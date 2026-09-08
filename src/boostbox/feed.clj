(ns boostbox.feed
  "The two things a boost note needs out of an RSS feed and nothing else: the
   npubs the feed declares for its people, and a cover image.

   Modelled on boostmebitch's lib/feed-xml.ts so the two apps agree about what
   a feed says. Two rules carry over from there, and both are load-bearing:

   1. Walk the document with a LINEAR scan, never a `[\\s\\S]*?` or
      `<tag\\b[^>]*>` regex. Those are O(n^2) on repeated unclosed opens --
      800 KB of `<!--` measured 38.7 s over there, and the same document here
      would pin the bot's poll loop for as long.
   2. Read an attribute anchored on `(?:^|\\s)`, never `\\b`. `\\bnpub` matches
      inside `x-npub`, so a decoy attribute is read as the real one.

   A feed is a document written by someone we do not control, reached by a URL
   written by whoever paid us, so everything here is bounded: the input, the
   number of items walked, the number of npubs returned, and the length of any
   URL handed back."
  (:require [clojure.string :as str]
            [boostbox.nostr :as nostr]))

(def max-feed-npubs
  "How many npubs one feed contributes. Mirrors MAX_FEED_NPUBS in BMB."
  4)

(def max-items-scanned
  "A feed with more items than this gets its first N looked at for episode art.
   The show-level art below needs no walk at all, so a long feed still gets a
   picture."
  500)

(def max-url-length
  "Longer than this is not a real artwork address."
  600)

(def max-npub-tags-scanned
  "How many `<podcast:txt>` / `<podcast:person>` tags are examined before we
   stop looking. Only four npubs are ever returned, so a feed carrying more
   than this is not a feed we are failing to read -- it is a document sized to
   make us work. The scan below is linear, but linear on 200k tags is still
   seconds the poll loop does not have."
  200)

;; ~~~~~~~~~~~~~~~~~~~ Linear scan ~~~~~~~~~~~~~~~~~~~

(defn strip-comments
  "Remove `<!-- ... -->` spans in one forward pass.

   Without this a commented-out `<podcast:txt>` reads as a live one, which is
   how a feed's old npub outlives the person who left the show. An unterminated
   comment truncates the rest of the document, exactly as a parser would."
  [^String xml]
  (if-not (str/includes? xml "<!--")
    xml
    (let [sb (StringBuilder.)]
      (loop [from 0]
        (if-let [open (str/index-of xml "<!--" from)]
          (do (.append sb (subs xml from open))
              (if-let [close (str/index-of xml "-->" (+ open 4))]
                (recur (+ close 3))
                (.toString sb)))            ; unterminated: the rest is comment
          (do (.append sb (subs xml from))
              (.toString sb)))))))

(defn- tag-end
  "Index just past the `>` closing the tag that starts at `open`, respecting
   quoted attribute values so `title=\"a > b\"` does not end it early. nil when
   the tag is never closed."
  [^String xml ^long open]
  (loop [i open, quote nil]
    (when (< i (.length xml))
      (let [c (.charAt xml i)]
        (cond
          quote (recur (inc i) (when-not (= c (char quote)) quote))
          (or (= c \") (= c \')) (recur (inc i) c)
          (= c \>) (inc i)
          :else (recur (inc i) nil))))))

(defn find-tags
  "Every `<name ...>` open tag, as {:attrs :self-closing? :after}, where
   `:after` is the index just past the tag.

   One forward pass: each step starts from the end of the previous hit, so the
   work is linear in the document rather than in the number of candidate
   matches."
  [^String xml ^String name]
  (let [needle (str "<" name)
        n (count needle)]
    (loop [from 0, out []]
      (if-let [open (str/index-of xml needle from)]
        (let [next-ch (when (< (+ open n) (.length xml)) (.charAt xml (+ open n)))]
          (if-not (contains? #{\space \tab \newline \return \/ \>} next-ch)
            ;; <podcast:person> must not match <podcast:personality>
            (recur (+ open n) out)
            (if-let [end (tag-end xml open)]
              (let [raw (subs xml (+ open n) (dec end))
                    self? (str/ends-with? (str/trimr raw) "/")
                    attrs (if self? (str/trimr (subs raw 0 (dec (count (str/trimr raw))))) raw)]
                (recur end (conj out {:attrs attrs :self-closing? self? :after end})))
              out)))
        out))))

(defn- close-positions
  "Where every `</name` sits, in one forward pass."
  [^String xml ^String name]
  (let [needle (str "</" name)
        n (count needle)]
    (loop [from 0, out (transient [])]
      (if-let [i (str/index-of xml needle from)]
        (recur (+ i n) (conj! out i))
        (persistent! out)))))

(defn- first-at-or-after
  "Binary search: the first position in `v` that is >= `x`, or nil."
  [v ^long x]
  (loop [lo 0, hi (count v)]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)]
        (if (< (long (nth v mid)) x)
          (recur (inc mid) hi)
          (recur lo mid)))
      (when (< lo (count v)) (nth v lo)))))

(defn find-blocks
  "Every `<name ...> ... </name>` block, as a `find-tags` hit plus `:inner`.
   A self-closing tag has an empty `:inner`; it carries no text node, so it
   names nobody.

   The closes are located ONCE and then binary-searched, rather than scanned
   for from each open tag. Scanning per open tag is the quadratic shape this
   namespace exists to avoid, and it does not announce itself: 200k unclosed
   `<podcast:txt>` opens measured 72 s that way and 30 ms this way, on a
   document a feed can serve by accident."
  [^String xml ^String name]
  (let [closes (close-positions xml name)]
    (for [{:keys [after self-closing?] :as hit} (find-tags xml name)]
      (assoc hit :inner
             (if self-closing?
               ""
               (if-let [i (first-at-or-after closes after)]
                 (subs xml after i)
                 ""))))))

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "#39" "'" "#34" "\""})

(defn decode-xml-text
  "Resolve the entities a feed actually uses, plus numeric ones. Anything else
   is left as written -- a stray `&` in a title is not an error to guess at."
  [^String s]
  (-> (str/replace (str s) #"&#x([0-9a-fA-F]+);"
                   (fn [[_ hex]] (str (char (Integer/parseInt hex 16)))))
      (str/replace #"&#(\d+);" (fn [[_ dec]] (str (char (Integer/parseInt dec)))))
      (str/replace #"&([a-zA-Z]+);" (fn [[whole nm]] (get entities nm whole)))
      (str/replace "<![CDATA[" "")
      (str/replace "]]>" "")))

(defn read-attr
  "One attribute out of a tag's attribute text.

   Anchored on `(?:^|\\s)`, never `\\b`: `\\bnpub` matches inside `x-npub`, so a
   decoy attribute would be read as the real one. The attribute text is a
   single tag's worth, so a regex is safe here in a way it is not on a whole
   document."
  [^String attrs ^String name]
  (when attrs
    (let [re (re-pattern (str "(?i)(?:^|\\s)" (java.util.regex.Pattern/quote name)
                              "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"))]
      (when-let [m (re-find re attrs)]
        (some-> (or (nth m 1) (nth m 2) (nth m 3)) decode-xml-text str/trim not-empty)))))

;; ~~~~~~~~~~~~~~~~~~~ npubs ~~~~~~~~~~~~~~~~~~~

(def ^:private nostr-txt-purposes
  "Two spellings in the wild: Podhome writes purpose=\"npub\", others write
   \"nostr\". Accepting both is safe because the value still has to decode as a
   bech32 npub below."
  #{"nostr" "npub"})

(defn decode-npub
  "{:npub :pubkey} for a bech32 npub, or nil.

   Only an `npub` names a person: `nprofile`, `note` and `nevent` are rejected,
   and so is bare hex -- a p tag built from a value that was never an npub
   names whoever that hex happens to belong to."
  [raw]
  (let [npub (some-> raw str str/trim (str/replace #"(?i)^nostr:" ""))]
    (when (and npub (str/starts-with? npub "npub1"))
      (try
        {:npub npub :pubkey (nostr/bytes->hex (nostr/decode-key npub "npub"))}
        (catch Exception _ nil)))))         ; a typo'd npub is dropped, not tagged

(defn feed-npubs
  "Up to `max-feed-npubs` people the feed declares, deduped by pubkey.

   `<podcast:txt>` before `<podcast:person>` because the cap truncates, so the
   order is data: the show's own npub should survive a feed that lists a dozen
   guests."
  [^String xml]
  (let [txt (for [{:keys [attrs inner]} (take max-npub-tags-scanned
                                              (find-blocks xml "podcast:txt"))
                  :let [purpose (some-> (read-attr attrs "purpose") str/lower-case)]
                  :when (contains? nostr-txt-purposes purpose)]
              (decode-npub (decode-xml-text inner)))
        person (for [{:keys [attrs]} (take max-npub-tags-scanned
                                           (find-tags xml "podcast:person"))]
                 (decode-npub (read-attr attrs "npub")))]
    (->> (concat txt person)
         (remove nil?)
         (reduce (fn [acc n]
                   (if (some #(= (:pubkey %) (:pubkey n)) acc)
                     acc
                     (conj acc n)))
                 [])
         (take max-feed-npubs)
         vec)))

;; ~~~~~~~~~~~~~~~~~~~ Artwork ~~~~~~~~~~~~~~~~~~~

(defn- usable-art [url]
  (let [u (some-> url str str/trim)]
    (when (and u
               (<= (count u) max-url-length)
               (str/starts-with? (str/lower-case u) "https://"))
      u)))

(defn- image-in
  "`<itunes:image href>` first, then `<image><url>`, within one slice."
  [^String slice]
  (or (some usable-art
            (for [{:keys [attrs]} (find-tags slice "itunes:image")]
              (read-attr attrs "href")))
      (some usable-art
            (for [{:keys [inner]} (find-blocks slice "image")
                  {u :inner} (find-blocks inner "url")]
              (decode-xml-text u)))))

(defn- channel-slice
  "The channel's own markup, with the items cut off.

   A `<podcast:liveItem>` or `<item>` carries its own `<itunes:image>`, and
   reading one of those as the show's cover puts a single episode's art on
   every boost for the feed."
  [^String xml]
  (let [start (or (str/index-of xml "<channel") 0)
        ends (keep #(str/index-of xml % start) ["<item" "<podcast:liveItem"])]
    (subs xml start (if (seq ends) (apply min ends) (count xml)))))

(defn item-art
  "The cover on the `<item>` whose guid matches, or nil.

   Matched on the guid the boostagram carries rather than on position: an item
   list is not ordered by anything we know, and the wrong item's art is a
   picture of a different episode."
  [^String xml item-guid]
  (when-not (str/blank? (str item-guid))
    (let [needle (str/trim (str item-guid))]
      (some (fn [{:keys [inner]}]
              (when (some #(= needle (str/trim (decode-xml-text (:inner %))))
                          (find-blocks inner "guid"))
                (image-in inner)))
            (take max-items-scanned (find-blocks xml "item"))))))

(defn feed-art
  "The best cover for this boost: the episode's own, else the show's."
  [^String xml item-guid]
  (or (item-art xml item-guid)
      (image-in (channel-slice xml))))

;; ~~~~~~~~~~~~~~~~~~~ Entry point ~~~~~~~~~~~~~~~~~~~

(defn read-feed
  "{:npubs :art} for one feed document. Returns nil for anything unusable, so
   a caller has one thing to test rather than several."
  [^String xml item-guid]
  (try
    (when-not (str/blank? xml)
      (let [clean (strip-comments xml)
            npubs (feed-npubs clean)
            art (feed-art clean item-guid)]
        (when (or (seq npubs) art)
          {:npubs npubs :art art})))
    (catch Exception _ nil)))
