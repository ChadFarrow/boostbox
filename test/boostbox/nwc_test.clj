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
      "the NWC transaction extension does not pin the encoding, so both are accepted")
  (testing "a decode only counts if it yields JSON"
    (is (nil? (nwc/decode-tlv-value (hex-tlv "not json at all"))))
    (is (nil? (nwc/decode-tlv-value "zzzz")) "neither hex nor base64")))

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
    (let [stream-json (clojure.string/replace blip10-json "\"boost\"" "\"stream\"")
          r (nwc/transaction->boost
             {"payment_hash" "x" "amount" 1000
              "metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv stream-json)}]}})]
      (is (nil? (:boostagram r)))
      (is (= :not-a-boost (:skip r)))
      (is (= "stream" (:action r)) "so the log line says what it actually was")))

  (testing "a payment with no boostagram is not a boost"
    (is (= :no-boostagram (:skip (nwc/transaction->boost {"payment_hash" "x" "amount" 1000}))))))

(deftest a-skipped-transaction-says-why
  (testing "the reasons are distinct, because a count cannot tell a stream from a defect"
    (is (= :no-boostagram (:skip (nwc/transaction->boost {})))
        "an ordinary payment, and the common case on a wallet that also takes them")

    (is (= :tlv-unreadable
           (:skip (nwc/transaction->boost
                   {"metadata" {"tlv_records" [{"type" 7629169 "value" "zzzz"}]}})))
        "a TLV record was there and neither hex nor base64 read it")

    (is (= :tlv-unreadable
           (:skip (nwc/transaction->boost
                   {"metadata" {"tlv_records"
                                [{"type" 7629169 "value" (hex-tlv "[1,2,3]")}]}})))
        "valid JSON that is not an object is no more a boostagram than garbage"))

  (testing "an unreadable TLV still falls back to the wallet's parsed copy"
    (let [r (nwc/transaction->boost
             {"metadata" {"tlv_records" [{"type" 7629169 "value" "zzzz"}]
                          "boostagram" {"action" "boost" "podcast" "P"}}})]
      (is (nil? (:skip r)))
      (is (= "P" (:podcast (:boostagram r))))))

  (testing "a TLV for some other record type is not a boostagram at all"
    (is (= :no-boostagram
           (:skip (nwc/transaction->boost
                   {"metadata" {"tlv_records"
                                [{"type" 133773310 "value" (hex-tlv "whatever")}]}})))))

  (testing "every reason the classifier can emit is a declared one"
    (doseq [tx [{}
                {"metadata" {"tlv_records" [{"type" 7629169 "value" "zzzz"}]}}
                {"metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv "[1,2,3]")}]}}
                {"metadata" {"boostagram" {"action" "stream"}}}]]
      (is (contains? nwc/skip-reasons (:skip (nwc/transaction->boost tx)))
          (pr-str tx)))))

(deftest podcast-guru-sends-both-url-and-boost-link
  (testing "the feed address wins over the app deep link, and the boost publishes"
    ;; A real PodcastGuru payload. Its `boost_link` is an app.podcastguru.io
    ;; deep link, NOT a BoostBox permalink and NOT a feed -- so reading it as
    ;; the feed address would cost the note its cover art and every p tag.
    (let [json (str "{\"value_msat\":3000,\"app_version\":\"2.3.2-beta2\","
                    "\"sender_name\":\"Silvie\",\"episode\":\"007. The show must go one\","
                    "\"message\":\"enjoyed the rewind, Chad\",\"value_msat_total\":333000,"
                    "\"url\":\"https://serve.podhome.fm/rss/7c6f7875-2b73-491e-b32c-e2c8d6e91d53\","
                    "\"sender_id\":\"fd4fdc23-2836-4eeb-86b0-8627b9bf51cb\","
                    "\"boost_link\":\"https://app.podcastguru.io/podcast/X686874\","
                    "\"app_name\":\"PodcastGuru\","
                    "\"episode_guid\":\"8c82416b-4adf-43aa-8836-e79257362fd5\","
                    "\"podcast\":\"Chad and Reeds Podcast\",\"name\":\"boostr\","
                    "\"guid\":\"7c6f7875-2b73-491e-b32c-e2c8d6e91d53\","
                    "\"action\":\"boost\",\"ts\":4910}")
          r (nwc/transaction->boost
             {"payment_hash" "7f4e94e3" "amount" 105000 "settled_at" 1789000000
              "metadata" {"tlv_records" [{"type" 7629169 "value" (hex-tlv json)}]}})
          b (:boostagram r)]
      (is (nil? (:skip r)) "this is a publishable boost")
      (is (= "https://serve.podhome.fm/rss/7c6f7875-2b73-491e-b32c-e2c8d6e91d53" (:url b))
          "url wins over boost_link")
      (is (= "7c6f7875-2b73-491e-b32c-e2c8d6e91d53" (:feed-guid b)))
      (is (= "8c82416b-4adf-43aa-8836-e79257362fd5" (:item-guid b)))
      (is (= "boostr" (:recipient-name b)))
      (is (= 333000 (:value-msat-total b)) "the whole boost, not this split")
      (is (= 105000 (:received-msat r)) "what actually arrived")
      (is (= 4910 (:position b)) "ts is seconds into the episode"))))
