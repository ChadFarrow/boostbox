(ns boostbox.forwardbackfill-test
  (:require [clojure.test :refer [deftest is]]
            [boostbox.boostagram :as bg]
            [boostbox.forward :as fwd]
            [boostbox.forwardbackfill :as bf]
            [boostbox.nostrbot :as bot]
            [boostbox.relay :as relay]))

(defn- result [hash action]
  {:payment-hash hash :settled-at 1790000000 :received-msat 1000
   :boostagram (bg/normalize {"action" action "name" "MSP 2.0"})})

(def ^:private ctx {:forward-actions #{"boost" "auto" "stream"} :recipient-names #{"msp 2.0"}})

(deftest backfill-sends-boosts-and-streams-in-batches-and-publishes-nothing
  (let [batches (atom [])
        txs (vec (range 30))
        results (into {} (map (fn [i] [i (result (str "h" i) (if (even? i) "stream" "auto"))]) txs))]
    (with-redefs [bot/fetch-transactions! (fn [_ _] txs)
                  bot/tx->boost! (fn [_ tx] (results tx))
                  relay/publish-to-relays! (fn [& _] (throw (ex-info "the backfill must not publish" {})))
                  fwd/send! (fn [_ batch] (swap! batches conj (count batch)) {:ok? true :status 200})]
      (is (= {:read 30 :forwardable 30 :sent 30 :failed 0} (bf/backfill! ctx ::session 1769721533)))
      (is (= [25 5] @batches)))))

(deftest backfill-sends-only-what-is-forwardable
  (with-redefs [bot/fetch-transactions! (fn [_ _] [1 2 3])
                bot/tx->boost! (fn [_ tx] (case tx
                                            1 (result "a" "boost")
                                            2 {:skip :other-recipient :payment-hash "b"}
                                            3 (result "c" "invoice")))
                fwd/send! (fn [_ batch] {:ok? (= ["a"] (mapv #(get % "payment_hash") batch)) :status 200})]
    (is (= {:read 3 :forwardable 1 :sent 1 :failed 0} (bf/backfill! ctx ::session 0)))))

(deftest backfill-counts-a-failed-batch-and-carries-on
  (let [calls (atom 0)]
    (with-redefs [bot/fetch-transactions! (fn [_ _] (vec (range 30)))
                  bot/tx->boost! (fn [_ tx] (result (str "h" tx) "boost"))
                  fwd/send! (fn [_ _] {:ok? (= 2 (swap! calls inc)) :status 200})]
      (is (= {:read 30 :forwardable 30 :sent 5 :failed 25} (bf/backfill! ctx ::session 0))))))
