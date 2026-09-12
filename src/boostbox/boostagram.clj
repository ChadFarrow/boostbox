(ns boostbox.boostagram
  "Turn a blip-10 boostagram into the things the bot needs: a BoostMetadata
   payload for BoostBox, NIP-73 tags, and human-readable note text.

   Everything here is a pure function of already-parsed data. JSON decoding
   lives in boostbox.nwc, at the edge, which keeps this -- the part with all
   the fiddly field mapping -- unit testable on its own."
  (:require [clojure.string :as str]
            [boostbox.applinks :as al]
            [boostbox.nostr :as nostr])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def boostagram-tlv-type
  "The custom TLV record type podcast apps put blip-10 JSON in. Originally
   chosen by Breez; universally adopted since."
  7629169)

(defn tlv-hex->string
  "Decode a hex-encoded TLV record value into its UTF-8 string."
  ^String [^String hex]
  (String. (nostr/hex->bytes hex) StandardCharsets/UTF_8))

;; ~~~~~~~~~~~~~~~~~~~ Coercion ~~~~~~~~~~~~~~~~~~~
;;
;; blip-10 is loosely typed in practice: several fields are declared
;; "StringOrNumber" and apps disagree about which they send.

(defn- ->str [v]
  (cond
    (nil? v) nil
    (string? v) (let [s (str/trim v)] (when-not (str/blank? s) s))
    :else (str v)))

;; ~~~~~~~~~~~~~~~~~~~ Bounds on payer-written text ~~~~~~~~~~~~~~~~~~~
;;
;; Everything downstream of normalize is signed and published under the bot's
;; own key, and every one of these fields is written by whoever paid us. The
;; amount buys the boost, not unlimited space in the bot's feed.

(def max-message-length 500)
(def max-title-length 200)
(def max-name-length 100)

(defn- clean
  "Trim, strip control characters, and bound the length.

   Control characters go because they let a payer forge structure in a rendered
   note -- a lone \\r overwriting a line, say. Newlines survive in a message,
   where they are ordinary, and not in a title, where they are not."
  ([v] (clean v max-message-length true))
  ([v max-len] (clean v max-len false))
  ([v max-len multiline?]
   (when (some? v)
     (let [s (str/replace (str v)
                          (if multiline? #"[\p{Cntrl}&&[^\n]]" #"\p{Cntrl}")
                          "")
           s (str/trim s)]
       (when-not (str/blank? s)
         (if (> (count s) max-len)
           (str (str/trimr (subs s 0 max-len)) "…")
           s))))))

(defn- ->int [v]
  (cond
    (nil? v) nil
    (integer? v) (long v)
    (number? v) (long v)
    (string? v) (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
    :else nil))

(defn- get* [m & ks]
  (some (fn [k] (let [v (get m k)] (when (some? v) v))) ks))

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn valid-feed-guid?
  "A `<podcast:guid>` is defined as a UUIDv5, so anything else must not be
   emitted as a NIP-73 `podcast:guid` -- a malformed `i` tag is worse than no
   tag, because it pollutes the global index and can never be matched."
  [s]
  (boolean (and (string? s) (re-matches uuid-re (str/trim s)))))

(defn valid-item-guid?
  "An episode guid is the RSS `<item><guid>`, which the RSS spec leaves as an
   arbitrary string -- in practice a URL or a host-specific id far more often
   than a UUID. NIP-73's `podcast:item:guid` takes it verbatim, so the only
   things to reject are blanks and values too long to be a real guid.

   Do not tighten this to valid-feed-guid?: episode-level tags would then be
   dropped for almost every real feed."
  [s]
  (boolean (and (string? s)
                (not (str/blank? s))
                (<= (count (str/trim s)) 256))))

;; ~~~~~~~~~~~~~~~~~~~ Normalization ~~~~~~~~~~~~~~~~~~~

(defn normalize
  "Normalize a parsed blip-10 boostagram (string keys) into a keyword-keyed map
   with consistent types.

   Note which GUID fields exist here. Alby Hub's own pre-parsed `boostagram`
   object keeps only the numeric feedID/itemID and drops every GUID, so a
   payload built from it cannot be tagged to a podcast. Always prefer the raw
   TLV record; see boostbox.nwc/extract-boostagram."
  [m]
  (when (map? m)
    {:action (some-> (get* m "action") ->str str/lower-case)
     :app-name (clean (get* m "app_name") max-name-length)
     :app-version (clean (get* m "app_version") max-name-length)
     :message (clean (get* m "message"))
     :sender-name (clean (get* m "sender_name") max-name-length)
     :sender-id (->str (get* m "sender_id"))
     :sender-npub (->str (get* m "sender_npub"))
     :recipient-name (clean (get* m "name" "recipient_name") max-name-length)
     ;; blip-10 names these "podcast" and "episode", but senders that model
     ;; their payload on BoostBox's own schema (BoostMeBitch, for one) send
     ;; "feed_title" and "item_title" instead. Accept both, exactly as the guid
     ;; fields below already do -- otherwise the note loses the show and
     ;; episode name and the stored boost loses both titles.
     :podcast (clean (get* m "podcast" "feed_title") max-title-length)
     :episode (clean (get* m "episode" "item_title") max-title-length)
     ;; blip-10 calls the feed address "url". BoostMeBitch sends it as
     ;; "boost_link" instead -- a name that means a BoostBox permalink
     ;; everywhere else in this repo, so read it last and only as a fallback.
     ;; Nothing here decides it really is a feed: feed-context hands it to
     ;; safefetch like any other payer-written URL, and a document that is not
     ;; RSS simply reads as no feed.
     :url (->str (get* m "url" "boost_link"))
     :feed-id (->str (get* m "feedID" "feedId"))
     :item-id (->str (get* m "itemID" "itemId"))
     ;; blip-10 calls the feed guid "guid" and the episode guid "episode_guid"
     :feed-guid (->str (get* m "guid" "feed_guid"))
     :item-guid (->str (get* m "episode_guid" "item_guid"))
     :remote-feed-guid (->str (get* m "remote_feed_guid"))
     :remote-item-guid (->str (get* m "remote_item_guid"))
     ;; "ts" is seconds into the episode, NOT a wall clock. "time" is a
     ;; human "HH:MM:SS" of the same thing. Neither is when the boost happened.
     ;; BoostBox's own schema calls the same thing "position".
     :position (or (->int (get* m "ts"))
                   (->int (get* m "time_seconds"))
                   (->int (get* m "position")))
     :value-msat (->int (get* m "value_msat"))
     :value-msat-total (->int (get* m "value_msat_total"))}))

(defn boost?
  "Only manual boosts are worth republishing. Per-minute streaming sats would
   flood the relays with near-empty notes."
  [b]
  (= "boost" (:action b)))

;; ~~~~~~~~~~~~~~~~~~~ Boost links ~~~~~~~~~~~~~~~~~~~
;;
;; A keysend can carry the boostagram in TLV 7629169, but an LNURL payment has
;; nowhere to put one -- so podcast apps put a BoostBox permalink in the BOLT11
;; description instead, and the metadata is fetched back from that URL. Most
;; Podcasting 2.0 apps take this route for lightning addresses, which makes it
;; the common case, not the fallback.

(defn- origin-of
  "scheme://host[:port], or nil if this is not an http(s) URL."
  [^String u]
  (try
    (let [uri (java.net.URI. (str/trim u))
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)
          port (.getPort uri)
          default (case scheme "https" 443 "http" 80 -1)]
      (when (and host (#{"http" "https"} scheme))
        (str scheme "://" host
             (when (and (not= -1 port) (not= port default)) (str ":" port)))))
    (catch Exception _ nil)))

(defn boost-link
  "The first URL in a payment description that is worth fetching.

   With `allowed-origins` empty this accepts any https URL, which it has to:
   podcast apps POST to a BoostBox *they* control, so a podcaster who adds this
   bot as a split cannot know in advance which instances their listeners' apps
   will name. An allowlist would silently skip almost every real boost.

   That makes this only half the check. It decides the *shape* of a link, not
   whether the address behind it is safe to contact -- that needs the host
   resolved, so it lives at the fetch, in nostrbot/fetchable-url?. Plaintext
   http is accepted only for an origin someone explicitly allowlisted.

   Match on origin rather than on a `rss::payment::` prefix, so apps that format
   the description differently still work."
  [description allowed-origins]
  (when (string? description)
    (let [allowed (into #{} (keep origin-of) allowed-origins)
          ok? (if (seq allowed)
                (fn [u] (contains? allowed (origin-of u)))
                (fn [u] (some-> (origin-of u) (str/starts-with? "https://"))))]
      (some (fn [u]
              (let [u (str/replace u #"[.,;:!?)\]]+$" "")]
                (when (ok? u) u)))
            (re-seq #"https?://[^\s\"'<>\\]+" description)))))

(defn boost-id-from-url
  "The ULID at the end of a BoostBox permalink."
  [^String url]
  (when (string? url)
    (last (remove str/blank? (str/split url #"/")))))

;; ~~~~~~~~~~~~~~~~~~~ The booster's own identity ~~~~~~~~~~~~~~~~~~~

(def ^:private hex-pubkey-re #"(?i)^[0-9a-f]{64}$")

(defn sender-npub
  "The booster's Nostr identity as a canonical npub, or nil.

   Two spellings, because apps disagree. BoostMeBitch sends `sender_npub`
   outright, and its `sender_id` happens to be the same key in hex -- worth
   reading, but only when it is exactly a 64-character hex pubkey, because for
   every other app `sender_id` is an app-internal string (\"abc123\") that
   means nothing here.

   The value is round-tripped through NIP-19 rather than passed along as text:
   that both validates it and normalizes whatever casing arrived, so a
   malformed npub is dropped instead of published.

   Both fields are payer-written, so this is a CLAIM about who paid, never a
   proof of it -- which is why it becomes a plain `sender` tag and deliberately
   not a `p`. A `p` tag would put this bot's signed note in the mentions of
   whatever npub a payer named: a notification the payer chose, the recipient
   cannot decline, and no one can delete."
  [b]
  (letfn [(canonical [^bytes pk] (try (nostr/->npub pk) (catch Exception _ nil)))]
    (or (when-let [s (some-> (:sender-npub b) str str/trim not-empty)]
          (try (canonical (nostr/decode-key s "npub")) (catch Exception _ nil)))
        (when-let [s (some-> (:sender-id b) str str/trim not-empty)]
          (when (re-matches hex-pubkey-re s)
            (try (canonical (nostr/hex->bytes (str/lower-case s)))
                 (catch Exception _ nil)))))))

;; ~~~~~~~~~~~~~~~~~~~ BoostMetadata payload ~~~~~~~~~~~~~~~~~~~

(defn- iso8601 [epoch-seconds]
  (str (Instant/ofEpochSecond (long epoch-seconds))))

(defn ->boost-payload
  "Build a POST /boost body from a normalized boostagram.

   `received-msat` is what actually arrived in the wallet and wins over the
   boostagram's own value_msat, which is only the sending app's claim about
   this split. `settled-at` is epoch seconds from the payment -- the boostagram
   carries no wall-clock time of its own.

   Keys are strings because this goes straight out as JSON; the server coerces
   it against BoostMetadata on arrival."
  [b {:keys [received-msat settled-at]}]
  (let [value-msat (or received-msat (:value-msat b) 1)
        total (or (:value-msat-total b) value-msat)
        value-msat (max (long value-msat) 1)
        ;; a wallet that received more than the boostagram's claimed total
        ;; would otherwise yield a split above 100%
        total (max (long total) value-msat 1)]
    (into {}
          (remove (comp nil? val))
          {"action" (or (:action b) "boost")
           "value_msat" value-msat
           "value_msat_total" total
           ;; percentage of the whole boost that landed here
           "split" (double (/ (* 100.0 value-msat) total))
           "timestamp" (iso8601 (or settled-at (quot (System/currentTimeMillis) 1000)))
           "message" (:message b)
           "app_name" (:app-name b)
           "app_version" (:app-version b)
           "sender_name" (:sender-name b)
           "sender_id" (:sender-id b)
           "sender_npub" (sender-npub b)
           "recipient_name" (:recipient-name b)
           "position" (:position b)
           "feed_guid" (:feed-guid b)
           "feed_title" (:podcast b)
           "item_guid" (:item-guid b)
           "item_title" (:episode b)
           "remote_feed_guid" (:remote-feed-guid b)
           "remote_item_guid" (:remote-item-guid b)})))

;; ~~~~~~~~~~~~~~~~~~~ NIP-73 tags ~~~~~~~~~~~~~~~~~~~

(def default-client-name
  "What NIP-89's `client` tag says when nothing overrides it.

   The name of the bot as readers meet it, not the name of this repo: a client
   renders this as \"via ...\" under the note. Override with BBN_CLIENT_NAME."
  "Boostr_Bot")

(def max-tag-item-length
  "The longest one item of one tag may be. Mirrors boostmebitch's
   MAX_TAG_ITEM_LEN, and the banner URL is the only thing here that can
   approach it -- it carries an artwork address and both titles."
  512)

(def banner-dimensions
  "The banner is one fixed size, so a client can reserve the space before the
   bytes arrive. Must agree with boostbox.banner."
  "1200x300")

(def ^:private nip73-id-fields
  "The boostagram fields that become NIP-73 external content ids, in emission
   order, each with the validator its kind demands.

   The remote pair carries a `<podcast:remoteItem>` boost: a listener boosting
   music played inside someone else's show. The payment settles against the
   host feed's splits, but the thing being boosted lives in another feed, and
   without these tags that reference is lost -- the remote feed's own audience
   can never find the boost by querying for it.

   A remote guid is validated exactly as its local counterpart is, and for the
   same reason: `podcast:guid` is a UUID and is lower-cased, an item guid is an
   arbitrary string and is taken verbatim. See valid-feed-guid?."
  [{:field :feed-guid        :kind "podcast:guid"      :valid? valid-feed-guid?
    :norm (fn [v] (str/lower-case (str/trim v)))}
   {:field :item-guid        :kind "podcast:item:guid" :valid? valid-item-guid?
    :norm str/trim}
   {:field :remote-feed-guid :kind "podcast:guid"      :valid? valid-feed-guid?
    :norm (fn [v] (str/lower-case (str/trim v)))}
   {:field :remote-item-guid :kind "podcast:item:guid" :valid? valid-item-guid?
    :norm str/trim}])

(defn- nip73-id-tags
  "The `i`/`k` tags for every content id the boostagram names.

   `k` answers \"what kinds of external content does this event reference\",
   so it is emitted once per kind rather than once per id -- a second identical
   `k` says nothing the first did not. Ids are de-duplicated because apps that
   send a remote item routinely repeat the host feed's guid in both fields, and
   the same `i` tag twice is noise a relay has to store forever."
  [b]
  (->> nip73-id-fields
       (keep (fn [{:keys [field kind valid? norm]}]
               (let [v (get b field)]
                 (when (valid? v) [kind (norm v)]))))
       (distinct)
       (reduce (fn [[tags seen] [kind v]]
                 [(cond-> (conj tags ["i" (str kind ":" v)])
                    (not (contains? seen kind)) (conj ["k" kind]))
                  (conj seen kind)])
               [[] #{}])
       (first)))

(defn ->nip73-tags
  "Every tag on the boost note, in the order boostmebitch emits them.

   The `i`/`k` content ids come from nip73-id-tags, which covers the remote
   pair as well as the host feed and episode. Tags are emitted only for GUIDs
   that pass their own validator -- and the validators differ on purpose, see
   valid-feed-guid?.

   Two attribution tags, and they answer different questions. `client` is
   NIP-89: the app that CREATED AND SIGNED this event, which is always this bot
   -- a client renders it as \"via ...\", so naming the paying app there would
   attribute the bot's own note to software that never signed it. `app` is the
   app that SENT the boost, the convention boostmebitch uses on its kind:3369
   receipts. It is payer-written text, so it is a claim, not a fact."
  [b {:keys [boost-url npubs banner-url client-name total-msat]}]
  (let [banner-item (when banner-url (str "url " banner-url))
        sender (sender-npub b)]
    (cond-> (nip73-id-tags b)
      boost-url
      (conj ["r" boost-url])

      ;; The people the feed names. Capped upstream in boostbox.feed: a p tag
      ;; notifies a stranger under this bot's identity, permanently.
      (seq npubs)
      (into (for [n npubs] ["p" (:pubkey n)]))

      ;; NIP-92, so a client that renders from tags shows the same picture as
      ;; one that scans the text. Dropped rather than truncated when it is too
      ;; long: the body still names the banner, so every text-scanning client
      ;; still renders it, and an over-long tag item is refused by relays that
      ;; bound them.
      (and banner-item (<= (count banner-item) max-tag-item-length))
      (conj ["imeta" banner-item "m image/png" (str "dim " banner-dimensions)])

      (and total-msat (pos? (long total-msat)))
      (conj ["amount" (str (long total-msat))])

      :always
      (conj ["client" (or (not-empty (str client-name)) default-client-name)])

      ;; The version rides along as a third item: a reader looking at element 1
      ;; still finds the name, and a pass-through service that drops what the
      ;; sending app told us about itself cannot get it back later.
      (:app-name b)
      (conj (cond-> ["app" (:app-name b)]
              (:app-version b) (conj (:app-version b))))

      ;; Which split this note is announcing. Not standard anywhere; it is the
      ;; recipient the paying app addressed, which on a multi-split boost is
      ;; the only thing distinguishing one recipient's note from another's.
      (:recipient-name b)
      (conj ["recipient" (:recipient-name b)])

      ;; Who paid, when the app knew. Not a `p` tag on purpose -- see
      ;; sender-npub.
      sender
      (conj ["sender" sender])

      :always
      (into [["t" "boostagram"] ["t" "value4value"]]))))

;; ~~~~~~~~~~~~~~~~~~~ Note content ~~~~~~~~~~~~~~~~~~~

(defn format-sats [msat]
  (let [sats (quot (long (or msat 0)) 1000)]
    (str (String/format java.util.Locale/US "%,d" (object-array [sats])) (if (= 1 sats) " sat" " sats"))))

(defn note-sats
  "The sats figure the note says, formatted as boostmebitch formats it: plain,
   always plural, no group separator.

   Deliberately not `format-sats`. That one groups thousands and says \"1 sat\",
   and it reads better -- but it renders the HTML pages, and a boost note and a
   boost card showing different strings for one payment is worse than either
   choice on its own. Keep the two apart, and change both or neither."
  [msat]
  (str (quot (long (or msat 0)) 1000) " sats"))

(defn note-total-msat
  "What the note calls the boost.

   value_msat_total is absent often enough -- Alby's parsed struct drops it,
   single-recipient splits never set it -- that using it alone puts \"0 sats\"
   in the headline of a real boost."
  [b received-msat]
  (or (:value-msat-total b) received-msat (:value-msat b)))

(def banner-param-order
  "The order the query string is written in.

   Fixed, and not merely the map's iteration order, because the URL itself is
   the contract: two notes describing the same boost must carry byte-identical
   picture URLs, or a client caches one and refetches the other."
  [:art :title :ep :sats])

(defn banner-params
  "The picture's parameters, under the names boostbox.banner reads them by.

   One value, two consumers. banner-url below renders it into the URL a note
   carries; the bot hands the same map straight to boostbox.banner/banner-png
   to draw those exact bytes for a Mastodon attachment, which is why the keys
   are banner's query-parameter names rather than this namespace's field names.
   Naming them once is what keeps the URL on a note and the picture posted
   beside it from describing different boosts.

   The names are boostmebitch's and they are a PERMANENT PUBLIC CONTRACT -- see
   banner-url directly below."
  [b art total-msat]
  (let [sats (quot (long (or total-msat 0)) 1000)]
    (cond-> {}
      art (assoc :art art)
      (:podcast b) (assoc :title (:podcast b))
      (:episode b) (assoc :ep (:episode b))
      (pos? sats) (assoc :sats sats))))

(defn banner-url
  "The picture the note carries: the boost banner on the BoostBox web app.

   The parameter names are boostmebitch's, and they are a PERMANENT PUBLIC
   CONTRACT -- every note ever published writes this URL into a signed kind:1,
   which cannot be edited, so renaming one blanks the picture on all of them at
   once. They must keep agreeing with boostbox.banner, which serves them. Add
   parameters; never repurpose one.

   Returns nil with no base URL, so a bot with nowhere to serve a picture from
   publishes a note with no picture rather than a broken link."
  [base-url b art total-msat]
  (when-not (str/blank? (str base-url))
    (let [enc #(java.net.URLEncoder/encode (str %) "UTF-8")
          params (banner-params b art total-msat)
          qs (for [k banner-param-order
                   :let [v (get params k)]
                   :when (some? v)]
               (str (name k) "=" (enc v)))]
      (str (str/replace (str base-url) #"/+$" "") "/og/boost.png"
           (when (seq qs) (str "?" (str/join "&" qs)))))))

(defn ->note-content
  "The human-readable body of the kind:1 note, laid out as boostmebitch lays
   its own out (lib/nostr/boost-notes.ts, formatContent).

   Everything except the amount is conditional: plenty of real boosts arrive
   with no message, no episode, or no sender name. With no sender the
   attribution line reads \"Boosted 100 sats\" rather than naming nobody.

   The banner URL goes last, on its own, because a Nostr client decides whether
   a bare URL is an image from the text around it.

   The BoostBox permalink is deliberately NOT here. It stays on the `r` tag,
   where a client that wants the full metadata can still find it, but a reader
   has no use for it: clients render a bare link as a preview card, and a card
   whose whole content is a boost id sits under every note saying nothing the
   note did not already say. The app link above is the one a reader wants --
   it goes to the episode, not to a record of the payment.

   The app line is the one departure from BMB's layout. It points back into the
   app the boost came from -- the episode where the app has a route for one,
   the show otherwise -- so a reader can go listen to the thing that was
   boosted. Its origin comes from boostbox.applinks' own table and never from
   the payment, which is what makes it safe to sign: see that namespace."
  [b {:keys [received-msat banner-url]}]
  (let [total (note-total-msat b received-msat)
        show (:podcast b)
        episode (:episode b)
        sender (:sender-name b)
        message (:message b)
        app (al/app-link b)
        links (->> [banner-url] (remove str/blank?) (remove nil?))]
    (->> (concat
          ["⚡ Boost ⚡" ""]
          (when message [message ""])
          [(str (if sender (str sender " boosted") "Boosted")
                " " (note-sats total)
                (when show (str " → " show)))]
          (when episode [(str "📻 " episode)])
          ;; label and URL on separate lines: a client that pulls the URL out
          ;; into a preview card would otherwise leave the separator dangling
          ;; at the end of the label, and one that renders links inline reads
          ;; the same either way.
          (when app [(str (if (:episode? app) "▶️ Listen on " "🎧 Find it on ")
                          (:label app))
                     (:url app)])
          (mapcat (fn [l] ["" l]) links))
         (str/join "\n")
         (str/trimr))))

;; ~~~~~~~~~~~~~~~~~~~ Mastodon ~~~~~~~~~~~~~~~~~~~
;;
;; The same boost, said for a different room. The body below is ->note-content's
;; line for line wherever it can be, because a reader who follows the bot in
;; both places should see one kind of card -- but three things genuinely differ
;; and none of them is cosmetic:
;;
;; 1. A status has a hard character limit and a note does not.
;; 2. A Mastodon reader cannot resolve an npub, so nostr identifiers are not
;;    carried over as text that would read as line noise.
;; 3. Nostr's tags are a separate field that costs a reader nothing. Mastodon
;;    has no such field, so anything worth keeping has to earn its place in the
;;    body or be dropped.

(def mastodon-hashtags
  "Discovery, in the only place Mastodon offers it.

   These are ->nip73-tags' two `t` tags, moved into the body because a status
   has nowhere else to put them. Keeping the same two words means one boost is
   findable under the same search on either network."
  "#boostagram #value4value")

(def default-mastodon-max-chars
  "Mastodon's stock status limit.

   Only the floor to assume when nobody has said otherwise: instances routinely
   raise it, and scripts/mastodon-check.sh reports what the configured one
   actually allows. Assuming too little costs a few truncated characters;
   assuming too much means the server rejects the post outright."
  500)

(defn- truncate-chars
  "Cut a string to `n` characters, marking the cut with an ellipsis.

   The trailing-surrogate check is not paranoia: emoji are ordinary in a boost
   message, they occupy two chars each, and a cut landing between the halves
   leaves a lone surrogate that renders as a replacement box and is not valid
   UTF-8 on the wire."
  [^String s ^long n]
  (if (<= (count s) n)
    s
    (let [end (max 0 (dec n))
          end (if (and (pos? end) (Character/isHighSurrogate (.charAt s (dec end))))
                (dec end)
                end)]
      (str (str/trimr (subs s 0 end)) "…"))))

(defn- defang
  "Stop payer-written text from addressing anyone, or going anywhere.

   Mastodon parses the body of a status. `@user@host` in it becomes a real
   mention that lands in that account's notifications, and `#word` files the
   post under that hashtag. Both would be chosen by whoever paid, on a post
   this bot published under its own name -- which is exactly the attack the
   sender-tag-not-p-tag rule exists to stop on the other network, and on this
   one it is also the fastest way to have the account suspended: a boost
   costing one sat could mention a stranger repeatedly, or stuff the bot's post
   into thirty unrelated public timelines.

   A zero-width space after the sigil stops the parser matching without
   removing anything: a message saying \"@dave\" still reads \"@dave\". Applied
   to the payer's fields only -- the post's own two hashtags are ours, and the
   app label comes from boostbox.applinks' table."
  [s]
  (when s
    (str/replace (str s) #"(?<=[@#])(?=[\p{L}\p{N}_])" "\u200b")))

(defn banner-alt-text
  "Alt text for the banner attachment.

   Built from the same three facts the picture is drawn from, so it describes
   what is actually in the image. Mastodon readers expect alt text and screen
   readers get nothing from the banner without it -- the amount, the show and
   the episode are the whole content of the picture."
  [b total-msat]
  (let [show (:podcast b)
        episode (:episode b)]
    (str "Boost banner: " (note-sats total-msat)
         (when show (str " to " show))
         (when episode (str " — " episode)))))

(defn ->mastodon-content
  "The body of the Mastodon status announcing one boost.

   ->note-content's layout, with four deliberate differences:

   - **No banner URL.** The picture is a real media attachment, so the URL
     would be a second copy of something already on the post, paid for out of
     the character budget.
   - **No npub, anywhere.** `sender` on a note is a nostr identifier a Mastodon
     client cannot resolve; as text it is forty characters of noise.
   - **A fediverse link, when the feed declared one.** This is the counterpart
     of a note's `p` tags, and like them the value comes ONLY from the feed --
     a podcaster naming their own account in their own RSS. It is emitted as a
     plain URL and never as an `@mention`: a mention would put this bot's posts
     in someone's notifications on the say-so of whoever paid, which is the
     same attack the `sender`-not-`p` rule exists to stop, with an instance
     suspension on the end of it.
   - **A hard length cap.** The payer's message is the only part that gives
     way; the attribution, the show, the app link and the hashtags are what the
     post is for.

   Every payer-written field goes through `defang` first, so nothing in a boost
   can turn into a mention or a hashtag on a post this bot signs its own name
   to. See that function -- it is the Mastodon half of the rule that keeps a
   payer's npub off a note as a `p` tag.

   The character budget is counted conservatively. Java counts an astral
   character as two where Mastodon counts one, and Mastodon counts any URL as
   23 characters however long it is, so this always measures the post as at
   least as long as the server will -- erring towards a slightly short message
   rather than a rejected post.

   The BoostBox permalink is left out for the same reason ->note-content leaves
   it out: a client renders a bare link as a preview card, and a card whose
   whole content is a boost id says nothing the post has not already said."
  [b {:keys [received-msat fediverse max-chars]}]
  (let [total (note-total-msat b received-msat)
        limit (long (or max-chars default-mastodon-max-chars))
        ;; every one of these four is written by whoever paid -- see defang
        show (defang (:podcast b))
        episode (defang (:episode b))
        sender (defang (:sender-name b))
        app (al/app-link b)
        profile (some-> fediverse str str/trim not-empty)
        head "⚡ Boost ⚡"
        tail (->> (concat
                   [(str (if sender (str sender " boosted") "Boosted")
                         " " (note-sats total)
                         (when show (str " → " show)))]
                   (when episode [(str "📻 " episode)])
                   (when app [(str (if (:episode? app) "▶️ Listen on " "🎧 Find it on ")
                                   (:label app))
                              (:url app)])
                   (when profile ["" profile])
                   ["" mastodon-hashtags])
                  (str/join "\n"))
        ;; What the post costs with no message at all: the header, the blank
        ;; line under it, everything below, and the blank line a message would
        ;; add between the two.
        budget (- limit (count head) 2 (count tail) 2)
        message (some-> (:message b) defang str/trim not-empty)
        message (when (and message (pos? budget)) (truncate-chars message budget))]
    (->> (concat [head ""]
                 (when message [message ""])
                 [tail])
         (str/join "\n")
         (str/trimr))))
