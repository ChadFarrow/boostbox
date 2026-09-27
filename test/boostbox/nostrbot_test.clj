(ns boostbox.nostrbot-test
  (:require [clojure.test :refer [deftest testing is]]
            [boostbox.boostagram :as bg]
            [boostbox.forward :as fwd]
            [boostbox.nostrbot :as bot]
            [boostbox.podcastindex]
            [boostbox.nostr :as nostr]
            [boostbox.nwc :as nwc]
            [boostbox.relay :as relay]
            [com.brunobonacci.mulog.core :as mulog-core]
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

;; A backfill publishes months of boosts in one sitting. Stamped "now", 289 of
;; them landed in followers' feeds at once; stamped when they were paid, they
;; slot into the bot's history in order.
(deftest a-note-carries-the-time-the-boost-was-paid
  (let [b (:boostagram (boost "h1" 0))
        now (quot (System/currentTimeMillis) 1000)
        note (fn [opts] (bot/build-note (ctx (atom {})) b (merge {:boost-url "u" :received-msat 21000} opts)))]
    (testing "an old payment keeps its own time"
      (let [e (note {:settled-at 1770336000})]
        (is (= 1770336000 (:created-at e)))
        (is (nostr/verify-event? e) "the id and signature cover the backdated time")))
    (testing "no payment time means now"
      (is (<= now (:created-at (note {})) (+ now 5))))
    (testing "a wallet clock ahead of ours never dates a note in the future, which relays refuse"
      (is (<= (:created-at (note {:settled-at (+ now 86400)})) (+ now 5))))))

(deftest a-bot-with-its-own-name-signs-its-banner-with-it
  (let [b (bg/normalize {"action" "boost" "podcast" "Some Album" "value_msat_total" 21000})
        tags-of (fn [client-name]
                  (let [e (bot/build-note (ctx (atom {}) :client-name client-name :feed-lookup? false)
                                          b {:boost-url "u" :received-msat 21000})]
                    {:content (:content e)
                     :client (some #(when (= "client" (first %)) (second %)) (:tags e))}))]
    (let [{:keys [content client]} (tags-of "MSP 2.0")]
      (is (= "MSP 2.0" client))
      (is (clojure.string/includes? content "&by=MSP+2.0") "the picture names the account the tag names"))
    (is (not (clojure.string/includes? (:content (tags-of bg/default-client-name)) "by="))
        "the default bot's banner URL is unchanged")))

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
      (is (= [0 bot/transactions-page-size] @offsets)
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
      (is (some? (:boostagram b)) "an LNURL payment with no TLV is still publishable")
      (is (= "https://tardbox.com/boost/01LINKED" (:boost-url b)))
      (is (= "01LINKED" (:boost-id b)))
      (is (= "9fe51a32-e08d-5ab7-9540-22a25c6bc2bf" (-> b :boostagram :feed-guid))
          "so the note can still carry NIP-73 tags"))))

(deftest a-linked-boost-carries-the-metadata-the-link-returned
  (with-redefs [bot/fetch-boost-metadata! (fn [_] linked-metadata)]
    (is (= linked-metadata (:link-metadata (bot/tx->boost! (ctx (atom {})) link-tx))))))

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
      (is (nil? (:boostagram (bot/tx->boost! (ctx (atom {}))
                                             {"payment_hash" "x" "amount" 1000
                                              "description" "rss::payment::boost https://evil.example/y hi"}))))
      (is (empty? @fetched)
          "the fixture names tardbox explicitly, so nothing else is requested"))))

(deftest with-no-origins-named-another-boostbox-still-works
  (with-redefs [bot/fetch-boost-metadata! (fn [_] linked-metadata)]
    (let [c (assoc (ctx (atom {})) :boost-link-origins nil)
          b (bot/tx->boost! c {"payment_hash" "y" "amount" 10000 "settled_at" 5
                               "description" "rss::payment::boost https://boostbox.someapp.com/boost/01Z hi"})]
      (is (some? (:boostagram b)) "a podcaster cannot enumerate every app's BoostBox in advance")
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

(deftest a-transaction-that-publishes-nothing-still-says-why
  (testing "an ordinary payment is a skip carrying the hash the wallet shows"
    (let [r (bot/tx->boost! (ctx (atom {})) {"payment_hash" "hP" "amount" 1000})]
      (is (nil? (:boostagram r)))
      (is (= :no-boostagram (:skip r)))
      (is (= "hP" (:payment-hash r))
          "so a boost that went missing is findable in the logs by the same id")))

  (testing "a TLV that would not decode is a defect, and reads as one"
    (let [r (bot/tx->boost! (ctx (atom {}))
                            {"payment_hash" "hD" "amount" 1000
                             "metadata" {"tlv_records" [{"type" 7629169 "value" "zzzz"}]}})]
      (is (= :tlv-unreadable (:skip r)))
      (is (= "hD" (:payment-hash r)))))

  (testing "a boost link that yields nothing says so, rather than passing as an ordinary payment"
    (with-redefs [bot/fetch-boost-metadata! (fn [_] nil)]
      (let [r (bot/tx->boost! (ctx (atom {})) link-tx)]
        (is (= :boost-link-unreadable (:skip r))
            "the lightning-address path is the common one; this cannot be a silent count")
        (is (= "https://tardbox.com/boost/01LINKED" (:url r)))
        (is (= "hL" (:payment-hash r))))))

  (testing "a boost link to a stream is a stream"
    (with-redefs [bot/fetch-boost-metadata! (fn [_] (assoc linked-metadata "action" "stream"))]
      (let [r (bot/tx->boost! (ctx (atom {})) link-tx)]
        (is (= :not-a-boost (:skip r)))
        (is (= "stream" (:action r))))))

  (testing "an unreadable TLV keeps its own reason when a link beside it fails too"
    (with-redefs [bot/fetch-boost-metadata! (fn [_] nil)]
      (let [r (bot/tx->boost! (ctx (atom {}))
                              (assoc link-tx "metadata"
                                     {"tlv_records" [{"type" 7629169 "value" "zzzz"}]}))]
        (is (= :tlv-unreadable (:skip r)) "the defect is the TLV; the link is incidental"))))

  (testing "every reason tx->boost! can give is a declared one"
    (with-redefs [bot/fetch-boost-metadata! (fn [_] nil)]
      (doseq [tx [{"payment_hash" "a"}
                  link-tx
                  {"metadata" {"tlv_records" [{"type" 7629169 "value" "zzzz"}]}}
                  {"metadata" {"boostagram" {"action" "stream"}}}]]
        (is (contains? bot/skip-reasons (:skip (bot/tx->boost! (ctx (atom {})) tx)))
            (pr-str tx))))))

(deftest a-boost-link-honours-the-recipient-and-action-filters
  (let [msp (ctx (atom {}) :actions #{"boost" "auto"} :recipient-names #{"msp 2.0"})]
    (testing "a linked boost to another recipient is that recipient's"
      (with-redefs [bot/fetch-boost-metadata! (fn [_] linked-metadata)]
        (let [r (bot/tx->boost! msp link-tx)]
          (is (= :other-recipient (:skip r)))
          (is (= "hL" (:payment-hash r))))))
    (testing "a linked auto-boost on the named split publishes"
      (with-redefs [bot/fetch-boost-metadata!
                    (fn [_] (assoc linked-metadata "action" "auto" "name" "MSP 2.0"))]
        (is (some? (:boostagram (bot/tx->boost! msp link-tx))))))
    (testing "and the default bot still refuses it"
      (with-redefs [bot/fetch-boost-metadata!
                    (fn [_] (assoc linked-metadata "action" "auto" "name" "MSP 2.0"))]
        (is (= :not-a-boost (:skip (bot/tx->boost! (ctx (atom {})) link-tx))))))))

(deftest filter-env-vars-parse-to-case-folded-sets
  (let [folded #'bot/folded-set]
    (is (= #{"msp 2.0"} (folded "MSP 2.0")))
    (is (= #{"boost" "auto"} (folded " Boost , AUTO ")))
    (is (= #{} (folded "")))))

(deftest a-typo-in-the-album-list-stops-the-bot
  (let [guids #'bot/feed-guid-set]
    (is (= #{"c90e609a-df1e-596a-bd5e-57bcc8aad6cc" "917393e3-1b1e-5cef-ace4-edaa54e1f810"}
           (guids " C90E609A-DF1E-596A-BD5E-57BCC8AAD6CC , 917393e3-1b1e-5cef-ace4-edaa54e1f810")))
    (is (= #{} (guids "")) "no list means every album")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"c90e609a-df1e-596a-bd5e-57bcc8aad6c"
                          (guids "c90e609a-df1e-596a-bd5e-57bcc8aad6c"))
        "a guid one character short is refused, not silently matched against nothing")))

;; A wallet in many shows' splits gets a boostagram on nearly every payment,
;; about 2 KB each. relay.getalby.com passed a page of 20 (39 KB) and silently
;; dropped a page of 50 -- the request just timed out, on every poll.
(deftest transactions-are-read-in-pages-the-relay-will-carry
  (let [wallet (vec (for [i (range 25)] {"payment_hash" (str "h" i) "settled_at" (- 1000 i)}))
        limits (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ {:keys [limit offset]}]
                                           (swap! limits conj limit)
                                           (->> wallet (drop offset) (take limit) vec))]
      (is (= wallet (bot/fetch-transactions! ::session 0)) "paging still reaches everything")
      (is (every? #(<= % 10) @limits) (pr-str @limits)))))

(defn- paged-wallet
  "A fake list_transactions over `n` transactions, newest first."
  [n]
  (let [wallet (vec (for [i (range n)] {"payment_hash" (str "h" i) "settled_at" (- 100000 i)}))]
    [wallet (fn [_ {:keys [limit offset]}] (->> wallet (drop offset) (take limit) vec))]))

(def timed-out (ex-info "NWC request timed out" {:method "list_transactions" :timeout? true}))

;; relay.getalby.com stops answering after about sixty requests a minute. A
;; backfill walking hundreds of pages back to back hit it on the 61st, and a
;; poll that fails there restarts the same walk and hits it again, forever.
(deftest a-long-walk-is-paced-and-a-poll-is-not
  (let [pauses (atom [])]
    (testing "every page after the first waits its turn"
      (let [[wallet list-txs] (paged-wallet 25)]
        (with-redefs [nwc/list-transactions! list-txs
                      bot/pause! (fn [ms] (swap! pauses conj ms))]
          (is (= wallet (bot/fetch-transactions! ::session 0)))
          (is (= 2 (count @pauses)) "three pages, two gaps")
          (is (every? pos? @pauses)))))
    (testing "an ordinary poll reads one page and never waits"
      (reset! pauses [])
      (let [[_ list-txs] (paged-wallet 3)]
        (with-redefs [nwc/list-transactions! list-txs
                      bot/pause! (fn [ms] (swap! pauses conj ms))]
          (bot/fetch-transactions! ::session 0)
          (is (empty? @pauses)))))))

(deftest a-timed-out-page-is-waited-out-and-read-again
  (let [[wallet list-txs] (paged-wallet 25)
        calls (atom 0)
        pauses (atom [])]
    (with-redefs [nwc/list-transactions! (fn [s {:keys [offset] :as p}]
                                           (if (and (= 10 offset) (= 1 (swap! calls inc)))
                                             (throw timed-out)
                                             (list-txs s p)))
                  bot/pause! (fn [ms] (swap! pauses conj ms))]
      (is (= wallet (bot/fetch-transactions! ::session 0))
          "the walk finishes rather than failing the whole poll")
      (is (some #(>= % 30000) @pauses) "and it waited long enough for the limit to clear"))))

(deftest a-page-that-never-answers-still-fails-the-poll
  (let [attempts (atom 0)]
    (with-redefs [nwc/list-transactions! (fn [_ _] (swap! attempts inc) (throw timed-out))
                  bot/pause! (fn [_])]
      (is (thrown? clojure.lang.ExceptionInfo (bot/fetch-transactions! ::session 0))
          "loud, so the cursor holds and the next poll starts again")
      (is (< 1 @attempts 10) (str @attempts " attempts")))))

(deftest a-wallet-error-is-not-retried
  (let [attempts (atom 0)]
    (with-redefs [nwc/list-transactions! (fn [_ _] (swap! attempts inc)
                                           (throw (ex-info "NWC error: RESTRICTED" {:code "RESTRICTED"})))
                  bot/pause! (fn [_])]
      (is (thrown? clojure.lang.ExceptionInfo (bot/fetch-transactions! ::session 0)))
      (is (= 1 @attempts) "a refusal will not change by asking again"))))

(deftest only-the-skips-worth-finding-are-narrated
  (let [narrate? #'bot/narrate-skip?]
    (testing "an ordinary payment and a stream are constant traffic, so only counted"
      (is (not (narrate? {:skip :no-boostagram})))
      (is (not (narrate? {:skip :not-a-boost :action "stream"})))
      (is (not (narrate? {:skip :other-recipient}))
          "on a shared wallet every other split is another recipient's"))
    (testing "a defect, a dead link and an unexpected action each get a line"
      (is (narrate? {:skip :tlv-unreadable}))
      (is (narrate? {:skip :boost-link-unreadable}))
      (is (narrate? {:skip :not-a-boost :action "auto"})))
    (testing "a published boost is not a skip"
      (is (not (narrate? {:boostagram {}}))))))

(deftest a-skipped-payment-is-narrated-once-however-often-it-is-re-read
  (let [first-sighting! #'bot/first-sighting!
        h (str "narrate-once-" (System/nanoTime))]
    (is (true? (first-sighting! h)))
    (is (false? (first-sighting! h))
        "the poll re-reads the newest transaction and anything behind a held cursor")
    (is (true? (first-sighting! nil)))
    (is (true? (first-sighting! nil)) "no hash, nothing to de-duplicate on")))

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

;; ~~~~~~~~~~~~~~~~~~~ Feed address memo ~~~~~~~~~~~~~~~~~~~
;;
;; One app's boost supplies the feed address a different app's boost needs:
;; BoostMeBitch sends the address, Castamatic sends only the guid.

(def ^:private remember-feed #'bot/remember-feed)
(def ^:private with-known-feed #'bot/with-known-feed)

(def ^:private podhome "https://serve.podhome.fm/rss/7c6f7875-2b73-491e-b32c-e2c8d6e91d53")
(def ^:private show-guid "7c6f7875-2b73-491e-b32c-e2c8d6e91d53")

(deftest feed-memo-learns-and-recalls
  (testing "a boost carrying both guid and address teaches the memo"
    (let [state (remember-feed {} (bg/normalize {"action" "boost"
                                                 "guid" show-guid
                                                 "boost_link" podhome}))]
      (is (= {show-guid podhome} (get state "feeds")))

      (testing "and a later boost with only the guid picks the address up"
        (let [b (with-known-feed state (bg/normalize {"action" "boost"
                                                      "app_name" "Castamatic"
                                                      "guid" show-guid}))]
          (is (= podhome (:url b)))))

      (testing "a guid the memo has never seen is left alone"
        (is (nil? (:url (with-known-feed
                          state
                          (bg/normalize {"action" "boost"
                                         "guid" "11111111-2222-3333-4444-555555555555"}))))))

      (testing "a boost that brought its own address keeps it"
        (is (= "https://other.example/rss"
               (:url (with-known-feed state
                       (bg/normalize {"action" "boost"
                                      "guid" show-guid
                                      "url" "https://other.example/rss"})))))))))

(deftest feed-memo-refuses-what-safefetch-would
  (testing "a private or non-https address is never memoized: recalling it
            later would only hand safefetch something it has to refuse"
    (doseq [bad ["http://plain.example/rss"
                 "https://127.0.0.1/rss"
                 "https://192.168.1.10/rss"
                 "not-a-url"]]
      (is (nil? (get (remember-feed {} (bg/normalize {"action" "boost"
                                                      "guid" show-guid
                                                      "url" bad}))
                     "feeds"))
          (str "should refuse " (pr-str bad)))))

  (testing "a boost with a guid and no address teaches nothing"
    (is (nil? (get (remember-feed {} (bg/normalize {"action" "boost" "guid" show-guid}))
                   "feeds")))))

(deftest feed-memo-is-bounded
  (testing "the state file is rewritten on every boost, so the memo is capped"
    (let [full (into {} (for [i (range bot/max-feeds)] [(str "guid-" i) "https://x.example/f"]))
          state (remember-feed {"feeds" full}
                               (bg/normalize {"action" "boost"
                                              "guid" show-guid
                                              "url" podhome}))]
      (is (= {show-guid podhome} (get state "feeds"))
          "cleared rather than evicted, exactly as feed-cache is"))))

;; ~~~~~~~~~~~~~~~~~~~ Feed resolution order ~~~~~~~~~~~~~~~~~~~

(def ^:private resolve-feed #'bot/resolve-feed)

(def ^:private pi-creds {:pi-key "K" :pi-secret "S"})

(defn- pi-returns [v]
  (fn [_cfg _guid] v))

(deftest resolve-feed-prefers-cheaper-sources
  (testing "an address the app sent is used as-is, with no API call"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (fn [& _] (throw (AssertionError. "must not call the API")))]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid "url" podhome})
            r (resolve-feed pi-creds {} b)]
        (is (= podhome (:url (:boostagram r))))
        (is (nil? (:artwork r))))))

  (testing "the memo is consulted before the API"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (fn [& _] (throw (AssertionError. "must not call the API")))]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid})
            r (resolve-feed pi-creds {"feeds" {show-guid podhome}} b)]
        (is (= podhome (:url (:boostagram r)))))))

  (testing "only when both miss does the API answer -- and what it returns is
            memoized, so a show is looked up once rather than per boost"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (pi-returns {:url podhome :artwork "https://cdn.example/a.jpg"})]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid})
            r (resolve-feed pi-creds {} b)]
        (is (= podhome (:url (:boostagram r))))
        (is (= "https://cdn.example/a.jpg" (:artwork r)))
        (is (= {show-guid podhome} (get (:state r) "feeds"))
            "memoized, so the next boost for this show needs no lookup")))))

(deftest resolve-feed-degrades-quietly
  (testing "with no credentials the API is never called and the memo is the
            only source -- exactly the behaviour before it existed"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (fn [& _] (throw (AssertionError. "must not call the API")))]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid})
            r (resolve-feed {} {} b)]
        (is (nil? (:url (:boostagram r))))
        (is (nil? (:artwork r))))))

  (testing "an API miss leaves the boost exactly as it arrived"
    (with-redefs [boostbox.podcastindex/feed-by-guid (pi-returns nil)]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid})
            r (resolve-feed pi-creds {} b)]
        (is (nil? (:url (:boostagram r))))
        (is (nil? (get (:state r) "feeds"))))))

  (testing "a boost with no feed guid has nothing to look up"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (fn [& _] (throw (AssertionError. "must not call the API")))]
      (let [r (resolve-feed pi-creds {} (bg/normalize {"action" "boost"}))]
        (is (nil? (:url (:boostagram r)))))))

  (testing "artwork with no address is still worth having: the API knows the
            show's cover even when it cannot tell us where the feed lives"
    (with-redefs [boostbox.podcastindex/feed-by-guid
                  (pi-returns {:url nil :artwork "https://cdn.example/a.jpg"})]
      (let [b (bg/normalize {"action" "boost" "guid" show-guid})
            r (resolve-feed pi-creds {} b)]
        (is (nil? (:url (:boostagram r))))
        (is (= "https://cdn.example/a.jpg" (:artwork r)))))))

;; ~~~~~~~~~~~~~~~~~~~ Forwarding to MSP ~~~~~~~~~~~~~~~~~~~

(defn- stream [hash settled-at]
  {:payment-hash hash :settled-at settled-at :received-msat 1000
   :boostagram (bg/normalize {"action" "stream" "name" "MSP 2.0"
                              "guid" "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"})})

(defn- fwd-ctx [a & {:as overrides}]
  (merge (ctx a
              :forward-url "https://msp.example/api/boosts/ingest"
              :forward-token "tok"
              :forward-actions #{"boost" "auto" "stream"}
              :recipient-names #{"msp 2.0"}
              :actions #{"boost" "auto"})
         overrides))

(defn- hashes [records] (mapv #(get % "payment_hash") records))

(deftest a-stream-is-forwarded-and-never-published
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::s1])
                  nwc/transaction->boost {::s1 (stream "s1" 100)}
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})]
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (empty? @published))
      (is (= ["s1"] (hashes @sent))))))

(deftest a-boost-is-forwarded-once-its-note-is-out
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _] {:ok? true :results []})
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (= ["h1"] (hashes @sent)))
      (is (fwd/forwarded? @a "h1") "and the saved state says so"))))

(deftest a-boost-whose-note-failed-is-not-forwarded-yet
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _] {:ok? false :results []})
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (empty? @sent)))))

(deftest msp-being-down-queues-without-holding-the-cursor
  (let [a (atom {"cursor" 50 "recent" []})]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _] {:ok? true :results []})
                  fwd/send! (fn [_ _] {:ok? false :status 503})]
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (= 100 (get @a "cursor")) "the note went out, so the cursor moves on")
      (is (= ["h1"] (hashes (get @a "forward-pending")))))))

(deftest a-listed-album-is-published
  (let [a (atom {"cursor" 50 "recent" []})
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})
                  fwd/send! (fn [_ _] {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :publish-feed-guids #{"c90e609a-df1e-596a-bd5e-57bcc8aad6cc"})
                      ::session)
      (is (= 1 (count @published))))))

(deftest nothing-is-forwarded-without-a-recipient-filter
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::s1])
                  nwc/transaction->boost {::s1 (stream "s1" 100)}
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :recipient-names #{}) ::session)
      (is (empty? @sent)))))

;; The five tests above stub nwc/transaction->boost as a map, which ignores its
;; second argument (the opts) whenever the key is present -- so they would all
;; still pass even if poll-once! passed plain `ctx` instead of the widened
;; `read-ctx`. These two use a real function stub that honours `opts` the way
;; nwc/transaction->boost actually does, so they are the ones that catch a
;; regression in that wiring.
(defn- honest-stream-stub [result]
  (fn [_tx opts]
    (if (contains? (:actions opts) "stream")
      result
      {:skip :not-a-boost :action "stream"})))

(deftest a-forwarding-bot-reads-a-stream-it-would-otherwise-skip
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::s1])
                  nwc/transaction->boost (honest-stream-stub (stream "s1" 100))
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})]
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (= ["s1"] (hashes @sent)) "forwarding is on, so :actions was widened to include \"stream\"")
      (is (empty? @published)))))

(deftest a-bot-without-forwarding-still-skips-streams
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])
        published (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::s1])
                  nwc/transaction->boost (honest-stream-stub (stream "s1" 100))
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})]
      (bot/poll-once! (ctx a) ::session)
      (is (empty? @sent) "forwarding is off, so :actions was never widened and the stream stays a skip")
      (is (empty? @published)))))

;; ~~~~~~~~~~~~~~~~~~~ Forwarding survives a crash ~~~~~~~~~~~~~~~~~~~

;; A transaction the wallet hands back, carrying the time the cursor moves to.
(defn- tx [hash settled-at] {"payment_hash" hash "settled_at" settled-at})

(defn- wallet-after
  "list_transactions over `txs`, answering only what settled after `from` -- so a
   poll whose cursor has moved past a payment never reads it again."
  [txs]
  (fn [_ {:keys [from]}] (filterv #(> (get % "settled_at") from) txs)))

(deftest a-stream-is-saved-in-the-queue-before-it-is-sent
  (let [a (atom {"cursor" 50 "recent" []})
        at-send (atom nil)
        t1 (tx "s1" 100)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 (stream "s1" 100)}
                  fwd/send! (fn [_ _] (reset! at-send @a) (throw (ex-info "killed mid-send" {})))]
      (is (thrown? Exception (bot/poll-once! (fwd-ctx a) ::session)))
      (is (= ["s1"] (hashes (get @at-send "forward-pending")))
          "the saved state held the stream before any send began")
      (is (= 100 (get @at-send "cursor")) "in the same save that moved the cursor past it"))))

(deftest an-unlisted-album-is-forwarded-and-never-published
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])
        published (atom [])
        t1 (tx "h1" 100)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] (throw (AssertionError. "an unlisted boost is not stored")))
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :publish-feed-guids #{"917393e3-1b1e-5cef-ace4-edaa54e1f810"})
                      ::session)
      (is (empty? @published) "its artist never agreed")
      (is (= ["h1"] (hashes @sent)) "the chart still counts it")
      (is (= 100 (get @a "cursor")) "and it is never read again"))))

(def ^:private artist "1a197bac-95ae-53bd-bf6d-40ba8b551088")
(def ^:private album-guid "fe17f4f6-074f-4b2c-a450-611faccfaea2")

(defn- i-tags [e] (set (for [[k v] (:tags e) :when (= "i" k)] v)))

(deftest an-artist-on-the-list-is-published-from-any-of-their-albums
  (let [a (atom {"cursor" 50 "recent" []})
        published (atom [])
        t1 (tx "h1" 100)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 (boost "h1" 100)}
                  bot/feed-context (fn [_ _] {:publisher-guid artist})
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})
                  fwd/send! (fn [_ _] {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :publish-feed-guids #{artist}) ::session)
      (is (= 1 (count @published)) "the album is not listed, but its artist is")
      (is (contains? (i-tags (first @published)) (str "podcast:publisher:guid:" artist))
          "and the note names the artist as NIP-73's publisher id"))))

(deftest a-music-show-boost-is-listed-by-the-songs-own-album
  ;; The show's feed names the show's publisher, not the artist. The song's
  ;; album is the remote feed, and blip-10 carries no address for it, so the
  ;; bot finds it from what an earlier boost taught it.
  (let [a (atom {"cursor" 50 "recent" [] "feeds" {album-guid "https://x.example/album.xml"}})
        published (atom [])
        t1 (tx "h1" 100)
        b (assoc-in (boost "h1" 100) [:boostagram :remote-feed-guid] album-guid)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 b}
                  bot/read-feed-at (fn [_ url _]
                                     (when (= url "https://x.example/album.xml") {:publisher-guid artist}))
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})
                  fwd/send! (fn [_ _] {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :publish-feed-guids #{artist} :feed-lookup? true) ::session)
      (is (= 1 (count @published)))
      (is (contains? (i-tags (first @published)) (str "podcast:publisher:guid:" artist))))))

(deftest a-feed-that-cannot-be-read-lists-no-artist
  (let [a (atom {"cursor" 50 "recent" []})
        published (atom [])
        sent (atom [])
        t1 (tx "h1" 100)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 (boost "h1" 100)}
                  bot/feed-context (fn [_ _] nil)
                  bot/store-boost! (fn [_ _] (throw (AssertionError. "an unlisted boost is not stored")))
                  relay/publish-to-relays! (fn [_ e] (swap! published conj e) {:ok? true :results []})
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :publish-feed-guids #{artist}) ::session)
      (is (empty? @published) "nothing says whose album it is, so it is nobody's")
      (is (= ["h1"] (hashes @sent)) "and the chart still counts it"))))

(deftest a-published-boost-is-saved-in-the-queue-before-it-is-sent
  (let [a (atom {"cursor" 50 "recent" []})
        at-send (atom nil)]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::tx1])
                  nwc/transaction->boost {::tx1 (boost "h1" 100)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _] {:ok? true :results []})
                  fwd/send! (fn [_ _] (reset! at-send @a) (throw (ex-info "killed mid-send" {})))]
      (is (thrown? Exception (bot/poll-once! (fwd-ctx a) ::session)))
      (is (= ["h1"] (hashes (get @at-send "forward-pending"))))
      (is (= 100 (get @at-send "cursor"))))))

(deftest the-poll-after-a-crash-sends-what-was-queued
  (let [a (atom {"cursor" 50 "recent" []})
        crash? (atom true)
        sent (atom [])
        t1 (tx "s1" 100)]
    (with-redefs [nwc/list-transactions! (wallet-after [t1])
                  nwc/transaction->boost {t1 (stream "s1" 100)}
                  fwd/send! (fn [_ batch]
                              (when @crash? (throw (ex-info "killed mid-send" {})))
                              (swap! sent into batch)
                              {:ok? true :status 200})]
      (is (thrown? Exception (bot/poll-once! (fwd-ctx a) ::session)))
      (reset! crash? false)
      (bot/poll-once! (fwd-ctx a) ::session)
      (is (= ["s1"] (hashes @sent)) "sent from the queue, though the wallet no longer returns it")
      (is (fwd/forwarded? @a "s1"))
      (is (empty? (get @a "forward-pending"))))))

(deftest a-dry-run-bot-forwards-nothing
  (let [a (atom {"cursor" 50 "recent" []})
        sent (atom [])]
    (with-redefs [nwc/list-transactions! (fn [_ _] [::s1 ::tx1])
                  nwc/transaction->boost {::s1 (stream "s1" 100) ::tx1 (boost "h1" 200)}
                  bot/store-boost! (fn [_ _] {:id "01K9" :url "https://tardbox.com/boost/01K9"})
                  relay/publish-to-relays! (fn [_ _] (throw (AssertionError. "dry run must not publish")))
                  fwd/send! (fn [_ batch] (swap! sent into batch) {:ok? true :status 200})]
      (bot/poll-once! (fwd-ctx a :dry-run? true) ::session)
      (is (empty? @sent) "a dry run never records event_id, so it must not forward either")
      (is (empty? (get @a "forward-pending")) "nor queue anything to send later"))))

(deftest a-full-queue-says-which-payments-it-dropped
  (let [old (mapv #(fwd/->record (stream (str "q" %) (+ 1000 %))) (range fwd/max-pending))
        a (atom {"cursor" 50 "recent" [] "forward-pending" old})
        logs (atom [])
        t1 (tx "s1" 5000)
        t2 (tx "s2" 5001)]
    (with-redefs [nwc/list-transactions! (wallet-after [t2 t1])
                  nwc/transaction->boost {t1 (stream "s1" 5000) t2 (stream "s2" 5001)}
                  fwd/send! (fn [_ _] {:ok? false :status 503})
                  mulog-core/log* (fn [_ event pairs]
                                    (swap! logs conj (assoc (apply hash-map pairs) :event event)))]
      (bot/poll-once! (fwd-ctx a) ::session)
      (let [dropped (filter #(= ::bot/forward-pending-dropped (:event %)) @logs)]
        (is (= 1 (count dropped)) "one line for the poll")
        (is (= {:dropped 2 :oldest-dropped-time 1000 :newest-dropped-time 1001}
               (select-keys (first dropped) [:dropped :oldest-dropped-time :newest-dropped-time]))
            "the times a backfill must reach back to"))
      (is (= fwd/max-pending (count (get @a "forward-pending"))))
      (is (= ["s1" "s2"] (take-last 2 (hashes (get @a "forward-pending"))))
          "queued oldest first, whatever order the wallet answered in"))))
