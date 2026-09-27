(ns boostbox.forwardbackfill
  "One-off: send MSP's split payments since a date to MSP-2.0's ingest. Publishes
   nothing to Nostr and never reads or writes the deployed bot's state; MSP ignores
   what it already holds, so a re-run is always safe. See
   scripts/msp-forward-backfill.sh."
  (:require [boostbox.boostagram :as bg]
            [boostbox.boostbox :as bb]
            [boostbox.forward :as fwd]
            [boostbox.nostrbot :as bot]
            [boostbox.nwc :as nwc]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:gen-class))

(defn backfill!
  "Read every transaction since `from` (unix seconds), keep MSP's forwardable
   payments, and send them in batches. A failed batch is counted and the rest
   still go. Returns {:read :forwardable :sent :failed}."
  [ctx session from]
  (let [txs (bot/fetch-transactions! session from)
        ;; nothing is published, so reading is decided by what MSP wants alone
        read-ctx (assoc ctx :actions (:forward-actions ctx))
        boosts (->> txs
                    (map #(bot/tx->boost! read-ctx %))
                    (filter #(and (:boostagram %)
                                  (bg/boost? (:boostagram %) (:forward-actions ctx)))))
        outcomes (mapv (fn [batch] [(count batch) (:ok? (fwd/send! ctx batch))])
                       (partition-all fwd/batch-size (map fwd/->record boosts)))]
    {:read (count txs)
     :forwardable (count boosts)
     :sent (reduce + 0 (map first (filter second outcomes)))
     :failed (reduce + 0 (map first (remove second outcomes)))}))

(defn -main
  "java -cp boostbox.jar boostbox.forwardbackfill <from-unix-seconds>
   Reads BBN_NWC_URI, BBN_FORWARD_URL, BBN_FORWARD_TOKEN, BBN_RECIPIENT_NAMES and
   BBN_FORWARD_ACTIONS from the environment."
  [& [from]]
  (let [stop (u/start-publisher! {:type :console})
        cfg (merge (fwd/env-config bb/get-env)
                   {:nwc (nwc/parse-uri (bb/get-env "BBN_NWC_URI"))
                    :recipient-names (into #{} (comp (map str/trim) (remove str/blank?) (map str/lower-case))
                                           (str/split (bb/get-env "BBN_RECIPIENT_NAMES" "") #","))
                    :boost-link-origins nil})]
    (when-not (fwd/enabled? cfg)
      (binding [*out* *err*]
        (println "BBN_FORWARD_URL, BBN_FORWARD_TOKEN and BBN_RECIPIENT_NAMES are all required"))
      (System/exit 2))
    (let [session (nwc/open! (:nwc cfg))]
      (try
        (println (backfill! cfg session (Long/parseLong (str from))))
        (finally
          (nwc/close! session)
          (Thread/sleep 250)
          (stop))))
    (System/exit 0)))
