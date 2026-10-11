(ns boostbox.zaps-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [jsonista.core :as json]
            [boostbox.boostagram :as bg]
            [boostbox.nostr :as nostr]
            [boostbox.nostrbot :as bot]
            [boostbox.zaps :as zaps]))

;; Two receipts Fountain published on 2026-10-11 for Matt Finlay's boosts,
;; unmodified: 123 sats for a boost whose value_msat_total was 101, and 321
;; for one whose total was 300.
(def receipts
  (mapv #'zaps/wire->event
        (json/read-value (slurp (io/resource "fountain-zap-receipts.json")))))

(def matt-npub "npub12znrejs4k94kp5efhyhk9rzr9jxpy6ymgrlf6py4awpkkhed7cmshs6yg6")
(def matt-hex "50a63cca15b16b60d329b92f628c432c8c12689b40fe9d0495eb836b5f2df637")
(def fountain (get-in zaps/apps ["fountain" :pubkey]))

(defn- match [item-guid settled-at]
  {:app-pubkey fountain :sender-hex matt-hex :item-guid item-guid :settled-at settled-at})

(deftest bolt11-amounts
  (is (= 321000 (zaps/bolt11-msat "lnbc3210n1p4vk770pp5k2l3n8")))
  (is (= 123000 (zaps/bolt11-msat "LNBC1230N1P4VKLYV")))
  (is (= 2500000000 (zaps/bolt11-msat "lnbc25m1pvjluez")))
  (is (= 100000 (zaps/bolt11-msat "lnbc1u1abc")))
  (is (= 1 (zaps/bolt11-msat "lnbc10p1abc")))
  (is (nil? (zaps/bolt11-msat "lnbc15p1abc")) "a fraction of a msat is not an amount")
  (is (nil? (zaps/bolt11-msat "lnbc1pvjluez")) "an invoice with no amount")
  (is (nil? (zaps/bolt11-msat "lnurl1dp68gurn8ghj7")))
  (is (nil? (zaps/bolt11-msat nil))))

(deftest a-fountain-receipt-names-what-was-spent
  (testing "each boost finds its own receipt by episode"
    (is (= 123000 (:msat (zaps/pick receipts (match "cf7f42c0-79fd-4874-a69a-fe7f68daed92" 1791720590)))))
    (is (= 321000 (:msat (zaps/pick receipts (match "a91e090d-df36-4195-a8df-8dc6a84536f4" 1791720403))))))
  (testing "another listener's or another episode's receipt does not count"
    (is (nil? (zaps/pick receipts (assoc (match "cf7f42c0-79fd-4874-a69a-fe7f68daed92" 1791720590)
                                         :sender-hex (apply str (repeat 64 "a"))))))
    (is (nil? (zaps/pick receipts (match "not-the-episode" 1791720590)))))
  (testing "only the app's own, correctly signed receipts count"
    (let [e (first receipts)]
      (is (nil? (zaps/receipt-msat (assoc e :pubkey (apply str (repeat 64 "b")))
                                   (match "cf7f42c0-79fd-4874-a69a-fe7f68daed92" 0))))
      (is (nil? (zaps/receipt-msat (update e :tags conj ["bolt11" "lnbc99990n1x"])
                                   (match "cf7f42c0-79fd-4874-a69a-fe7f68daed92" 0)))
          "an edited receipt fails its signature")
      (let [forged (nostr/sign-event (nostr/hex->bytes (apply str (repeat 64 "9")))
                                     {:kind 9735 :created-at (:created-at e) :tags (:tags e)})]
        (is (nil? (zaps/receipt-msat forged (match "cf7f42c0-79fd-4874-a69a-fe7f68daed92" 0)))
            "a receipt signed by anyone else")))))

(deftest only-apps-in-the-table-are-looked-up
  (is (some? (zaps/app-for {:app-name " Fountain "})))
  (is (nil? (zaps/app-for {:app-name "BoostMeBitch"})))
  (is (nil? (zaps/app-for {}))))

(def flood-gates
  (bg/normalize {"action" "boost" "app_name" "Fountain"
                 "sender_npub" matt-npub
                 "item_guid" "cf7f42c0-79fd-4874-a69a-fe7f68daed92"
                 "value_msat" 1000 "value_msat_total" 101000}))

(deftest the-note-says-what-the-listener-spent
  (let [opts {:received-msat 1000 :settled-at 1791720590 :payment-hash "h"}
        ctx {:app-zap-lookup? true}]
    (testing "a receipt raises the total"
      (with-redefs [zaps/find-spent! (fn [_ _ _] {:msat 123000 :id "d755"})]
        (is (= 123000 (:value-msat-total (bot/with-spent-total ctx flood-gates opts))))))
    (testing "the lookup reads the boost's own sender and payment time"
      (let [args (atom nil)]
        (with-redefs [zaps/find-spent! (fn [& xs] (reset! args xs) nil)]
          (bot/with-spent-total ctx flood-gates opts)
          (is (= [flood-gates matt-npub 1791720590] @args)))))
    (testing "a receipt for less than the splits were paid is not this boost's"
      (with-redefs [zaps/find-spent! (fn [_ _ _] {:msat 50000 :id "x"})]
        (is (= 101000 (:value-msat-total (bot/with-spent-total ctx flood-gates opts))))))
    (testing "no receipt, or a failed lookup, leaves the boost as it was"
      (with-redefs [zaps/find-spent! (fn [_ _ _] nil)]
        (is (= flood-gates (bot/with-spent-total ctx flood-gates opts))))
      (with-redefs [zaps/find-spent! (fn [_ _ _] (throw (Exception. "relay down")))]
        (is (= flood-gates (bot/with-spent-total ctx flood-gates opts)))))
    (testing "switched off, or another app, nothing is looked up"
      (with-redefs [zaps/find-spent! (fn [_ _ _] (throw (AssertionError. "should not run")))]
        (is (= flood-gates (bot/with-spent-total {} flood-gates opts)))
        (is (= (assoc flood-gates :app-name "Podverse")
               (bot/with-spent-total ctx (assoc flood-gates :app-name "Podverse") opts)))))))
