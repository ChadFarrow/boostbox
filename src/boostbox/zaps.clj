(ns boostbox.zaps
  "What the listener actually spent on a boost, read from the zap receipt the
   paying app published for it.

   A boostagram's `value_msat_total` is what the show's splits were paid. Some
   apps charge on top of that, and say so only on Nostr: Fountain pays a boost
   as a NIP-57 zap to its own account (\"Sent 123 sats to Fountain Boost Bot\")
   and then pays the splits out of it, keeping the difference -- 123 against a
   `value_msat_total` of 101 on 2026-10-11. The receipt is the only place the
   123 is written down, so this finds it.

   Everything a boost says is payer-written, so a receipt counts only when the
   app's own key signed it: `apps` names that key, and the signature is checked
   here rather than trusted from the relay. The sender and the episode, which
   the payer chose, only pick which of that app's receipts to read."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manifold.stream :as s]
            [com.brunobonacci.mulog :as u]
            [boostbox.nostr :as nostr]
            [boostbox.relay :as relay]))

(def apps
  "Per app, by its folded `app_name`: the pubkey that signs its zap receipts,
   and the relays its zap requests name. Both are literals, never read from a
   boost."
  {"fountain" {:pubkey "b866ce76be5b826695980248322b8df4c381608ffa5a5b47c4f3abe0d8f767a5"
               :relays ["wss://relay.fountain.fm" "wss://relay.primal.net"]}})

(def window-sec
  "How far from the payment's settled_at a receipt may be dated. Fountain's
   came two seconds before the payment settled on the bot's wallet."
  600)

(def query-timeout-ms 8000)

(defn app-for
  "The `apps` entry for a boost's app, or nil."
  [b]
  (some-> (:app-name b) str str/trim str/lower-case apps))

(def ^:private multipliers
  "msat per unit of a BOLT11 amount, by its multiplier letter."
  {"" 100000000000 "m" 100000000 "u" 100000 "n" 100})

(defn bolt11-msat
  "The amount a BOLT11 invoice asks for, in msat, or nil. Read from the
   human-readable part, which is everything before the last `1` (bech32 data
   never contains one). `p` is a tenth of a msat and is only exact in tens."
  [invoice]
  (let [inv (some-> invoice str str/trim str/lower-case)
        sep (some-> inv (str/last-index-of "1"))
        hrp (when sep (subs inv 0 sep))]
    (when-let [[_ digits mult] (some->> hrp (re-matches #"ln(?:bc|tb|bcrt|tbs)(\d{1,18})([munp]?)"))]
      (let [n (Long/parseLong digits)]
        (if (= "p" mult)
          (when (zero? (mod n 10)) (quot n 10))
          (try (Math/multiplyExact n (long (multipliers mult)))
               (catch ArithmeticException _ nil)))))))

(defn- wire->event
  "A relay's event JSON as the map boostbox.nostr signs and verifies."
  [m]
  {:id (get m "id")
   :pubkey (get m "pubkey")
   :created-at (get m "created_at")
   :kind (get m "kind")
   :tags (get m "tags")
   :content (get m "content")
   :sig (get m "sig")})

(defn- tag-values [event k]
  (keep (fn [t] (when (and (sequential? t) (= k (first t))) (second t))) (:tags event)))

(defn receipt-msat
  "The amount a zap receipt says was paid, if it is one of `app-pubkey`'s,
   names `sender-hex` as the payer and `item-guid` as what was zapped, and its
   signature holds. Nil otherwise."
  [event {:keys [app-pubkey sender-hex item-guid]}]
  (when (and (= 9735 (:kind event))
             (= app-pubkey (:pubkey event))
             (some #{sender-hex} (tag-values event "P"))
             (some #{(str "podcast:item:guid:" item-guid)} (tag-values event "i"))
             (try (nostr/verify-event? event) (catch Exception _ false)))
    (some bolt11-msat (tag-values event "bolt11"))))

(defn pick
  "Of the receipts that match, the one dated nearest the payment. A listener
   who boosts one episode twice in ten minutes gets each boost's own receipt
   as long as the two are further apart than either is from its payment."
  [events {:keys [settled-at] :as match}]
  (->> events
       (keep (fn [e] (when-let [msat (receipt-msat e match)]
                       {:msat msat :id (:id e) :created-at (:created-at e)})))
       (sort-by #(Math/abs (- (long (:created-at %)) (long settled-at))))
       first))

(defn- query-relay!
  "Every event one relay holds for `filter`, until its EOSE. Never throws."
  [url filter]
  (try
    (let [conn (relay/connect! url query-timeout-ms)]
      (try
        @(relay/send-json! conn ["REQ" "zap" filter])
        (let [deadline (+ (System/currentTimeMillis) query-timeout-ms)]
          (loop [acc []]
            (let [left (- deadline (System/currentTimeMillis))
                  msg (when (pos? left)
                        (relay/await-message conn #(and (vector? %) (#{"EVENT" "EOSE"} (first %))) left))]
              (if (= "EVENT" (first msg))
                (recur (conj acc (wire->event (nth msg 2 nil))))
                acc))))
        (finally (s/close! conn))))
    (catch Exception e
      (u/log ::zap-query-failed :relay url :error (ex-message e))
      [])))

(defn find-spent!
  "What the listener paid for boost `b`, per its app's zap receipt:
   {:msat :id}, or nil when the app publishes none, the boost names no
   sender or episode, or no relay holds a matching receipt."
  [b sender-npub settled-at]
  (let [{:keys [pubkey relays]} (app-for b)
        item-guid (some-> (:item-guid b) str str/trim not-empty)
        sender-hex (when sender-npub
                     (try (nostr/bytes->hex (nostr/decode-key sender-npub "npub"))
                          (catch Exception _ nil)))]
    (when (and pubkey item-guid sender-hex settled-at)
      (let [filter {"kinds" [9735] "authors" [pubkey] "#P" [sender-hex]
                    "since" (- (long settled-at) window-sec)
                    "until" (+ (long settled-at) window-sec)}
            events (->> relays
                        (mapv #(future (query-relay! % filter)))
                        (mapcat deref))]
        (pick events {:app-pubkey pubkey :sender-hex sender-hex
                      :item-guid item-guid :settled-at settled-at})))))
