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
  (testing "malformed guids are dropped rather than emitted"
    (is (= [["t" "boostagram"]]
           (bg/->nip73-tags (bg/normalize (assoc fountain-tlv
                                                 "guid" "920666"
                                                 "episode_guid" "not-a-uuid"))
                            {})))))

(deftest guid-validation
  (is (bg/valid-guid? "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"))
  (is (bg/valid-guid? "C90E609A-DF1E-596A-BD5E-57BCC8AAD6CC"))
  (is (not (bg/valid-guid? "920666")))
  (is (not (bg/valid-guid? "")))
  (is (not (bg/valid-guid? nil)))
  (is (not (bg/valid-guid? "c90e609a-df1e-596a-bd5e-57bcc8aad6c"))))

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
      (is (= "⚡ 1 sat boost" c)))))

(deftest sats-formatting-is-locale-independent
  (is (= "1 sat" (bg/format-sats 1000)))
  (is (= "0 sats" (bg/format-sats 999)) "sub-sat amounts truncate")
  (is (= "2,100 sats" (bg/format-sats 2100000)))
  (is (= "0 sats" (bg/format-sats nil))))

(deftest tlv-hex-decoding
  (let [json "{\"action\":\"boost\",\"message\":\"héllo 🚀\"}"
        hex (nostr/bytes->hex (.getBytes json "UTF-8"))]
    (is (= json (bg/tlv-hex->string hex)) "round trips through hex as UTF-8")))
