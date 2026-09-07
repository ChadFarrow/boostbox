(ns boostbox.nwc
  "Nostr Wallet Connect (NIP-47) client -- enough of it to watch a wallet for
   incoming payments and read their boostagram metadata.

   NWC is itself Nostr: requests and responses are events on a relay, encrypted
   to the wallet with NIP-04. That is why this bot needs no inbound webhook and
   no open port, and why the signing stack it already needs to publish notes is
   the same stack it uses to talk to the wallet."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [manifold.stream :as s]
            [com.brunobonacci.mulog :as u]
            [boostbox.nostr :as nostr]
            [boostbox.relay :as relay]
            [boostbox.boostagram :as bg]))

(def request-kind 23194)
(def response-kind 23195)
(def notification-kind 23196) ;; NIP-04 flavour; 23197 is the NIP-44 one

(def default-timeout-ms 30000)

;; ~~~~~~~~~~~~~~~~~~~ Connection URI ~~~~~~~~~~~~~~~~~~~

(defn- url-decode [^String s]
  (java.net.URLDecoder/decode s java.nio.charset.StandardCharsets/UTF_8))

(defn parse-uri
  "Parse nostr+walletconnect://<wallet-pubkey>?relay=<url>&secret=<hex>[&lud16=]

   Parsed by hand rather than via java.net.URI: the '+' in the scheme and the
   64-char hex authority are handled inconsistently by URI parsers."
  [^String uri]
  (let [uri (str/trim uri)
        [_ pubkey query] (re-matches #"(?i)^nostr\+walletconnect://([0-9a-f]{64})\??(.*)$" uri)]
    (when-not pubkey
      (throw (ex-info "malformed NWC connection URI (expected nostr+walletconnect://<64-hex>?...)"
                      {})))
    (let [params (for [pair (str/split (or query "") #"&")
                       :when (str/includes? pair "=")
                       :let [[k v] (str/split pair #"=" 2)]]
                   [(url-decode k) (url-decode v)])
          by-key (group-by first params)
          one (fn [k] (some-> (get by-key k) first second))
          secret (one "secret")
          relays (mapv second (get by-key "relay"))]
      (when (str/blank? secret)
        (throw (ex-info "NWC URI has no secret" {})))
      (when (empty? relays)
        (throw (ex-info "NWC URI names no relay" {})))
      {:wallet-pubkey (str/lower-case pubkey)
       :relays relays
       :secret secret
       :secret-bytes (nostr/decode-key secret)
       :lud16 (one "lud16")})))

;; ~~~~~~~~~~~~~~~~~~~ Session ~~~~~~~~~~~~~~~~~~~

(defn open!
  "Connect to the wallet's relay. The caller owns the session and must close!
   it. Kept explicit rather than per-call so the notification subscription can
   live on the same socket."
  [{:keys [relays] :as nwc}]
  (let [url (first relays)]
    {:nwc nwc
     :relay url
     :conn (relay/connect! url)}))

(defn close! [{:keys [conn]}]
  (when conn (s/close! conn)))

(defn- encrypt-for-wallet [{:keys [secret-bytes wallet-pubkey]} payload]
  (nostr/nip04-encrypt secret-bytes (nostr/hex->bytes wallet-pubkey)
                       (json/write-value-as-string payload)))

(defn- decrypt-from-wallet [{:keys [secret-bytes wallet-pubkey]} content]
  (json/read-value
   (nostr/nip04-decrypt secret-bytes (nostr/hex->bytes wallet-pubkey) content)))

(defn call!
  "Make an NWC request and return its `result` map. Throws on a wallet-reported
   error or a timeout."
  ([session method params] (call! session method params default-timeout-ms))
  ([{:keys [conn nwc]} method params timeout-ms]
   (let [{:keys [wallet-pubkey secret-bytes]} nwc
         req (nostr/sign-event secret-bytes
                               {:kind request-kind
                                :tags [["p" wallet-pubkey]]
                                :content (encrypt-for-wallet
                                          nwc {:method method :params (or params {})})})
         sub-id (str "nwc-" (subs (:id req) 0 16))]
     (u/log ::nwc-request :method method)
     @(relay/send-json! conn ["REQ" sub-id {"kinds" [response-kind]
                                            "authors" [wallet-pubkey]
                                            "#e" [(:id req)]}])
     (try
       @(s/put! conn (str "[\"EVENT\"," (nostr/event->json req) "]"))
       (let [msg (relay/await-message
                  conn
                  (fn [m] (and (vector? m)
                               (= "EVENT" (first m))
                               (= sub-id (second m))
                               (= response-kind (get (nth m 2 nil) "kind"))))
                  timeout-ms)]
         (when-not msg
           (throw (ex-info "NWC request timed out" {:method method :timeout-ms timeout-ms})))
         (let [body (decrypt-from-wallet nwc (get (nth msg 2) "content"))]
           (when-let [err (get body "error")]
             (throw (ex-info (str "NWC error: " (get err "message"))
                             {:method method :code (get err "code")})))
           (get body "result")))
       (finally
         @(relay/send-json! conn ["CLOSE" sub-id]))))))

(defn get-info! [session]
  (call! session "get_info" {}))

(defn list-transactions!
  "Incoming transactions, newest first. `from` is epoch seconds (exclusive-ish;
   the wallet decides) and is the cursor that keeps this cheap."
  [session {:keys [from limit] :or {limit 50}}]
  (let [params (cond-> {:type "incoming" :limit limit :unpaid false}
                 from (assoc :from from))]
    (get (call! session "list_transactions" params) "transactions")))

(defn subscribe-notifications!
  "Subscribe to the wallet's live notifications. Returns the subscription id.

   Notifications are best-effort -- they are lost across reconnects and are not
   replayed -- so this is an latency optimisation layered on top of polling,
   never the only delivery path."
  [{:keys [conn nwc]}]
  (let [{:keys [wallet-pubkey secret-bytes]} nwc
        sub-id "nwc-notifications"]
    @(relay/send-json! conn ["REQ" sub-id
                             {"kinds" [notification-kind]
                              "authors" [wallet-pubkey]
                              "#p" [(nostr/bytes->hex (nostr/x-only-pubkey secret-bytes))]
                              "since" (quot (System/currentTimeMillis) 1000)}])
    (u/log ::nwc-notifications-subscribed)
    sub-id))

(defn next-payment-received
  "Wait up to timeout-ms for a payment_received notification. Returns the
   transaction map, or nil."
  [{:keys [conn nwc]} timeout-ms]
  (when-let [msg (relay/await-message
                  conn
                  (fn [m] (and (vector? m)
                               (= "EVENT" (first m))
                               (= notification-kind (get (nth m 2 nil) "kind"))))
                  timeout-ms)]
    (try
      (let [body (decrypt-from-wallet nwc (get (nth msg 2) "content"))]
        (when (= "payment_received" (get body "notification_type"))
          (get body "notification")))
      (catch Exception e
        (u/log ::nwc-notification-undecryptable :error (ex-message e))
        nil))))

;; ~~~~~~~~~~~~~~~~~~~ Boostagram extraction ~~~~~~~~~~~~~~~~~~~

(defn- ->long [v]
  (cond (number? v) (long v)
        (string? v) (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
        :else nil))

(defn decode-tlv-value
  "TLV record values are hex in every implementation checked, but the NWC
   transaction extension is not part of core NIP-47 and the encoding is not
   pinned by a spec, so fall back to base64 rather than dropping a boost."
  [^String v]
  (or (try (bg/tlv-hex->string v) (catch Exception _ nil))
      (try (String. (.decode (java.util.Base64/getDecoder) v) "UTF-8")
           (catch Exception _ nil))))

(defn extract-boostagram
  "Pull the blip-10 boostagram out of a transaction's metadata.

   The raw TLV record is preferred and the wallet's own pre-parsed object is
   only a fallback: Alby Hub's Boostagram struct keeps feedID/itemID but drops
   every GUID, and without the feed GUID the note cannot be tagged to the
   podcast under NIP-73. Returns a normalized map, or nil for an ordinary
   payment that carries no boostagram."
  [tx]
  (let [md (get tx "metadata")
        raw (some (fn [r]
                    (when (= bg/boostagram-tlv-type (->long (get r "type")))
                      (get r "value")))
                  (get md "tlv_records"))]
    (or (when raw
          (try
            (some-> (decode-tlv-value raw) (json/read-value) (bg/normalize))
            (catch Exception e
              (u/log ::boostagram-tlv-unparseable :error (ex-message e))
              nil)))
        (when-let [parsed (get md "boostagram")]
          (u/log ::boostagram-from-wallet-fallback
                 :note "GUIDs unavailable; note will not carry NIP-73 tags")
          (bg/normalize parsed)))))

(defn transaction->boost
  "Combine a transaction and its boostagram into everything downstream needs,
   or nil if this payment is not a republishable boost."
  [tx]
  (when-let [b (extract-boostagram tx)]
    (when (bg/boost? b)
      {:payment-hash (get tx "payment_hash")
       :boostagram b
       :received-msat (->long (get tx "amount"))
       :settled-at (->long (get tx "settled_at"))})))
