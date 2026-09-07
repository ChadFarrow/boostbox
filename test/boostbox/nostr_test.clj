(ns boostbox.nostr-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [boostbox.nostr :as nostr]))

;; ~~~~~~~~~~~~~~~~~~~ BIP-340 official vectors ~~~~~~~~~~~~~~~~~~~
;;
;; test/resources/bip340-vectors.csv is the unmodified file from the bitcoin/bips
;; repository. The signing path here is hand-written on top of BouncyCastle's
;; curve math, so these vectors -- the failure cases especially -- are the only
;; thing standing between us and silently producing invalid signatures.

(defn- read-vectors []
  (with-open [r (io/reader (io/resource "bip340-vectors.csv"))]
    (let [[header & rows] (line-seq r)
          ks (map #(-> % str/trim (str/replace " " "-") keyword)
                  (str/split header #","))]
      (doall (map #(zipmap ks (str/split % #"," -1)) rows)))))

(deftest bip340-verification-vectors
  (doseq [{:keys [index public-key message signature verification-result comment]}
          (read-vectors)]
    (testing (str "vector " index (when-not (str/blank? comment) (str " -- " comment)))
      (is (= (= "TRUE" (str/upper-case verification-result))
             (nostr/verify? (nostr/hex->bytes public-key)
                            (nostr/hex->bytes message)
                            (nostr/hex->bytes signature)))))))

(deftest bip340-signing-vectors
  (doseq [{:keys [index secret-key public-key aux_rand message signature]}
          (read-vectors)
          :when (not (str/blank? secret-key))]
    (testing (str "vector " index)
      (let [sk (nostr/hex->bytes secret-key)]
        (is (= (str/lower-case public-key)
               (nostr/bytes->hex (nostr/x-only-pubkey sk)))
            "derived x-only pubkey")
        (is (= (str/lower-case signature)
               (nostr/bytes->hex (nostr/sign sk
                                             (nostr/hex->bytes message)
                                             (nostr/hex->bytes aux_rand))))
            "signature matches the vector exactly")))))

(deftest signing-is-deterministic-given-aux-rand
  (let [sk (nostr/hex->bytes (apply str (repeat 64 "a")))
        msg (nostr/sha256 (.getBytes "boost" "UTF-8"))
        aux (byte-array 32)]
    (is (= (nostr/bytes->hex (nostr/sign sk msg aux))
           (nostr/bytes->hex (nostr/sign sk msg aux))))))

(deftest random-aux-rand-still-verifies
  (let [sk (nostr/hex->bytes (apply str (repeat 64 "3")))
        pk (nostr/x-only-pubkey sk)]
    (dotimes [_ 20]
      (let [msg (nostr/sha256 (.getBytes (str (random-uuid)) "UTF-8"))]
        (is (nostr/verify? pk msg (nostr/sign sk msg)))))))

;; ~~~~~~~~~~~~~~~~~~~ NIP-01 serialization ~~~~~~~~~~~~~~~~~~~

(deftest canonical-serialization-escapes-exactly-the-nip01-set
  (testing "only \\n \\\" \\\\ \\r \\t \\b \\f are escaped"
    (is (= "a\\nb" (nostr/json-escape "a\nb")))
    (is (= "a\\\"b" (nostr/json-escape "a\"b")))
    (is (= "a\\\\b" (nostr/json-escape "a\\b")))
    (is (= "a\\rb" (nostr/json-escape "a\rb")))
    (is (= "a\\tb" (nostr/json-escape "a\tb"))))
  (testing "characters a general JSON encoder would escape are left verbatim"
    (is (= "https://tardbox.com/boost/01K9" (nostr/json-escape "https://tardbox.com/boost/01K9"))
        "forward slashes must not be escaped")
    (is (= "boost 🚀 ünïcode" (nostr/json-escape "boost 🚀 ünïcode"))
        "non-ASCII must be emitted verbatim, not \\u-escaped")))

(deftest canonical-event-shape
  (is (= (str "[0,\"" (apply str (repeat 64 "b")) "\",1700000000,1,"
              "[[\"i\",\"podcast:guid:abc\"],[\"k\",\"podcast:guid\"]],"
              "\"hello\"]")
         (nostr/canonical-event
          {:pubkey (apply str (repeat 64 "b"))
           :created-at 1700000000
           :kind 1
           :tags [["i" "podcast:guid:abc"] ["k" "podcast:guid"]]
           :content "hello"}))))

(deftest event-id-changes-with-every-field
  (let [base {:pubkey (apply str (repeat 64 "b"))
              :created-at 1700000000
              :kind 1
              :tags [["t" "boostagram"]]
              :content "hello"}
        id (nostr/event-id base)]
    (doseq [[k v] {:created-at 1700000001
                   :kind 1111
                   :tags [["t" "boost"]]
                   :content "hello!"}]
      (is (not= id (nostr/event-id (assoc base k v)))
          (str "id must depend on " k)))))

(deftest sign-event-round-trip
  (let [sk (nostr/hex->bytes (apply str (repeat 64 "7")))
        event (nostr/sign-event sk {:kind 1
                                    :content "boost 🚀 with \"quotes\" and\na newline"
                                    :tags [["i" "podcast:guid:c90e609a-df1e-596a-bd5e-57bcc8aad6cc"]
                                           ["k" "podcast:guid"]]})]
    (is (= 64 (count (:id event))))
    (is (= 128 (count (:sig event))))
    (is (= (nostr/bytes->hex (nostr/x-only-pubkey sk)) (:pubkey event)))
    (is (nostr/verify-event? event) "signature verifies against the recomputed id")
    (testing "tampering is detected"
      (is (not (nostr/verify-event? (assoc event :content "tampered"))))
      (is (not (nostr/verify-event? (assoc event :tags [])))))
    (testing "wire JSON embeds the signed id"
      (is (str/includes? (nostr/event->json event) (str "\"id\":\"" (:id event) "\""))))))

;; ~~~~~~~~~~~~~~~~~~~ bech32 / NIP-19 ~~~~~~~~~~~~~~~~~~~

(deftest bech32-reference-strings
  (testing "BIP-173 valid strings decode"
    (doseq [s ["A12UEL5L"
               "a12uel5l"
               "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw"
               "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j"]]
      (is (map? (nostr/decode-bech32 s)) s)))
  (testing "corrupted checksums are rejected"
    (is (thrown? Exception (nostr/decode-bech32 "A12UEL5X")))
    (is (thrown? Exception (nostr/decode-bech32 "a12uel5l1")))
    (is (thrown? Exception (nostr/decode-bech32 "abc")))
    (is (thrown? Exception (nostr/decode-bech32 "A12UeL5L")) "mixed case")))

(deftest npub-round-trip
  (let [sk (nostr/hex->bytes (apply str (repeat 64 "5")))
        pk (nostr/x-only-pubkey sk)
        npub (nostr/->npub pk)]
    (is (str/starts-with? npub "npub1"))
    (is (= 63 (count npub)))
    (is (= (nostr/bytes->hex pk) (nostr/bytes->hex (:data (nostr/decode-bech32 npub)))))
    (testing "decode-key accepts both hex and bech32"
      (is (= (nostr/bytes->hex pk) (nostr/bytes->hex (nostr/decode-key npub))))
      (is (= (nostr/bytes->hex pk) (nostr/bytes->hex (nostr/decode-key (nostr/bytes->hex pk))))))))

(deftest hex-round-trip
  (let [bs (byte-array (map unchecked-byte (range -128 128)))]
    (is (= (seq bs) (seq (nostr/hex->bytes (nostr/bytes->hex bs)))))
    (is (thrown? Exception (nostr/hex->bytes "abc")))
    (is (thrown? Exception (nostr/hex->bytes "zz")))))
