(ns boostbox.nostrbot-test
  (:require [clojure.test :refer [deftest testing is]]
            [boostbox.boostagram :as bg]
            [boostbox.nostrbot :as bot]
            [boostbox.nostr :as nostr]
            [boostbox.nwc :as nwc]
            [boostbox.relay :as relay]
            [jsonista.core :as json]))

(def seckey (nostr/hex->bytes (apply str (repeat 64 "9"))))

(defn- mem-state-io
  "An in-memory stand-in for the FS/S3 state store."
  [a]
  {:read #(deref a) :write #(reset! a %)})

(defn- ctx [a & {:as overrides}]
  (merge {:state-io (mem-state-io a)
          :seckey seckey
          :relays ["wss://relay.example"]
          :dry-run? false
          :min-sats 0
          :boostbox-url "https://tardbox.com"
          :boostbox-api-key "test-key"
          :boost-link-origins ["https://tardbox.com"]}
         overrides))

(defn- boost [hash settled-at]
  {:payment-hash hash
   :settled-at settled-at
   :received-msat 21000
   :boostagram (bg/normalize
                {"action" "boost"
                 "podcast" "Podcasting 2.0"
                 "guid" "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"
                 "message" "hi"
                 "value_msat_total" 2100000})})

;; ~~~~~~~~~~~~~~~~~~~ State bookkeeping ~~~~~~~~~~~~~~~~~~~

(deftest remembering-is-bounded-and-deduplicated
  (let [remember #'bot/remember
        seen-index #'bot/seen-index]
    (testing "re-remembering a hash replaces rather than duplicates it"
      (let [s (-> {"recent" []}
                  (remember {"payment_hash" "a" "boost_id" "1"})
                  (remember {"payment_hash" "a" "boost_id" "1" "event_id" "e"}))]
        (is (= 1 (count (get s "recent"))))
        (is (= "e" (get (get (seen-index s) "a") "event_id")))))
    (testing "the recent list is capped so state cannot grow without bound"
      (let [s (reduce (fn [s i] (remember s {"payment_hash" (str i)}))
                      {"recent" []}
                      (range (+ 50 bot/max-recent)))]
        (is (= bot/max-recent (count (get s "recent"))))
        (is (nil? (get (seen-index s) "0")) "oldest entries are dropped")
        (is (some? (get (seen-index s) (str (dec (+ 50 bot/max-recent)))))
            "newest are kept")))))

;; ~~~~~~~~~~~~~~~~~~~ The poll loop ~~~~~~~~~~~~~~~~~~~

(deftest poll-publishes-and-advances-the-cursor
  (let [a (atom {"cursor" nil "recent" []})
        posted (atom [])
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1 ::tx2])
                  nwc/transaction->boost {::tx1 (boost "h1" 100) ::tx2 (boost "h2" 200)}
                  bot/store-boost! (fn [_ payload]
                                     (swap! posted conj payload)
                                     {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ event]
                                             (swap! published conj event)
                                             {:ok? true :results []})]
      (bot/poll-once! (ctx a) ::session)
      (is (= 2 (count @posted)) "both boosts stored in BoostBox")
      (is (= 2 (count @published)) "both notes published")
      (is (= 200 (get @a "cursor")) "cursor advances to the newest processed boost")
      (testing "the published note is a valid, tagged kind:1 event"
        (let [e (first @published)]
          (is (= 1 (:kind e)))
          (is (nostr/verify-event? e))
          (is (some #(= ["k" "podcast:guid"] %) (:tags e)))
          (is (some #(= ["r" "https://tardbox.com/boost/01K9"] %) (:tags e))))))))

(deftest a-failed-publish-holds-the-cursor-back
  (let [a (atom {"cursor" 50 "recent" []})
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1 ::tx2])
                  nwc/transaction->boost {::tx1 (boost "h1" 100) ::tx2 (boost "h2" 200)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _]
                                             (swap! published conj :attempt)
                                             {:ok? false :results [{:ok? false}]})]
      (bot/poll-once! (ctx a) ::session)
      (is (= 50 (get @a "cursor"))
          "cursor must not advance past a boost that was never published")
      (is (= 1 (count @published))
          "processing stops at the first failure instead of racing ahead")
      (testing "the BoostBox record is remembered so a retry does not create a second one"
        (let [entry (first (get @a "recent"))]
          (is (= "01K9" (get entry "boost_id")))
          (is (nil? (get entry "event_id"))))))))

(deftest a-retry-reuses-the-existing-boostbox-record
  (let [a (atom {"cursor" 50
                 "recent" [{"payment_hash" "h1"
                            "boost_id" "01K9"
                            "url" "https://tardbox.com/boost/01K9"}]})
        posted (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ p] (swap! posted conj p) {:id "NEW" :url "new"})
                  relay/publish-to-relays! (fn [_ _] {:ok? true :results []})]
      (bot/poll-once! (ctx a) ::session)
      (is (empty? @posted) "no second POST /boost for a payment already stored")
      (is (= 100 (get @a "cursor"))))))

(deftest an-already-published-boost-is-skipped
  (let [a (atom {"cursor" 50
                 "recent" [{"payment_hash" "h1" "boost_id" "01K9"
                            "url" "u" "event_id" "abc"}]})
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] (throw (AssertionError. "must not store again")))
                  relay/publish-to-relays! (fn [_ _] (swap! published conj :x) {:ok? true})]
      (bot/poll-once! (ctx a) ::session)
      (is (empty? @published) "de-duplicated by payment hash"))))

(deftest dry-run-publishes-nothing
  (let [a (atom {"cursor" nil "recent" []})
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "u"})
                  relay/publish-to-relays! (fn [_ _] (swap! published conj :x) {:ok? true})]
      (bot/poll-once! (ctx a :dry-run? true) ::session)
      (is (empty? @published) "dry run must not touch the relays"))))

(deftest below-threshold-boosts-are-skipped-but-remembered
  (let [a (atom {"cursor" nil "recent" []})
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "u"})
                  relay/publish-to-relays! (fn [_ _] (swap! published conj :x) {:ok? true})]
      ;; the boost is 2100 sats
      (bot/poll-once! (ctx a :min-sats 5000) ::session)
      (is (empty? @published))
      (is (= "below-threshold" (get (first (get @a "recent")) "skipped"))
          "remembered so it is not re-examined every poll"))))

;; ~~~~~~~~~~~~~~~~~~~ Profile ~~~~~~~~~~~~~~~~~~~

(deftest relay-list-is-nip65-kind-10002
  (with-redefs [relay/publish-to-relays! (fn [_ _] {:ok? true :results []})]
    (let [e (bot/publish-relay-list! {:seckey seckey
                                      :relays ["wss://relay.damus.io" "wss://nos.lol"]
                                      :dry-run? false})]
      (is (= 10002 (:kind e)))
      (is (= "" (:content e)) "NIP-65 carries everything in tags")
      (is (nostr/verify-event? e))
      (testing "each relay is declared write-only, which is what the bot does"
        (is (= [["r" "wss://relay.damus.io" "write"]
                ["r" "wss://nos.lol" "write"]]
               (:tags e))))
      (testing "the wallet relay is never in the list -- it is a credential"
        (is (not (some #(clojure.string/includes? (str %) "getalby") (:tags e))))))))

(deftest profile-event-is-kind-0-json-content
  (with-redefs [relay/publish-to-relays! (fn [_ _] {:ok? true :results []})]
    (let [e (bot/publish-profile!
             {:seckey seckey
              :relays ["wss://relay.example"]
              :dry-run? false
              :profile {:name "boostbot" :display_name "BoostBot"
                        :about nil :lud16 "boostbot@getalby.com"}})]
      (is (= 0 (:kind e)))
      (is (nostr/verify-event? e))
      (testing "content is a JSON string, with blank fields omitted"
        (is (= "{\"name\":\"boostbot\",\"display_name\":\"BoostBot\",\"lud16\":\"boostbot@getalby.com\"}"
               (:content e)))))))

;; ~~~~~~~~~~~~~~~~~~~ First-run safety ~~~~~~~~~~~~~~~~~~~

(deftest first-run-sets-a-watermark-instead-of-replaying-history
  (let [a (atom {"cursor" nil "recent" []})
        asked (atom nil)
        now (quot (System/currentTimeMillis) 1000)]
    (with-redefs [nwc/list-transactions! (fn [_ opts] (reset! asked opts) [])
                  bot/store-boost! (fn [_ _] (throw (AssertionError. "should not run")))
                  relay/publish-to-relays! (fn [_ _] (throw (AssertionError. "should not run")))]
      (bot/poll-once! (ctx a) ::session)
      (is (>= (:from @asked) now)
          "the first poll starts from now, not from the beginning of wallet history")
      (is (some? (get @a "cursor")) "the watermark is persisted immediately")))

  (testing "BBN_BACKFILL_SEC deliberately reaches back"
    (let [a (atom {"cursor" nil "recent" []})
          asked (atom nil)
          now (quot (System/currentTimeMillis) 1000)]
      (with-redefs [nwc/list-transactions! (fn [_ opts] (reset! asked opts) [])]
        (bot/poll-once! (ctx a :backfill-sec 3600) ::session)
        (is (<= (:from @asked) (- now 3599)))))))

;; ~~~~~~~~~~~~~~~~~~~ Regressions ~~~~~~~~~~~~~~~~~~~

(deftest a-published-note-is-not-republished-after-a-later-boost-fails
  (let [a (atom {"cursor" 50 "recent" []})
        published (atom [])
        stores (atom 0)]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1 ::tx2])
                  nwc/transaction->boost {::tx1 (boost "h1" 100) ::tx2 (boost "h2" 200)}
                  ;; h1 stores fine; BoostBox is down by the time h2 is tried,
                  ;; so h2 throws *before* it can persist anything
                  bot/store-boost! (fn [_ _]
                                     (when (pos? @stores)
                                       (throw (ex-info "BoostBox is down" {})))
                                     (swap! stores inc)
                                     {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ e]
                                             (swap! published conj (:id e))
                                             {:ok? true :results []})]
      (bot/poll-once! (ctx a) ::session)
      (is (= 1 (count @published)) "h1 published, h2 never got as far as a note")
      (is (some? (get (first (get @a "recent")) "event_id"))
          "h1's event_id is persisted, not just returned in memory")

      (testing "the next poll must not mint a second note for h1"
        (bot/poll-once! (ctx a) ::session)
        (is (= 1 (count @published))
            "h1 is recognised as already published rather than republished")))))

(deftest a-full-transaction-page-is-followed-by-the-next
  (let [a (atom {"cursor" 50 "recent" []})
        offsets (atom [])]
    (with-redefs [nwc/list-transactions!
                  (fn [_ {:keys [offset limit]}]
                    (swap! offsets conj offset)
                    (if (zero? offset)
                      (vec (repeat limit {"settled_at" 100}))
                      [{"settled_at" 300}]))
                  nwc/transaction->boost (constantly nil)]
      (bot/poll-once! (ctx a) ::session)
      (is (= [0 50] @offsets)
          "a page that came back full may have older transactions behind it")
      (is (= 300 (get @a "cursor"))
          "a window of ordinary payments still advances the cursor, or it would
           pin forever and the paging walk would grow on every poll"))))

(deftest min-sats-is-measured-against-what-actually-arrived
  (let [a (atom {"cursor" nil "recent" []})
        published (atom [])
        b (-> (boost "h1" 100)
              (update :boostagram dissoc :value-msat-total)
              (assoc :received-msat 50000000))]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 b}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "u"})
                  relay/publish-to-relays! (fn [_ _]
                                             (swap! published conj :x)
                                             {:ok? true :results []})]
      (bot/poll-once! (ctx a :min-sats 1000) ::session)
      (is (= 1 (count @published))
          "a 50,000 sat boost whose TLV omits value_msat_total is not below a
           1,000 sat threshold"))))

;; ~~~~~~~~~~~~~~~~~~~ Boost links (LNURL payments) ~~~~~~~~~~~~~~~~~~~

(def link-tx
  {"payment_hash" "hL" "amount" 10000 "settled_at" 300
   "description" "rss::payment::boost https://tardbox.com/boost/01LINKED hello"})

(def linked-metadata
  {"action" "boost" "feed_title" "LNURL Testing Podcast"
   "item_title" "Episode 3" "feed_guid" "9fe51a32-e08d-5ab7-9540-22a25c6bc2bf"
   "item_guid" "c4dac22d-173f-4442-9b3b-5d89b20b26e6"
   "sender_name" "ChadF" "message" "hi" "value_msat_total" 100000})

(deftest a-boost-link-supplies-the-metadata-an-lnurl-payment-cannot-carry
  (with-redefs [bot/fetch-boost-metadata! (fn [_] linked-metadata)]
    (let [b (bot/tx->boost! (ctx (atom {})) link-tx)]
      (is (some? b) "an LNURL payment with no TLV is still publishable")
      (is (= "https://tardbox.com/boost/01LINKED" (:boost-url b)))
      (is (= "01LINKED" (:boost-id b)))
      (is (= "9fe51a32-e08d-5ab7-9540-22a25c6bc2bf" (-> b :boostagram :feed-guid))
          "so the note can still carry NIP-73 tags"))))

(deftest a-linked-boost-is-never-stored-twice
  (let [a (atom {"cursor" 50 "recent" []})
        posted (atom [])
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [link-tx])
                  bot/fetch-boost-metadata! (fn [_] linked-metadata)
                  bot/store-boost! (fn [_ p] (swap! posted conj p) {:id "NEW" :url "NEW"})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})]
      (bot/poll-once! (ctx a) ::session)
      (is (empty? @posted)
          "the record already exists at the linked URL; POSTing would mint a duplicate")
      (is (= 1 (count @published)))
      (testing "and the note points at the existing record, not a new one"
        (is (some #(= ["r" "https://tardbox.com/boost/01LINKED"] %) (:tags (first @published)))))
      (is (= "01LINKED" (get (first (get @a "recent")) "boost_id"))))))

(deftest an-unlisted-origin-is-not-fetched-when-origins-are-named
  (let [fetched (atom [])]
    (with-redefs [bot/fetch-boost-metadata! (fn [u] (swap! fetched conj u) nil)]
      (is (nil? (bot/tx->boost! (ctx (atom {}))
                                {"payment_hash" "x" "amount" 1000
                                 "description" "rss::payment::boost https://evil.example/y hi"})))
      (is (empty? @fetched)
          "the fixture names tardbox explicitly, so nothing else is requested"))))

(deftest with-no-origins-named-another-boostbox-still-works
  (with-redefs [bot/fetch-boost-metadata! (fn [_] linked-metadata)]
    (let [c (assoc (ctx (atom {})) :boost-link-origins nil)
          b (bot/tx->boost! c {"payment_hash" "y" "amount" 10000 "settled_at" 5
                               "description" "rss::payment::boost https://boostbox.someapp.com/boost/01Z hi"})]
      (is (some? b) "a podcaster cannot enumerate every app's BoostBox in advance")
      (is (= "https://boostbox.someapp.com/boost/01Z" (:boost-url b))))))

(deftest the-address-behind-a-link-is-what-is-actually-checked
  (testing "public names resolve and are allowed"
    (is (bot/fetchable-url? "https://tardbox.com/boost/01ABC")))
  (testing "anything that resolves into private space is refused, however it is spelled"
    (doseq [u ["https://localhost/x"
               "https://127.0.0.1/x"
               "https://169.254.169.254/latest/meta-data/"
               "https://10.0.0.5/x"
               "https://192.168.1.1/x"
               "https://172.16.0.1/x"
               "https://[::1]/x"
               "https://0.0.0.0/x"]]
      (is (not (bot/fetchable-url? u)) u)))
  (testing "plaintext and unresolvable hosts are refused"
    (is (not (bot/fetchable-url? "http://tardbox.com/x")))
    (is (not (bot/fetchable-url? "https://no-such-host.invalid/x")))
    (is (not (bot/fetchable-url? "not-a-url")))))

(deftest a-refused-address-is-never-contacted
  (testing "the check and the connection use one resolution, so there is no
            window for DNS to answer differently the second time"
    (doseq [u ["https://169.254.169.254/latest/meta-data/"
               "https://127.0.0.1/x"
               "https://10.0.0.5/x"
               "http://tardbox.com/boost/01ABC"
               "https://no-such-host.invalid/x"]]
      (is (nil? (bot/fetch-boost-metadata! u)) u))))

(deftest a-tlv-boostagram-still-wins-and-costs-no-round-trip
  (let [fetched (atom 0)
        tlv (nostr/bytes->hex (.getBytes (json/write-value-as-string
                                          {"action" "boost" "podcast" "From TLV"
                                           "guid" "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"
                                           "value_msat_total" 2100000})
                                         "UTF-8"))]
    (with-redefs [bot/fetch-boost-metadata! (fn [_] (swap! fetched inc) linked-metadata)]
      (let [b (bot/tx->boost! (ctx (atom {}))
                              {"payment_hash" "hT" "amount" 21000 "settled_at" 1
                               "description" "rss::payment::boost https://tardbox.com/boost/01LINKED hi"
                               "metadata" {"tlv_records" [{"type" 7629169 "value" tlv}]}})]
        (is (= "From TLV" (-> b :boostagram :podcast)))
        (is (nil? (:boost-url b)) "so it is stored normally, as before")
        (is (zero? @fetched) "no network round trip when the TLV is right there")))))
