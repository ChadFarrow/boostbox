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

(defn valid-guid?
  "Podcast GUIDs in the podcast namespace are UUIDs. Anything else must not be
   emitted as a NIP-73 id -- a malformed `i` tag is worse than no tag, because
   it pollutes the global index and can never be matched."
  [s]
  (boolean (and (string? s) (re-matches uuid-re (str/trim s)))))

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
     :app-name (->str (get* m "app_name"))
     :app-version (->str (get* m "app_version"))
     :message (->str (get* m "message"))
     :sender-name (->str (get* m "sender_name"))
     :sender-id (->str (get* m "sender_id"))
     :recipient-name (->str (get* m "name"))
     :podcast (->str (get* m "podcast"))
     :episode (->str (get* m "episode"))
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
     :position (or (->int (get* m "ts")) (->int (get* m "time_seconds")))
     :value-msat (->int (get* m "value_msat"))
     :value-msat-total (->int (get* m "value_msat_total"))}))

(defn boost?
  "Only manual boosts are worth republishing. Per-minute streaming sats would
   flood the relays with near-empty notes."
  [b]
  (= "boost" (:action b)))

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
        total (max (long total) 1)
        value-msat (max (long value-msat) 1)]
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
      (valid-guid? feed)
      (into [["i" (str "podcast:guid:" (str/lower-case (str/trim feed)))]
             ["k" "podcast:guid"]])

      (valid-guid? item)
      (into [["i" (str "podcast:item:guid:" (str/lower-case (str/trim item)))]
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
  [b {:keys [boost-url]}]
  (let [total (:value-msat-total b)
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
