(ns boostbox.forward
  "Send MSP's split payments to MSP-2.0's /api/boosts/ingest, where they feed the
   music chart. Since 2026-09-26 this bot is the chart's only live source; Helipad's
   webhook is turned off at the end of the rollout. See MSP-2.0's
   docs/superpowers/specs/2026-09-26-msp-bot-boost-ingest-design.md.

   Record building and queue bookkeeping are pure; `send!` is the one network call.
   MSP ignores a record it already holds, so every send here is safe to repeat."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [jsonista.core :as json]))

(def max-pending
  "Records kept for retry while MSP is unreachable. Past this the oldest are dropped
   and logged by the caller; a backfill run can resend them."
  500)

(def max-forwarded
  "Payment hashes remembered as sent, so a poll that re-reads its newest
   transactions does not send them again -- each send makes MSP rebuild a week."
  2000)

(def batch-size
  "Records per request: MSP writes one blob per record, inside one function timeout."
  25)

;; ~~~~~~~~~~~~~~~~~~~ Config ~~~~~~~~~~~~~~~~~~~

(defn- names [s]
  (into #{} (comp (map str/trim) (remove str/blank?) (map str/lower-case))
        (str/split (str s) #",")))

(defn env-config
  "Forwarding settings from `get-env`, a (fn [key default]). URL or token unset
   means forwarding is off -- the Boostr bot sets neither."
  [get-env]
  {:forward-url (some-> (get-env "BBN_FORWARD_URL" nil) str/trim not-empty)
   :forward-token (some-> (get-env "BBN_FORWARD_TOKEN" nil) str/trim not-empty)
   :forward-actions (let [named (names (get-env "BBN_FORWARD_ACTIONS" "boost,auto,stream"))]
                      (if (seq named) named #{"boost" "auto" "stream"}))})

(defn enabled?
  "Forwarding needs a URL, a token and a recipient filter. Without the filter the
   bot would send every split payment on the node -- other shows' boosts, with
   their listeners' messages -- to MSP."
  [{:keys [forward-url forward-token recipient-names]}]
  (boolean (and forward-url forward-token (seq recipient-names))))

;; ~~~~~~~~~~~~~~~~~~~ Records ~~~~~~~~~~~~~~~~~~~

(def ^:private boostbox->blip10
  "BoostBox's metadata names, as a boost link returns them, and the blip-10 names
   MSP's parser reads.

   `boost_link` is deliberately not here. In BoostBox metadata it is not the song's
   own URL: apps put a BoostBox permalink there (StableKraft's is another boost's
   tardbox permalink) or the feed address (BoostMeBitch), while MSP keys songs on
   it. So it is never forwarded.

   `position` is not renamed to blip-10's `ts` either. MSP resolves any record with
   `ts` and a guid on its `timesplit` rung, which gives no key and ignores the
   listener's message -- and a boost link's metadata (v4vmusic, Castamatic) carries
   `feed_guid`, `item_guid` and `position` but no remote guids, so every such boost
   would count and never chart. Under its own name it leaves MSP to resolve the
   record on `message`, joining its keysend twin, while raw storage keeps the
   playback position for a later resolver."
  {"feed_guid" "guid" "item_guid" "episode_guid" "feed_title" "podcast"
   "item_title" "episode" "recipient_name" "name" "group" "uuid"})

(defn link-metadata->tlv
  "A boost link's metadata as a blip-10 map. Keys already in blip-10 form win over
   their BoostBox spelling; `boost_link` never survives."
  [m]
  (let [base (apply dissoc m "boost_link" (keys boostbox->blip10))]
    (reduce-kv (fn [acc from to]
                 (if (and (contains? m from) (not (contains? acc to)))
                   (assoc acc to (get m from))
                   acc))
               base boostbox->blip10)))

(def ^:private normalized->blip10
  {:action "action" :app-name "app_name" :app-version "app_version" :message "message"
   :sender-name "sender_name" :sender-id "sender_id" :recipient-name "name"
   :podcast "podcast" :episode "episode" :url "url" :feed-guid "guid"
   :item-guid "episode_guid" :remote-feed-guid "remote_feed_guid"
   :remote-item-guid "remote_item_guid" :group "uuid"
   ;; not "ts", for the reason given at boostbox->blip10
   :position "position"
   :value-msat "value_msat" :value-msat-total "value_msat_total"})

(defn- boostagram->tlv
  "Last resort, when no original metadata survived: the normalized boostagram under
   blip-10 names, `position` excepted."
  [b]
  (into {} (keep (fn [[k v]] (when-some [name (normalized->blip10 k)] (when (some? v) [name v])))) b))

(defn- tlv-string
  "The `tlv` MSP receives: what Helipad would have seen for this payment."
  [{:keys [tlv-json link-metadata wallet-boostagram boostagram]}]
  (cond tlv-json tlv-json
        link-metadata (json/write-value-as-string (link-metadata->tlv link-metadata))
        wallet-boostagram (json/write-value-as-string wallet-boostagram)
        :else (json/write-value-as-string (boostagram->tlv boostagram))))

(defn ->record
  "One payment in the shape of Helipad's BoostRecord, as MSP's ingest parses it.
   Everything descriptive rides in `tlv`; the outer fields are what only the wallet
   knows."
  [{:keys [payment-hash received-msat settled-at] :as boost}]
  {"source" "boostbox"
   "payment_hash" payment-hash
   "direction" "incoming"
   "time" settled-at
   "value_msat" received-msat
   "tlv" (tlv-string boost)})

;; ~~~~~~~~~~~~~~~~~~~ State ~~~~~~~~~~~~~~~~~~~

(defn forwarded? [state hash]
  (boolean (some #{hash} (get state "forwarded" []))))

(defn- fresh-records
  "Records for `boosts` that are neither forwarded nor in `pending`, in the order
   given, each payment once."
  [state pending boosts]
  (let [queued (set (map #(get % "payment_hash") pending))]
    (->> boosts
         (remove #(or (forwarded? state (:payment-hash %))
                      (contains? queued (:payment-hash %))))
         (reduce (fn [[seen acc] b]
                   (if (contains? seen (:payment-hash b))
                     [seen acc]
                     [(conj seen (:payment-hash b)) (conj acc (->record b))]))
                 [#{} []])
         second)))

(defn enqueue
  "Append the record of each of `boosts` that is neither forwarded nor already
   queued to \"forward-pending\", in the order given, dropping the oldest past
   `max-pending`. Returns {:state :dropped}, `:dropped` being the records
   themselves, so the caller can log which payments a backfill must resend.

   The poll queues a payment in the same state that moves its cursor past it, and
   sends only from the queue: a crash mid-send then leaves it queued, not lost."
  [state boosts]
  (let [pending (vec (get state "forward-pending" []))
        all (into pending (fresh-records state pending boosts))
        n (max 0 (- (count all) max-pending))]
    {:state (assoc state "forward-pending" (vec (drop n all)))
     :dropped (subvec all 0 n)}))

(defn- mark-forwarded [state hashes]
  (let [hashes (vec hashes)
        kept (vec (remove (set hashes) (get state "forwarded" [])))]
    (assoc state "forwarded" (vec (take-last max-forwarded (into kept hashes))))))

;; ~~~~~~~~~~~~~~~~~~~ Sending ~~~~~~~~~~~~~~~~~~~

(defn send!
  "POST records to MSP's ingest as one JSON array. OK only on exactly 200: MSP
   answers 200 for a record it already holds, so OK means stored, now or before."
  [{:keys [forward-url forward-token]} records]
  (try
    (let [resp (http/post forward-url {:headers {"authorization" (str "Bearer " forward-token)
                                                 "content-type" "application/json"}
                                       :body (json/write-value-as-string (vec records))
                                       ;; past MSP's 60 s function limit, so a slow
                                       ;; ingest is not mistaken for a failed one
                                       :timeout 65000
                                       :throw false})]
      {:ok? (= 200 (:status resp)) :status (:status resp)})
    (catch Exception e
      {:ok? false :error (ex-message e)})))

(defn forward!
  "Send the queue, then `boosts` (tx->boost! results) that are neither sent nor
   queued, oldest first, in batches. The first failed batch stops sending; it and
   everything after it stay queued, oldest dropped past `max-pending`.
   Returns {:state :sent :queued :dropped}."
  [ctx state boosts]
  (let [pending (vec (get state "forward-pending" []))
        fresh (fresh-records state pending boosts)]
    (loop [[batch & more] (partition-all batch-size (into pending fresh))
           sent []
           failed []]
      (cond
        (nil? batch)
        (let [dropped (max 0 (- (count failed) max-pending))]
          {:state (-> state
                      (mark-forwarded (map #(get % "payment_hash") sent))
                      (assoc "forward-pending" (vec (drop dropped failed))))
           :sent (count sent)
           :queued (- (count failed) dropped)
           :dropped dropped})

        (seq failed) (recur more sent (into failed batch))
        (:ok? (send! ctx batch)) (recur more (into sent batch) failed)
        :else (recur more sent (into failed batch))))))
