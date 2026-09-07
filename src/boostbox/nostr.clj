(ns boostbox.nostr
  "Pure Nostr primitives: hex, BIP-340 schnorr signatures, NIP-01 canonical
   serialization and event ids, and NIP-19 bech32 key encoding.

   This namespace deliberately depends on nothing but BouncyCastle's secp256k1
   math and the JDK. Two reasons:

   1. NIP-01 mandates an *exact* canonical serialization with a fixed escape
      set (only \\n \\\" \\\\ \\r \\t \\b \\f are escaped, everything else
      verbatim). A general JSON library escapes a different set -- notably
      non-ASCII and forward slashes -- which silently yields a different event
      id and therefore an invalid signature. Hand-writing it is the correct
      implementation, not a shortcut.
   2. Keeping it dependency-free means the signing path can be unit tested
      against the official BIP-340 vectors without standing up the rest of the
      application."
  (:require [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Arrays)
           (org.bouncycastle.crypto.ec CustomNamedCurves)))

;; ~~~~~~~~~~~~~~~~~~~ Bytes & hex ~~~~~~~~~~~~~~~~~~~

(def ^:private hex-chars "0123456789abcdef")

(defn bytes->hex
  "Lowercase hex encoding."
  ^String [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (.append sb (.charAt hex-chars (unsigned-bit-shift-right b 4)))
        (.append sb (.charAt hex-chars (bit-and b 0x0f)))))
    (.toString sb)))

(defn hex->bytes
  "Decode a hex string. Case insensitive. Throws on odd length or non-hex."
  ^bytes [^String s]
  (let [s (str/trim s)
        len (.length s)]
    (when (odd? len)
      (throw (ex-info "hex string must have an even length" {:length len})))
    (let [out (byte-array (quot len 2))]
      (dotimes [i (alength out)]
        (let [hi (Character/digit (.charAt s (* 2 i)) 16)
              lo (Character/digit (.charAt s (inc (* 2 i))) 16)]
          (when (or (neg? hi) (neg? lo))
            (throw (ex-info "invalid hex character" {:index (* 2 i)})))
          (aset-byte out i (unchecked-byte (bit-or (bit-shift-left hi 4) lo)))))
      out)))

(defn- cat-bytes
  ^bytes [& arrays]
  (let [total (reduce + (map #(alength ^bytes %) arrays))
        out (byte-array total)]
    (loop [pos 0 [^bytes a & more] arrays]
      (if a
        (do (System/arraycopy a 0 out pos (alength a))
            (recur (+ pos (alength a)) more))
        out))))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn sha256 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn- bi->32
  "Big-endian 32-byte unsigned encoding, left-padded / sign-byte stripped."
  ^bytes [^BigInteger x]
  (let [out (byte-array 32)
        src (.toByteArray x)
        n (alength src)]
    (if (<= n 32)
      (System/arraycopy src 0 out (- 32 n) n)
      (System/arraycopy src (- n 32) out 0 32))
    out))

(defn- bytes->bi ^BigInteger [^bytes bs] (BigInteger. 1 bs))

;; ~~~~~~~~~~~~~~~~~~~ secp256k1 ~~~~~~~~~~~~~~~~~~~

(def ^:private secp (CustomNamedCurves/getByName "secp256k1"))
(def ^:private curve (.getCurve secp))
(def ^:private G (.getG secp))
(def ^:private N (.getN secp))
(def ^:private P (.getCharacteristic (.getField curve)))
(def ^:private BI-3 (BigInteger/valueOf 3))
(def ^:private BI-7 (BigInteger/valueOf 7))

(defn- even-y? [pt]
  (not (.testBit (.toBigInteger (.getAffineYCoord pt)) 0)))

(defn- lift-x
  "BIP-340 lift_x: the point with x-coordinate x and even y, or nil if x is
   out of range or not on the curve."
  [^BigInteger x]
  (when (and (not (neg? (.signum x))) (neg? (.compareTo x P)))
    (let [c (.mod (.add (.modPow x BI-3 P) BI-7) P)
          y (.modPow c (.divide (.add P BigInteger/ONE) (BigInteger/valueOf 4)) P)]
      ;; modPow always returns something; only a real square root squares back to c
      (when (zero? (.compareTo (.mod (.multiply y y) P) c))
        (let [y (if (.testBit y 0) (.subtract P y) y)]
          (.normalize (.createPoint curve x y)))))))

(defn- tagged-hash
  ^bytes [^String tag ^bytes msg]
  (let [th (sha256 (utf8 tag))]
    (sha256 (cat-bytes th th msg))))

(defn x-only-pubkey
  "The 32-byte x-only public key for a 32-byte secret key (BIP-340)."
  ^bytes [^bytes seckey]
  (let [d' (bytes->bi seckey)]
    (when (or (zero? (.signum d')) (not (neg? (.compareTo d' N))))
      (throw (ex-info "secret key out of range" {})))
    (bi->32 (.toBigInteger (.getAffineXCoord (.normalize (.multiply G d')))))))

(defn verify?
  "BIP-340 verification. 32-byte x-only pubkey, arbitrary-length message,
   64-byte signature."
  [^bytes pubkey ^bytes msg ^bytes sig]
  (try
    (boolean
     (when (and (= 32 (alength pubkey)) (= 64 (alength sig)))
       (let [pt (lift-x (bytes->bi pubkey))
             r (bytes->bi (Arrays/copyOfRange sig 0 32))
             s (bytes->bi (Arrays/copyOfRange sig 32 64))]
         (when (and pt (neg? (.compareTo r P)) (neg? (.compareTo s N)))
           (let [e (.mod (bytes->bi (tagged-hash "BIP0340/challenge"
                                                 (cat-bytes (Arrays/copyOfRange sig 0 32)
                                                            pubkey msg)))
                         N)
                 R (.normalize (.subtract (.multiply G s) (.multiply pt e)))]
             (and (not (.isInfinity R))
                  (even-y? R)
                  (zero? (.compareTo (.toBigInteger (.getAffineXCoord R)) r))))))))
    (catch Exception _ false)))

(defn sign
  "BIP-340 signing. Returns a 64-byte signature.

   aux-rand must be 32 bytes; it is omitted only by the test vectors, which
   supply their own. The result is verified before being returned, as the
   spec recommends, so a fault cannot silently produce a bad signature."
  (^bytes [^bytes seckey ^bytes msg]
   (let [aux (byte-array 32)]
     (.nextBytes (SecureRandom.) aux)
     (sign seckey msg aux)))
  (^bytes [^bytes seckey ^bytes msg ^bytes aux-rand]
   (when-not (= 32 (alength seckey))
     (throw (ex-info "secret key must be 32 bytes" {:length (alength seckey)})))
   (when-not (= 32 (alength aux-rand))
     (throw (ex-info "aux-rand must be 32 bytes" {:length (alength aux-rand)})))
   (let [d' (bytes->bi seckey)]
     (when (or (zero? (.signum d')) (not (neg? (.compareTo d' N))))
       (throw (ex-info "secret key out of range" {})))
     (let [pt (.normalize (.multiply G d'))
           d (if (even-y? pt) d' (.subtract N d'))
           px (bi->32 (.toBigInteger (.getAffineXCoord pt)))
           t (bi->32 (.xor d (bytes->bi (tagged-hash "BIP0340/aux" aux-rand))))
           rand' (tagged-hash "BIP0340/nonce" (cat-bytes t px msg))
           k' (.mod (bytes->bi rand') N)]
       (when (zero? (.signum k'))
         (throw (ex-info "nonce was zero; retry with different aux-rand" {})))
       (let [R (.normalize (.multiply G k'))
             k (if (even-y? R) k' (.subtract N k'))
             rx (bi->32 (.toBigInteger (.getAffineXCoord R)))
             e (.mod (bytes->bi (tagged-hash "BIP0340/challenge"
                                             (cat-bytes rx px msg)))
                     N)
             sig (cat-bytes rx (bi->32 (.mod (.add k (.multiply e d)) N)))]
         (when-not (verify? px msg sig)
           (throw (ex-info "produced signature failed verification" {})))
         sig)))))

;; ~~~~~~~~~~~~~~~~~~~ NIP-01 serialization ~~~~~~~~~~~~~~~~~~~

(defn json-escape
  "Escape a string per NIP-01. Only these seven characters are escaped; every
   other character -- including non-ASCII -- is emitted verbatim. Escaping
   more than this changes the event id."
  ^String [^String s]
  (let [sb (StringBuilder. (+ 2 (.length s)))]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \newline (.append sb "\\n")
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          \backspace (.append sb "\\b")
          \formfeed (.append sb "\\f")
          (.append sb c))))
    (.toString sb)))

(defn- json-string ^String [^String s]
  (str "\"" (json-escape s) "\""))

(defn- json-string-array ^String [xs]
  (str "[" (str/join "," (map json-string xs)) "]"))

(defn json-object
  "Minimal JSON object serializer for flat string->string maps. Used for the
   kind:0 profile content, which Nostr carries as a JSON string inside the
   event. Entries with nil or blank values are dropped."
  ^String [m]
  (str "{"
       (str/join ","
                 (for [[k v] m
                       :when (and (some? v) (not (str/blank? (str v))))]
                   (str (json-string (name k)) ":" (json-string (str v)))))
       "}"))

(defn canonical-event
  "The NIP-01 serialization whose sha256 is the event id:
   [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]"
  ^String [{:keys [pubkey created-at kind tags content]}]
  (str "[0,"
       (json-string pubkey) ","
       (long created-at) ","
       (long kind) ","
       "[" (str/join "," (map json-string-array tags)) "],"
       (json-string (or content ""))
       "]"))

(defn event-id
  "Lowercase hex event id."
  ^String [event]
  (bytes->hex (sha256 (utf8 (canonical-event event)))))

(defn sign-event
  "Take an unsigned event {:kind :content :tags :created-at?} plus a 32-byte
   secret key, and return it completed with :pubkey, :id and :sig."
  [^bytes seckey {:keys [kind content tags created-at]}]
  (let [pubkey (bytes->hex (x-only-pubkey seckey))
        event {:pubkey pubkey
               :created-at (or created-at (quot (System/currentTimeMillis) 1000))
               :kind kind
               :tags (or tags [])
               :content (or content "")}
        id (event-id event)]
    (assoc event
           :id id
           :sig (bytes->hex (sign seckey (hex->bytes id))))))

(defn event->json
  "Serialize a signed event as the wire JSON object."
  ^String [{:keys [id pubkey created-at kind tags content sig]}]
  (str "{"
       "\"id\":" (json-string id) ","
       "\"pubkey\":" (json-string pubkey) ","
       "\"created_at\":" (long created-at) ","
       "\"kind\":" (long kind) ","
       "\"tags\":[" (str/join "," (map json-string-array tags)) "],"
       "\"content\":" (json-string (or content "")) ","
       "\"sig\":" (json-string sig)
       "}"))

(defn verify-event?
  "Recompute the id and check the signature. Used in tests and as a
   belt-and-braces check before publishing."
  [{:keys [id pubkey sig] :as event}]
  (try
    (and (= id (event-id event))
         (verify? (hex->bytes pubkey) (hex->bytes id) (hex->bytes sig)))
    (catch Exception _ false)))

;; ~~~~~~~~~~~~~~~~~~~ NIP-19 bech32 ~~~~~~~~~~~~~~~~~~~
;;
;; A direct port of the BIP-173 reference implementation. The accumulator is
;; masked on every step: without it a 32-byte payload overflows a long well
;; before the end of the conversion.

(def ^:private bech32-charset "qpzry9x8gf2tvdw0s3jn54khce6mua7l")
(def ^:private bech32-gen [0x3b6a57b2 0x26508e6d 0x1ea119fa 0x3d4233dd 0x2a1462b3])

(defn- bech32-polymod [values]
  (reduce (fn [chk v]
            (let [b (bit-shift-right chk 25)
                  chk (bit-xor (bit-shift-left (bit-and chk 0x1ffffff) 5) v)]
              (reduce (fn [c i]
                        (if (bit-test b i) (bit-xor c (nth bech32-gen i)) c))
                      chk
                      (range 5))))
          1
          values))

(defn- hrp-expand [^String hrp]
  (concat (map #(bit-shift-right (int %) 5) hrp)
          [0]
          (map #(bit-and (int %) 31) hrp)))

(defn- convert-bits
  "Regroup a sequence of `from`-bit values into `to`-bit values."
  [values from to pad?]
  (let [maxv (dec (bit-shift-left 1 to))
        acc-max (dec (bit-shift-left 1 (dec (+ from to))))
        [acc bits out]
        (reduce
         (fn [[acc bits out] v]
           (when (or (neg? v) (not (zero? (bit-shift-right v from))))
             (throw (ex-info "value out of range for bech32 conversion" {:value v})))
           (loop [acc (bit-and (bit-or (bit-shift-left acc from) v) acc-max)
                  bits (+ bits from)
                  out out]
             (if (>= bits to)
               (let [bits (- bits to)]
                 (recur acc bits (conj out (bit-and (bit-shift-right acc bits) maxv))))
               [acc bits out])))
         [0 0 []]
         values)]
    (cond
      pad?
      (if (pos? bits)
        (conj out (bit-and (bit-shift-left acc (- to bits)) maxv))
        out)

      (or (>= bits from)
          (not (zero? (bit-and (bit-shift-left acc (- to bits)) maxv))))
      (throw (ex-info "invalid padding in bech32 data" {}))

      :else out)))

(defn decode-bech32
  "Decode a bech32 string into {:hrp :data}, where :data is the 8-bit payload.
   Verifies the checksum and rejects mixed case."
  [^String s]
  (let [s (str/trim s)
        lower (str/lower-case s)]
    (when-not (or (= s lower) (= s (str/upper-case s)))
      (throw (ex-info "bech32 string has mixed case" {})))
    (let [pos (str/last-index-of lower "1")]
      (when (or (nil? pos) (zero? pos) (> (+ pos 7) (count lower)))
        (throw (ex-info "malformed bech32 string" {})))
      (let [hrp (subs lower 0 pos)
            data (mapv (fn [c]
                         (or (str/index-of bech32-charset (str c))
                             (throw (ex-info "invalid bech32 character" {:char c}))))
                       (subs lower (inc pos)))]
        (when-not (= 1 (bech32-polymod (concat (hrp-expand hrp) data)))
          (throw (ex-info "bech32 checksum mismatch" {})))
        {:hrp hrp
         :data (byte-array (map unchecked-byte
                                (convert-bits (drop-last 6 data) 5 8 false)))}))))

(defn- bech32-checksum [^String hrp data]
  (let [polymod (bit-xor (bech32-polymod (concat (hrp-expand hrp) data [0 0 0 0 0 0])) 1)]
    (map #(bit-and (bit-shift-right polymod (* 5 (- 5 %))) 31) (range 6))))

(defn encode-bech32
  "Encode an 8-bit payload as bech32 under the given human-readable part."
  ^String [^String hrp ^bytes payload]
  (let [data (convert-bits (map #(bit-and % 0xff) (seq payload)) 8 5 true)]
    (str hrp "1"
         (apply str (map #(.charAt bech32-charset %)
                         (concat data (bech32-checksum hrp data)))))))

(defn decode-key
  "Accept either 64-char hex or a bech32 nsec/npub and return 32 raw bytes, so
   operators can paste whichever form their tooling handed them."
  ^bytes [^String s]
  (let [s (str/trim s)]
    (if (or (str/starts-with? s "nsec1") (str/starts-with? s "npub1"))
      (let [{:keys [data]} (decode-bech32 s)]
        (when-not (= 32 (alength ^bytes data))
          (throw (ex-info "bech32 key payload must be 32 bytes"
                          {:length (alength ^bytes data)})))
        data)
      (let [bs (hex->bytes s)]
        (when-not (= 32 (alength bs))
          (throw (ex-info "key must be 32 bytes" {:length (alength bs)})))
        bs))))

(defn ->npub ^String [^bytes pubkey] (encode-bech32 "npub" pubkey))
