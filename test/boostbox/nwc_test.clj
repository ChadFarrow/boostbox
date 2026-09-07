(ns boostbox.nwc-test
  (:require [clojure.test :refer [deftest testing is]]
            [boostbox.nwc :as nwc]
            [boostbox.nostr :as nostr]))

(def wallet-pubkey (apply str (repeat 64 "a")))
(def secret (apply str (repeat 64 "b")))

(deftest parse-connection-uri
  (let [uri (str "nostr+walletconnect://" wallet-pubkey
                 "?relay=wss%3A%2F%2Frelay.getalby.com%2Fv1&secret=" secret
                 "&lud16=boostbot%40getalby.com")
        p (nwc/parse-uri uri)]
    (is (= wallet-pubkey (:wallet-pubkey p)))
    (is (= ["wss://relay.getalby.com/v1"] (:relays p)) "relay is url-decoded")
    (is (= secret (:secret p)))
    (is (= "boostbot@getalby.com" (:lud16 p)) "url-decoded, and defaults the profile lud16")
    (is (= 32 (alength ^bytes (:secret-bytes p)))))

  (testing "multiple relay params are all kept"
    (let [p (nwc/parse-uri (str "nostr+walletconnect://" wallet-pubkey
                                "?relay=wss://a.example&relay=wss://b.example"
                                "&secret=" secret))]
      (is (= ["wss://a.example" "wss://b.example"] (:relays p)))))

  (testing "uppercase pubkey is normalized"
    (let [p (nwc/parse-uri (str "nostr+walletconnect://" (clojure.string/upper-case wallet-pubkey)
                                "?relay=wss://a.example&secret=" secret))]
      (is (= wallet-pubkey (:wallet-pubkey p)))))

  (testing "malformed URIs are rejected rather than half-parsed"
    (is (thrown? Exception (nwc/parse-uri "https://example.com")))
    (is (thrown? Exception (nwc/parse-uri (str "nostr+walletconnect://short?relay=wss://a&secret=" secret))))
    (is (thrown? Exception (nwc/parse-uri (str "nostr+walletconnect://" wallet-pubkey "?relay=wss://a")))
        "no secret")
    (is (thrown? Exception (nwc/parse-uri (str "nostr+walletconnect://" wallet-pubkey "?secret=" secret)))
        "no relay")))

(def blip10-json
  (str "{\"podcast\":\"Podcasting 2.0\",\"feedID\":920666,"
       "\"guid\":\"c90e609a-df1e-596a-bd5e-57bcc8aad6cc\","
       "\"episode_guid\":\"d98d189b-dc7b-45b1-8720-d4b98690f31f\","
       "\"action\":\"boost\",\"app_name\":\"Fountain\",\"message\":\"Great show!\","
       "\"sender_name\":\"Alice\",\"ts\":1435,\"value_msat_total\":2100000}"))

(defn- hex-tlv [s] (nostr/bytes->hex (.getBytes ^String s "UTF-8")))
(defn- b64-tlv [s] (.encodeToString (java.util.Base64/getEncoder) (.getBytes ^String s "UTF-8")))

(deftest decode-tlv-value-accepts-hex-and-base64
  (is (= blip10-json (nwc/decode-tlv-value (hex-tlv blip10-json))))
  (is (= blip10-json (nwc/decode-tlv-value (b64-tlv blip10-json)))
      "the NWC transaction extension does not pin the encoding, so both are accepted"))

(deftest extract-boostagram-prefers-raw-tlv
  (testing "raw TLV carries the GUIDs"
    (let [tx {"metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv blip10-json)}]}}
          b (nwc/extract-boostagram tx)]
      (is (= "c90e609a-df1e-596a-bd5e-57bcc8aad6cc" (:feed-guid b)))
      (is (= "Great show!" (:message b)))
      (is (= 1435 (:position b)))))

  (testing "the raw TLV wins even when the wallet also offers its parsed copy"
    (let [tx {"metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv blip10-json)}]
                          "boostagram" {"action" "boost" "podcast" "Wrong"}}}
          b (nwc/extract-boostagram tx)]
      (is (= "Podcasting 2.0" (:podcast b)))
      (is (some? (:feed-guid b)) "GUIDs survive, which they would not via the wallet's copy")))

  (testing "falls back to the wallet's parsed boostagram, losing the GUIDs"
    (let [tx {"metadata" {"boostagram" {"action" "boost" "podcast" "Podcasting 2.0"
                                        "feedID" 920666 "value_msat_total" 2100000}}}
          b (nwc/extract-boostagram tx)]
      (is (= "Podcasting 2.0" (:podcast b)))
      (is (nil? (:feed-guid b)))))

  (testing "other TLV types are ignored"
    (is (nil? (nwc/extract-boostagram
               {"metadata" {"tlv_records" [{"type" 133773310 "value" (hex-tlv "whatever")}]}}))))

  (testing "a plain payment with no metadata yields nil"
    (is (nil? (nwc/extract-boostagram {})))
    (is (nil? (nwc/extract-boostagram {"metadata" {}}))))

  (testing "unparseable TLV does not blow up the poll loop"
    (is (nil? (nwc/extract-boostagram
               {"metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv "{not json")}]}})))))

(deftest transaction->boost
  (let [tx {"payment_hash" "deadbeef"
            "amount" 21000
            "settled_at" 1757275200
            "metadata" {"tlv_records" [{"type" "7629169" "value" (hex-tlv blip10-json)}]}}
        b (nwc/transaction->boost tx)]
    (is (= "deadbeef" (:payment-hash b)))
    (is (= 21000 (:received-msat b)) "the amount that actually arrived")
    (is (= 1757275200 (:settled-at b)))
    (is (= "Great show!" (-> b :boostagram :message)))
    (is (= "7629169" (get-in tx ["metadata" "tlv_records" 0 "type"]))
        "sanity: type matched even as a string"))

  (testing "streams are not republished"
    (let [stream-json (clojure.string/replace blip10-json "\"boost\"" "\"stream\"")]
      (is (nil? (nwc/transaction->boost
                 {"payment_hash" "x" "amount" 1000
                  "metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv stream-json)}]}})))))

  (testing "a payment with no boostagram is not a boost"
    (is (nil? (nwc/transaction->boost {"payment_hash" "x" "amount" 1000})))))
