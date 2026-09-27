(ns boostbox.forward-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.http-client :as http]
            [boostbox.boostagram :as bg]
            [boostbox.forward :as fwd]
            [jsonista.core :as json]))

(def tlv-json
  (json/write-value-as-string {"action" "auto" "name" "MSP 2.0" "app_name" "v4vmusic-com"
                               "boost_link" "https://v4vmusic.com/songs/x"
                               "value_msat_total" 100000}))

(defn- boost [hash & {:as extra}]
  (merge {:payment-hash hash :received-msat 1000 :settled-at 1790000000
          :boostagram (bg/normalize (json/read-value tlv-json))
          :tlv-json tlv-json}
         extra))

;; ~~~~~~~~~~~~~~~~~~~ Records ~~~~~~~~~~~~~~~~~~~

(deftest a-keysend-record-carries-its-tlv-untouched
  (is (= {"source" "boostbox" "payment_hash" "h1" "direction" "incoming"
          "time" 1790000000 "value_msat" 1000 "tlv" tlv-json}
         (fwd/->record (boost "h1")))))

(deftest a-linked-record-never-carries-the-permalink-as-boost-link
  (let [linked {"action" "boost" "recipient_name" "MSP 2.0" "feed_guid" "fg" "item_guid" "ig"
                "feed_title" "Show" "item_title" "Ep" "position" 42 "group" "u1"
                "boost_link" "https://tardbox.com/boost/01X" "message" "hi"}
        tlv (json/read-value (get (fwd/->record (boost "h2" :tlv-json nil :link-metadata linked)) "tlv"))]
    (is (= {"action" "boost" "name" "MSP 2.0" "guid" "fg" "episode_guid" "ig" "podcast" "Show"
            "episode" "Ep" "ts" 42 "uuid" "u1" "message" "hi"}
           tlv))))

(deftest blip10-names-win-over-boostbox-names
  (is (= "blip" (get (fwd/link-metadata->tlv {"guid" "blip" "feed_guid" "bb"}) "guid"))))

(deftest a-wallet-parsed-record-sends-the-wallets-map
  (let [wallet {"action" "boost" "name" "MSP 2.0" "podcast" "Show"}]
    (is (= wallet (json/read-value (get (fwd/->record (boost "h3" :tlv-json nil :wallet-boostagram wallet)) "tlv"))))))

(deftest a-record-with-no-source-data-is-rebuilt-from-the-boostagram
  (let [tlv (json/read-value (get (fwd/->record (boost "h4" :tlv-json nil)) "tlv"))]
    (is (= "MSP 2.0" (get tlv "name")))
    (is (= "auto" (get tlv "action")))
    (is (= 100000 (get tlv "value_msat_total")))))

;; ~~~~~~~~~~~~~~~~~~~ Config ~~~~~~~~~~~~~~~~~~~

(deftest forward-config-defaults-to-all-three-actions
  (let [env {"BBN_FORWARD_URL" " https://msp.example/api/boosts/ingest " "BBN_FORWARD_TOKEN" "tok"}
        cfg (fwd/env-config (fn [k d] (get env k d)))]
    (is (= "https://msp.example/api/boosts/ingest" (:forward-url cfg)))
    (is (= "tok" (:forward-token cfg)))
    (is (= #{"boost" "auto" "stream"} (:forward-actions cfg)))))

(deftest forwarding-needs-a-url-a-token-and-a-recipient-filter
  (is (fwd/enabled? {:forward-url "u" :forward-token "t" :recipient-names #{"msp 2.0"}}))
  (is (not (fwd/enabled? {:forward-url "u" :forward-token "t" :recipient-names #{}}))
      "without a filter every split on the node would go to MSP")
  (is (not (fwd/enabled? {:forward-url "u" :recipient-names #{"msp 2.0"}}))))

;; ~~~~~~~~~~~~~~~~~~~ Sending ~~~~~~~~~~~~~~~~~~~

(deftest send-posts-a-json-array-with-the-bearer-token
  (let [seen (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! seen [url opts]) {:status 200})]
      (is (:ok? (fwd/send! {:forward-url "https://msp.example/i" :forward-token "tok"} [{"a" 1}])))
      (let [[url opts] @seen]
        (is (= "https://msp.example/i" url))
        (is (= "Bearer tok" (get-in opts [:headers "authorization"])))
        (is (= [{"a" 1}] (json/read-value (:body opts))))))
    (with-redefs [http/post (fn [_ _] {:status 201})]
      (is (not (:ok? (fwd/send! {:forward-url "u" :forward-token "t"} [{}]))) "only exactly 200 counts"))
    (with-redefs [http/post (fn [_ _] (throw (ex-info "connection refused" {})))]
      (is (not (:ok? (fwd/send! {:forward-url "u" :forward-token "t"} [{}])))))))

(deftest forward-sends-new-boosts-and-remembers-them
  (let [sent (atom [])]
    (with-redefs [fwd/send! (fn [_ batch] (swap! sent conj (mapv #(get % "payment_hash") batch)) {:ok? true :status 200})]
      (let [{:keys [state] :as r} (fwd/forward! {} {} [(boost "a") (boost "b")])]
        (is (= 2 (:sent r)))
        (is (= [["a" "b"]] @sent))
        (is (fwd/forwarded? state "a"))
        (testing "a second pass sends nothing"
          (reset! sent [])
          (fwd/forward! {} state [(boost "a")])
          (is (empty? @sent)))))))

(deftest a-failed-send-is-queued-and-retried-oldest-first
  (let [ok? (atom false)
        sent (atom [])]
    (with-redefs [fwd/send! (fn [_ batch]
                              (swap! sent conj (mapv #(get % "payment_hash") batch))
                              {:ok? @ok? :status (if @ok? 200 503)})]
      (let [{s1 :state :as r1} (fwd/forward! {} {} [(boost "old")])]
        (is (= 1 (:queued r1)))
        (is (not (fwd/forwarded? s1 "old")))
        (reset! ok? true)
        (reset! sent [])
        (let [{s2 :state} (fwd/forward! {} s1 [(boost "new")])]
          (is (= [["old" "new"]] @sent) "the queue goes first, in the same batch")
          (is (empty? (get s2 "forward-pending")))
          (is (every? #(fwd/forwarded? s2 %) ["old" "new"])))))))

(deftest a-queued-boost-is-not-sent-twice-in-one-pass
  (let [sent (atom [])]
    (with-redefs [fwd/send! (fn [_ batch] (swap! sent into (map #(get % "payment_hash") batch)) {:ok? true :status 200})]
      (fwd/forward! {} {"forward-pending" [(fwd/->record (boost "q"))]} [(boost "q")])
      (is (= ["q"] @sent)))))

(deftest sends-in-batches-and-stops-at-the-first-failure
  (let [sizes (atom [])]
    (with-redefs [fwd/send! (fn [_ batch] (swap! sizes conj (count batch)) {:ok? (= 1 (count @sizes)) :status 200})]
      (let [r (fwd/forward! {} {} (map #(boost (str "b" %)) (range 60)))]
        (is (= [25 25] @sizes) "the second batch failed, so the third was not tried")
        (is (= 25 (:sent r)))
        (is (= 35 (:queued r)))))))

(deftest the-queue-is-bounded-and-drops-the-oldest
  (with-redefs [fwd/send! (fn [_ _] {:ok? false :status 503})]
    (let [{:keys [state dropped]} (fwd/forward! {} {} (map #(boost (str "h" %)) (range (+ 10 fwd/max-pending))))]
      (is (= 10 dropped))
      (is (= fwd/max-pending (count (get state "forward-pending"))))
      (is (= "h10" (get (first (get state "forward-pending")) "payment_hash"))))))
