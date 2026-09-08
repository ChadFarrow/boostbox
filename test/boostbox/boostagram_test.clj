(ns boostbox.boostagram-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [boostbox.boostagram :as bg]
            [boostbox.nostr :as nostr]))

;; A realistic blip-10 payload as a podcast app sends it in TLV 7629169.
;; Note `ts` is seconds into the episode and `value_msat` is only this split's
;; share -- both are routinely misread.
(def fountain-tlv
  {"podcast" "Podcasting 2.0"
   "feedID" 920666
   "episode" "Episode 158: The Big One"
   "itemID" "16795090"
   "guid" "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"
   "episode_guid" "d98d189b-dc7b-45b1-8720-d4b98690f31f"
   "action" "boost"
   "app_name" "Fountain"
   "app_version" "1.1.9"
   "message" "Great show!"
   "sender_name" "Alice"
   "sender_id" "abc123"
   "name" "TardBox"
   "ts" 1435
   "time" "00:23:55"
   "value_msat" 21000
   "value_msat_total" 2100000})

;; What Alby Hub's own parsed Boostagram struct looks like: numeric ids only,
;; every GUID dropped.
(def alby-parsed
  {"podcast" "Podcasting 2.0"
   "feedID" 920666
   "itemID" 16795090
   "episode" "Episode 158"
   "action" "boost"
   "app_name" "Fountain"
   "message" "Great show!"
   "sender_name" "Alice"
   "value_msat_total" 2100000})

(deftest normalize-reads-blip10-fields
  (let [b (bg/normalize fountain-tlv)]
    (is (= "boost" (:action b)))
    (is (= "Great show!" (:message b)))
    (is (= "Alice" (:sender-name b)))
    (is (= "TardBox" (:recipient-name b)) "blip-10 'name' is the recipient split name")
    (is (= "c90e609a-df1e-596a-bd5e-57bcc8aad6cc" (:feed-guid b)) "'guid' is the feed guid")
    (is (= "d98d189b-dc7b-45b1-8720-d4b98690f31f" (:item-guid b)) "'episode_guid' is the item guid")
    (is (= 1435 (:position b)) "'ts' is seconds into the episode")
    (is (= 2100000 (:value-msat-total b)))
    (is (= "920666" (:feed-id b)) "StringOrNumber coerced to string")))

(deftest normalize-handles-albys-lossy-struct
  (let [b (bg/normalize alby-parsed)]
    (is (= "boost" (:action b)))
    (is (nil? (:feed-guid b))
        "Alby's parsed boostagram has no GUIDs -- this is why the raw TLV is preferred")
    (is (nil? (:item-guid b)))
    (is (= "16795090" (:item-id b)) "numeric itemID still coerces to string")))

(deftest normalize-tolerates-junk
  (is (nil? (bg/normalize nil)))
  (is (nil? (bg/normalize "not a map")))
  (let [b (bg/normalize {"action" "BOOST" "message" "   " "ts" "1435" "value_msat" "21000"})]
    (is (= "boost" (:action b)) "action is lower-cased")
    (is (nil? (:message b)) "blank strings become nil")
    (is (= 1435 (:position b)) "numeric strings coerce")
    (is (= 21000 (:value-msat b)))))

(deftest only-boosts-are-republished
  (is (bg/boost? (bg/normalize fountain-tlv)))
  (is (not (bg/boost? (bg/normalize (assoc fountain-tlv "action" "stream")))))
  (is (not (bg/boost? (bg/normalize {})))))

(deftest boost-payload-mapping
  (let [b (bg/normalize fountain-tlv)
        p (bg/->boost-payload b {:received-msat 21000 :settled-at 1757275200})]
    (is (= "boost" (get p "action")))
    (is (= 21000 (get p "value_msat")))
    (is (= 2100000 (get p "value_msat_total")))
    (is (= 1.0 (get p "split")) "1% of the total landed here")
    (is (= "2025-09-07T20:00:00Z" (get p "timestamp")) "wall clock comes from the payment")
    (is (= 1435 (get p "position")) "position is seconds into the episode")
    (is (= "Podcasting 2.0" (get p "feed_title")))
    (is (= "c90e609a-df1e-596a-bd5e-57bcc8aad6cc" (get p "feed_guid")))
    (is (= "d98d189b-dc7b-45b1-8720-d4b98690f31f" (get p "item_guid")))
    (is (not (contains? p "value_usd")) "absent fields are omitted, not sent as null")))

(deftest received-amount-beats-the-apps-claim
  (let [b (bg/normalize (assoc fountain-tlv "value_msat" 999999))
        p (bg/->boost-payload b {:received-msat 21000 :settled-at 0})]
    (is (= 21000 (get p "value_msat"))
        "what actually arrived wins over what the app said it sent")))

(deftest boost-payload-always-satisfies-required-schema-bounds
  (testing "BoostMetadata requires action/split/value_msat/value_msat_total/timestamp"
    (doseq [tlv [{} {"action" "boost"} {"value_msat_total" 0} fountain-tlv]]
      (let [p (bg/->boost-payload (bg/normalize tlv) {})]
        (is (contains? #{"boost" "stream"} (get p "action")))
        (is (>= (get p "value_msat") 1) "schema requires min 1")
        (is (>= (get p "value_msat_total") 1) "schema requires min 1")
        (is (>= (get p "split") 0.0))
        (is (string? (get p "timestamp")))
        (is (inst? (java.time.Instant/parse (get p "timestamp"))) "must be ISO-8601")))))

(deftest nip73-tags
  (testing "both guids present"
    (is (= [["i" "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc"]
            ["k" "podcast:guid"]
            ["i" "podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"]
            ["k" "podcast:item:guid"]
            ["r" "https://tardbox.com/boost/01K9"]
            ["t" "boostagram"]]
           (bg/->nip73-tags (bg/normalize fountain-tlv)
                            {:boost-url "https://tardbox.com/boost/01K9"}))))
  (testing "no guids -- still publishable, just untagged"
    (is (= [["t" "boostagram"]]
           (bg/->nip73-tags (bg/normalize alby-parsed) {}))))
  (testing "a malformed feed guid is dropped rather than emitted"
    (is (= [["t" "boostagram"]]
           (bg/->nip73-tags (bg/normalize (assoc fountain-tlv
                                                 "guid" "920666"
                                                 "episode_guid" ""))
                            {}))))
  (testing "a non-UUID item guid is still tagged: RSS <item><guid> is an
            arbitrary string, and requiring a UUID would drop episode tags for
            almost every real feed"
    (let [tags (bg/->nip73-tags
                (bg/normalize (assoc fountain-tlv
                                     "episode_guid" "https://example.com/ep/42?a=B"))
                {})]
      (is (some #(= ["i" "podcast:item:guid:https://example.com/ep/42?a=B"] %) tags)
          "and not lower-cased -- an item guid may be a case-sensitive URL")
      (is (some #(= ["k" "podcast:item:guid"] %) tags)))))

(deftest guid-validation
  (testing "a feed guid must be a UUID"
    (is (bg/valid-feed-guid? "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"))
    (is (bg/valid-feed-guid? "C90E609A-DF1E-596A-BD5E-57BCC8AAD6CC"))
    (is (not (bg/valid-feed-guid? "920666")))
    (is (not (bg/valid-feed-guid? "")))
    (is (not (bg/valid-feed-guid? nil)))
    (is (not (bg/valid-feed-guid? "c90e609a-df1e-596a-bd5e-57bcc8aad6c"))))
  (testing "an item guid is any non-blank string of sane length"
    (is (bg/valid-item-guid? "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"))
    (is (bg/valid-item-guid? "https://example.com/episodes/42"))
    (is (bg/valid-item-guid? "PC20-0042"))
    (is (not (bg/valid-item-guid? "")))
    (is (not (bg/valid-item-guid? "   ")))
    (is (not (bg/valid-item-guid? nil)))
    (is (not (bg/valid-item-guid? (apply str (repeat 257 "x")))))))

(deftest note-content
  (let [c (bg/->note-content (bg/normalize fountain-tlv)
                             {:boost-url "https://tardbox.com/boost/01K9"})]
    (is (str/includes? c "2,100 sats"))
    (is (str/includes? c "Podcasting 2.0"))
    (is (str/includes? c "Alice"))
    (is (str/includes? c "Great show!"))
    (is (str/includes? c "https://tardbox.com/boost/01K9")))
  (testing "a boost with no message, show or sender still reads sensibly"
    (let [c (bg/->note-content (bg/normalize {"action" "boost" "value_msat_total" 1000}) {})]
      (is (= "⚡ 1 sat boost" c))))
  (testing "the headline falls back to what actually arrived when the
            boostagram omits value_msat_total, rather than reading 0 sats"
    (is (= "⚡ 21 sats boost"
           (bg/->note-content (bg/normalize {"action" "boost"})
                              {:received-msat 21000})))
    (is (= "⚡ 5 sats boost"
           (bg/->note-content (bg/normalize {"action" "boost" "value_msat" 5000})
                              {}))
        "and to the boostagram's own share if even that is missing")))

(deftest sats-formatting-is-locale-independent
  (is (= "1 sat" (bg/format-sats 1000)))
  (is (= "0 sats" (bg/format-sats 999)) "sub-sat amounts truncate")
  (is (= "2,100 sats" (bg/format-sats 2100000)))
  (is (= "0 sats" (bg/format-sats nil))))

(deftest tlv-hex-decoding
  (let [json "{\"action\":\"boost\",\"message\":\"héllo 🚀\"}"
        hex (nostr/bytes->hex (.getBytes json "UTF-8"))]
    (is (= json (bg/tlv-hex->string hex)) "round trips through hex as UTF-8")))

;; ~~~~~~~~~~~~~~~~~~~ Sender field-naming ~~~~~~~~~~~~~~~~~~~

(deftest titles-accept-boostbox-style-field-names
  (testing "blip-10 says podcast/episode, but senders modelled on BoostBox's own
            schema send feed_title/item_title -- a real BoostMeBitch payload"
    (let [b (bg/normalize {"action" "boost"
                           "feed_title" "LNURL Testing Podcast"
                           "item_title" "LNURL Test Episode 3"
                           "feed_guid" "9fe51a32-e08d-5ab7-9540-22a25c6bc2bf"
                           "item_guid" "c4dac22d-173f-4442-9b3b-5d89b20b26e6"
                           "sender_name" "ChadF"
                           "value_msat" 10000
                           "value_msat_total" 100000})]
      (is (= "LNURL Testing Podcast" (:podcast b)))
      (is (= "LNURL Test Episode 3" (:episode b)))
      (testing "so the note names the show instead of trailing off after the amount"
        (let [c (bg/->note-content b {})]
          (is (str/includes? c "to LNURL Testing Podcast"))
          (is (str/includes? c "LNURL Test Episode 3"))))
      (testing "and the stored boost keeps both titles"
        (let [p (bg/->boost-payload b {:received-msat 10000 :settled-at 1757288117})]
          (is (= "LNURL Testing Podcast" (get p "feed_title")))
          (is (= "LNURL Test Episode 3" (get p "item_title")))))))

  (testing "every BoostMetadata field name round-trips, not just the titles"
    (let [b (bg/normalize {"action" "boost"
                           "recipient_name" "boostr"
                           "position" 42
                           "feed_title" "Show" "item_title" "Ep"
                           "feed_guid" "9fe51a32-e08d-5ab7-9540-22a25c6bc2bf"
                           "value_msat_total" 100000})
          p (bg/->boost-payload b {:received-msat 10000 :settled-at 1757288117})]
      (is (= "boostr" (get p "recipient_name")))
      (is (= 42 (get p "position")))
      (is (= "Show" (get p "feed_title")))
      (is (= "Ep" (get p "item_title")))))

  (testing "the blip-10 names still win when both are present"
    (let [b (bg/normalize {"action" "boost"
                           "podcast" "canonical" "feed_title" "fallback"
                           "episode" "canonical-ep" "item_title" "fallback-ep"})]
      (is (= "canonical" (:podcast b)))
      (is (= "canonical-ep" (:episode b))))))

;; ~~~~~~~~~~~~~~~~~~~ Boost links ~~~~~~~~~~~~~~~~~~~

(deftest boost-link-is-origin-restricted
  (let [trusted ["https://tardbox.com"]]
    (testing "a BoostBox permalink is found however the description frames it"
      (is (= "https://tardbox.com/boost/01ABC"
             (bg/boost-link "rss::payment::boost https://tardbox.com/boost/01ABC hi" trusted)))
      (is (= "https://tardbox.com/boost/01ABC"
             (bg/boost-link "https://tardbox.com/boost/01ABC" trusted))
          "apps that do not use the rss::payment:: prefix still work")
      (is (= "https://tardbox.com/boost/01ABC"
             (bg/boost-link "see https://tardbox.com/boost/01ABC." trusted))
          "trailing sentence punctuation is not part of the URL"))

    (testing "naming origins locks the bot down to exactly those"
      (is (nil? (bg/boost-link "rss::payment::boost https://evil.example/x hi" trusted)))
      (is (nil? (bg/boost-link "https://tardbox.com.evil.example/x" trusted))
          "a lookalike host is a different origin")
      (is (nil? (bg/boost-link "just a message" trusted)))
      (is (nil? (bg/boost-link "http://tardbox.com/boost/01ABC" trusted))
          "http and https are different origins"))

    (testing "with no origins named, any https link is in scope -- apps POST to
              whichever BoostBox they run, and a podcaster cannot enumerate
              those in advance"
      (is (= "https://boostbox.someapp.com/boost/01X"
             (bg/boost-link "rss::payment::boost https://boostbox.someapp.com/boost/01X hi" [])))
      (testing "but plaintext http never is, and the address itself is checked
                separately at fetch time -- see nostrbot/fetchable-url?"
        (is (nil? (bg/boost-link "http://169.254.169.254/latest/meta-data/" [])))
        (is (nil? (bg/boost-link "http://localhost:8080/admin" [])))))))

(deftest payer-written-text-is-bounded
  (testing "every one of these is written by whoever paid us and ends up signed
            under the bot's own key"
    (let [b (bg/normalize {"action" "boost"
                           "message" (apply str (repeat 900 "x"))
                           "feed_title" (apply str (repeat 400 "t"))
                           "sender_name" (apply str (repeat 300 "n"))})]
      (is (= (inc bg/max-message-length) (count (:message b))) "capped, plus the ellipsis")
      (is (= (inc bg/max-title-length) (count (:podcast b))))
      (is (= (inc bg/max-name-length) (count (:sender-name b))))
      (is (str/ends-with? (:message b) "…"))))

  (testing "control characters are stripped so a payer cannot forge structure"
    (let [b (bg/normalize {"action" "boost"
                           "feed_title" (str "Ti" (char 7) "tle" (char 13) "X")
                           "message" (str "line1" (char 13) "overwrite")})]
      (is (= "TitleX" (:podcast b)))
      (is (= "line1overwrite" (:message b)))))

  (testing "a newline is ordinary in a message and not in a title"
    (let [b (bg/normalize {"action" "boost" "message" "a\nb" "feed_title" "a\nb"})]
      (is (= "a\nb" (:message b)))
      (is (= "ab" (:podcast b)))))

  (testing "short text is untouched"
    (is (= "row of ducks" (:message (bg/normalize {"action" "boost" "message" "row of ducks"}))))))

(deftest boost-id-comes-off-the-end-of-the-url
  (is (= "01M1Z3KRQ0E27RZ2T0CT1B2NEE"
         (bg/boost-id-from-url "https://tardbox.com/boost/01M1Z3KRQ0E27RZ2T0CT1B2NEE")))
  (is (= "01ABC" (bg/boost-id-from-url "https://tardbox.com/boost/01ABC/")))
  (is (nil? (bg/boost-id-from-url nil))))
