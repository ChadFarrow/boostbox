(ns boostbox.boostagram
  "Turn a blip-10 boostagram into the things the bot needs: a BoostMetadata
   payload for BoostBox, NIP-73 tags, and human-readable note text.

   Everything here is a pure function of already-parsed data. JSON decoding
   lives in boostbox.nwc, at the edge, which keeps this -- the part with all
   the fiddly field mapping -- unit testable on its own."
  (:require [clojure.string :as str]
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
     :recipient-name (clean (get* m "name" "recipient_name") max-name-length)
     ;; blip-10 names these "podcast" and "episode", but senders that model
     ;; their payload on BoostBox's own schema (BoostMeBitch, for one) send
     ;; "feed_title" and "item_title" instead. Accept both, exactly as the guid
     ;; fields below already do -- otherwise the note loses the show and
     ;; episode name and the stored boost loses both titles.
     :podcast (clean (get* m "podcast" "feed_title") max-title-length)
     :episode (clean (get* m "episode" "item_title") max-title-length)
     :url (->str (get* m "url"))
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
           "recipient_name" (:recipient-name b)
           "position" (:position b)
           "feed_guid" (:feed-guid b)
           "feed_title" (:podcast b)
           "item_guid" (:item-guid b)
           "item_title" (:episode b)
           "remote_feed_guid" (:remote-feed-guid b)
           "remote_item_guid" (:remote-item-guid b)})))

;; ~~~~~~~~~~~~~~~~~~~ NIP-73 tags ~~~~~~~~~~~~~~~~~~~

(defn ->nip73-tags
  "NIP-73 external content id tags for the podcast and episode this boost was
   sent to, plus a link to the BoostBox permalink.

   Each `i` tag is paired with a `k` tag naming its kind, so clients can query
   every event for a kind. Tags are emitted only for GUIDs that are actually
   UUIDs."
  [b {:keys [boost-url]}]
  (let [feed (:feed-guid b)
        item (:item-guid b)]
    (cond-> []
      (valid-feed-guid? feed)
      (into [["i" (str "podcast:guid:" (str/lower-case (str/trim feed)))]
             ["k" "podcast:guid"]])

      (valid-item-guid? item)
      ;; not lower-cased: an item guid is an opaque string and may well be a
      ;; case-sensitive URL, unlike the feed guid's UUID
      (into [["i" (str "podcast:item:guid:" (str/trim item))]
             ["k" "podcast:item:guid"]])

      boost-url
      (conj ["r" boost-url])

      :always
      (conj ["t" "boostagram"]))))

;; ~~~~~~~~~~~~~~~~~~~ Note content ~~~~~~~~~~~~~~~~~~~

(defn format-sats [msat]
  (let [sats (quot (long (or msat 0)) 1000)]
    (str (String/format java.util.Locale/US "%,d" (object-array [sats])) (if (= 1 sats) " sat" " sats"))))

(defn ->note-content
  "The human-readable body of the kind:1 note.

   Everything except the amount is conditional: plenty of real boosts arrive
   with no message, no episode, or no sender name."
  [b {:keys [boost-url received-msat]}]
  (let [;; value_msat_total is absent often enough -- Alby's parsed struct
        ;; drops it, single-recipient splits never set it -- that using it
        ;; alone puts "0 sats" in the headline of a real boost.
        total (or (:value-msat-total b) received-msat (:value-msat b))
        show (:podcast b)
        episode (:episode b)
        sender (:sender-name b)
        message (:message b)]
    (->> [(str "⚡ " (format-sats total) " boost"
               (when show (str " to " show))
               (when episode (str " — " episode))
               (when sender (str "\nfrom " sender)))
          (when message (str "\n\"" message "\""))
          (when boost-url (str "\n" boost-url))]
         (remove nil?)
         (str/join "")
         (str/trim))))
