(ns boostbox.nostrbot
  "The boost bot: watch an Alby Hub sub-wallet over NWC, store each incoming
   boostagram in BoostBox, and republish it to Nostr with NIP-73 tags.

   Runs as its own process from the same uberjar as the web app:

     java -cp boostbox.jar boostbox.nostrbot

   It is deliberately not a route on the web server. It holds a signing key and
   a wallet credential, it is a long-lived loop with at-least-once delivery, and
   the web app has neither of those concerns."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [babashka.http-client :as http]
            [com.brunobonacci.mulog :as u]
            [boostbox.boostbox :as bb]
            [boostbox.boostagram :as bg]
            [boostbox.nostr :as nostr]
            [boostbox.nwc :as nwc]
            [boostbox.relay :as relay]))

(def default-relays
  "The same three the homepage's client-side npub resolver already uses."
  "wss://relay.damus.io,wss://nos.lol,wss://relay.primal.net")

(def max-recent
  "How many payment hashes to remember for de-duplication."
  500)

;; ~~~~~~~~~~~~~~~~~~~ Config ~~~~~~~~~~~~~~~~~~~

(defn- truthy? [s]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str/trim (str s)))))

(defn- csv [s]
  (->> (str/split (str s) #",") (map str/trim) (remove str/blank?) vec))

(defn config
  "BBN_-prefixed so nothing here can collide with the web app's BB_ vars.
   Storage config is shared with the web app and read via bb/config."
  []
  (let [bb-cfg (bb/config)
        nwc-uri (bb/get-env "BBN_NWC_URI")
        nwc (nwc/parse-uri nwc-uri)
        seckey (nostr/decode-key (bb/get-env "BBN_NOSTR_SECKEY") "nsec")]
    {:bb-cfg bb-cfg
     :nwc nwc
     :seckey seckey
     :pubkey (nostr/bytes->hex (nostr/x-only-pubkey seckey))
     :npub (nostr/->npub (nostr/x-only-pubkey seckey))
     :relays (csv (bb/get-env "BBN_RELAYS" default-relays))
     :boostbox-url (str/replace (bb/get-env "BBN_BOOSTBOX_URL" "https://tardbox.com")
                                #"/+$" "")
     :boostbox-api-key (bb/get-env "BBN_BOOSTBOX_API_KEY")
     ;; Origins the bot will fetch a boost link from. Empty means "any https
     ;; origin, subject to the address check at fetch time" -- the default,
     ;; because apps POST to whichever BoostBox they run and a podcaster cannot
     ;; enumerate those in advance. Naming origins here locks the bot down to
     ;; those plus our own.
     :boost-link-origins (let [named (csv (bb/get-env "BBN_BOOST_LINK_ORIGINS" ""))]
                           (when (seq named)
                             (into [(str/replace (bb/get-env "BBN_BOOSTBOX_URL" "https://tardbox.com")
                                                 #"/+$" "")]
                                   named)))
     :poll-interval-ms (* 1000 (Long/parseLong (bb/get-env "BBN_POLL_INTERVAL_SEC" "60")))
     :min-sats (Long/parseLong (bb/get-env "BBN_MIN_SATS" "0"))
     ;; how far back to reach on the very first run; 0 means "start from now"
     :backfill-sec (Long/parseLong (bb/get-env "BBN_BACKFILL_SEC" "0"))
     :dry-run? (truthy? (bb/get-env "BBN_DRY_RUN" "false"))
     :state-key (bb/get-env "BBN_STATE_KEY" "nostrbot/state.json")
     :publish-profile? (truthy? (bb/get-env "BBN_PUBLISH_PROFILE" "false"))
     :profile {:name (bb/get-env "BBN_PROFILE_NAME" nil)
               :display_name (bb/get-env "BBN_PROFILE_NAME" nil)
               :about (bb/get-env "BBN_PROFILE_ABOUT" nil)
               :picture (bb/get-env "BBN_PROFILE_PICTURE" nil)
               :nip05 (bb/get-env "BBN_PROFILE_NIP05" nil)
               ;; default the lightning address to the sub-wallet the NWC
               ;; connection already points at, so the bot is boostable back
               :lud16 (or (bb/get-env "BBN_PROFILE_LUD16" nil) (:lud16 nwc))}}))

;; ~~~~~~~~~~~~~~~~~~~ State ~~~~~~~~~~~~~~~~~~~
;;
;; The bot needs a cursor and a de-duplication set at an arbitrary key.
;; IStorage cannot hold that -- its keys are derived from a ULID -- so this
;; uses the same underlying FS/S3 config directly and leaves the web app's
;; storage path untouched.

(defn check-state-durability!
  "The cursor and de-duplication set are the bot's only memory. On FS storage
   they live on the container's own filesystem, and the deployment the README
   describes -- a second Railway service -- gets a fresh one on every restart.
   Losing them is silent and lossy: poll-once! takes its first-run branch, sets
   the watermark to `now`, and every boost that arrived while the bot was down
   is dropped, unpublished, with nothing in the logs to say so.

   Set BBN_ALLOW_EPHEMERAL_STATE=1 if the filesystem really is a mounted
   volume."
  [{:keys [bb-cfg]}]
  (when (and (= "FS" (:storage bb-cfg))
             (not= "DEV" (:env bb-cfg))
             (not (truthy? (bb/get-env "BBN_ALLOW_EPHEMERAL_STATE" "false"))))
    (throw (ex-info (str "BB_STORAGE=FS gives the bot no durable cursor: on a restart the "
                         "watermark resets to now and every boost received while the bot "
                         "was down is dropped. Set BB_STORAGE=S3, or set "
                         "BBN_ALLOW_EPHEMERAL_STATE=1 if this really is a persistent volume.")
                    {:storage "FS" :root-path (:root-path bb-cfg)}))))

(defn- state-io [{:keys [bb-cfg state-key]}]
  (case (:storage bb-cfg)
    "FS" (let [f (io/file (:root-path bb-cfg) state-key)]
           {:read #(when (.exists f) (json/read-value f))
            :write #(do (-> f .getParentFile .mkdirs)
                        (json/write-value f %))})
    "S3" (let [{:keys [access-key secret-key region endpoint bucket]} bb-cfg
               client (bb/s3-client access-key secret-key region endpoint)]
           {:read #(let [resp (bb/s3-get client bucket state-key)]
                     (when-not (contains? resp :cognitect.anomalies/category)
                       (json/read-value (:Body resp))))
            :write #(bb/s3-put client bucket state-key "application/json"
                               (json/write-value-as-string %))})))

(defn- load-state [{:keys [read]}]
  (or (try (read) (catch Exception e
                    (u/log ::state-load-failed :error (ex-message e))
                    nil))
      {"cursor" nil "recent" []}))

(defn- save-state! [{:keys [write]} state]
  (write state))

(defn- seen-index [state]
  (into {} (map (juxt #(get % "payment_hash") identity)) (get state "recent" [])))

(defn- remember [state entry]
  (let [h (get entry "payment_hash")
        recent (vec (remove #(= h (get % "payment_hash")) (get state "recent" [])))]
    (assoc state "recent" (vec (take-last max-recent (conj recent entry))))))

;; ~~~~~~~~~~~~~~~~~~~ BoostBox ~~~~~~~~~~~~~~~~~~~

(defn store-boost!
  "POST the boost to BoostBox and return {:id :url :desc}."
  [{:keys [boostbox-url boostbox-api-key]} payload]
  (let [resp (http/post (str boostbox-url "/boost")
                        {:headers {"x-api-key" boostbox-api-key
                                   "content-type" "application/json"}
                         :body (json/write-value-as-string payload)
                         :throw false})]
    (if (= 201 (:status resp))
      (let [body (json/read-value (:body resp))]
        {:id (get body "id") :url (get body "url") :desc (get body "desc")})
      (throw (ex-info "BoostBox rejected the boost"
                      {:status (:status resp) :body (:body resp)})))))

;; ~~~~~~~~~~~~~~~~~~~ Boost links ~~~~~~~~~~~~~~~~~~~

(def boost-link-timeout-ms 10000)

(defn- reserved-address?
  "Addresses the bot must never be talked into contacting."
  [^java.net.InetAddress a]
  (let [bs (map #(bit-and % 0xff) (.getAddress a))
        [b0 b1] bs]
    (or (.isAnyLocalAddress a)                 ; 0.0.0.0, ::
        (.isLoopbackAddress a)                 ; 127/8, ::1
        (.isLinkLocalAddress a)                ; 169.254/16, fe80::/10
        (.isSiteLocalAddress a)                ; 10/8, 172.16/12, 192.168/16
        (.isMulticastAddress a)
        (and (= 4 (count bs))
             (or (and (= 100 b0) (<= 64 b1 127))   ; CGNAT 100.64/10
                 (>= b0 240)))                     ; reserved 240/4
        (and (= 16 (count bs))
             (= 0xfc (bit-and b0 0xfe))))))        ; IPv6 ULA fc00::/7

(defn- public-addresses
  "Every address `host` resolves to, or nil if any one of them is an address
   the bot must not contact.

   All-or-nothing on purpose: a name answering with both a public and a private
   address is not a misconfiguration to be worked around, it is the shape of an
   attack."
  [^String host]
  (let [addrs (seq (java.net.InetAddress/getAllByName host))]
    (when (and addrs (not-any? reserved-address? addrs))
      addrs)))

(defn fetchable-url?
  "Whether a boost link is safe to request.

   bg/boost-link decides the shape of a URL; this decides its destination, and
   it is the half that matters. The link comes out of a payment description
   written by whoever paid us, so an unguarded fetch would let any payer aim
   the bot's poll loop at whatever it can reach -- a cloud metadata endpoint,
   something on the deploy's private network."
  [^String url]
  (try
    (let [uri (java.net.URI. url)
          host (.getHost uri)]
      (boolean (and host
                    (= "https" (str/lower-case (str (.getScheme uri))))
                    (public-addresses host))))
    (catch Exception _ false)))

(def ^:private max-header-bytes
  "A HEAD has no body, so the only thing a hostile server can send us is
   headers. Stop reading rather than let it send them forever."
  65536)

(defn- head-pinned!
  "HEAD `url` over a socket opened directly to `addr` -- the address already
   checked by public-addresses.

   Connecting to a verified address rather than a name is the whole point.
   An ordinary client resolves the host itself, so checking a name and then
   handing it to the client leaves a window in which DNS can answer differently
   the second time, and the request lands inside the network the check existed
   to keep it out of. One lookup, one address, no window.

   Pinning the address does not weaken TLS: SNI is set explicitly and endpoint
   identification stays on, so the certificate is still validated against the
   hostname. Only the status line and headers are read."
  [^String url ^java.net.InetAddress addr timeout-ms]
  (let [uri (java.net.URI. url)
        host (.getHost uri)
        port (if (pos? (.getPort uri)) (.getPort uri) 443)
        path (let [p (.getRawPath uri)
                   q (.getRawQuery uri)]
               (str (if (str/blank? p) "/" p) (when q (str "?" q))))
        ^javax.net.ssl.SSLSocketFactory factory (javax.net.ssl.SSLSocketFactory/getDefault)
        ^javax.net.ssl.SSLSocket sock (.createSocket factory)]
    (try
      (.connect sock (java.net.InetSocketAddress. addr (int port)) (int timeout-ms))
      (.setSoTimeout sock (int timeout-ms))
      (let [params (.getSSLParameters sock)]
        (.setServerNames params [(javax.net.ssl.SNIHostName. host)])
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (.setSSLParameters sock params))
      (.startHandshake sock)
      (doto (.getOutputStream sock)
        (.write (.getBytes (str "HEAD " path " HTTP/1.1\r\n"
                                "Host: " host "\r\n"
                                "User-Agent: boostbox-bot\r\n"
                                "Accept: */*\r\n"
                                "Connection: close\r\n\r\n")
                           "UTF-8"))
        (.flush))
      (let [rdr (java.io.BufferedReader.
                 (java.io.InputStreamReader. (.getInputStream sock) "ISO-8859-1"))
            status (some-> (.readLine rdr)
                           (str/split #"\s+")
                           second
                           (as-> s (try (Long/parseLong s) (catch Exception _ nil))))]
        (loop [headers {} budget max-header-bytes]
          (let [line (.readLine rdr)]
            (if (or (nil? line) (str/blank? line) (neg? budget))
              {:status status :headers headers}
              (let [i (str/index-of line ":")]
                (recur (if i
                         (assoc headers
                                (str/lower-case (str/trim (subs line 0 i)))
                                (str/trim (subs line (inc i))))
                         headers)
                       (- budget (count line))))))))
      (finally
        (try (.close sock) (catch Exception _ nil))))))

(defn fetch-boost-metadata!
  "Read a boost back out of a BoostBox permalink's x-rss-payment header.

   This is how an LNURL payment carries its boostagram: there is no TLV to put
   one in, so the app POSTs the metadata to BoostBox first and puts the
   resulting permalink in the BOLT11 description. Returns the decoded map, or
   nil if the URL does not answer like a BoostBox.

   HEAD, not GET: the header is the whole payload, so there is no reason to
   pull a body of unknown size from a server we do not control. Redirects are
   refused outright rather than followed and re-checked -- a public host that
   answers 302 with a private address is the standard way around an address
   check, and nothing legitimate needs one here. Anything that is not a 200 is
   simply not a boost.

   The host is resolved once and the connection made to that address, so there
   is no second lookup for DNS to answer differently."
  [url]
  (try
    (let [uri (java.net.URI. (str url))
          host (.getHost uri)]
      (when (and host (= "https" (str/lower-case (str (.getScheme uri)))))
        (when-let [addrs (public-addresses host)]
          (let [{:keys [status headers]} (head-pinned! url (first addrs) boost-link-timeout-ms)
                hdr (get headers "x-rss-payment")]
            (when (and (= 200 status) (not (str/blank? hdr)))
              (json/read-value (java.net.URLDecoder/decode ^String hdr "UTF-8")))))))
    (catch Exception e
      (u/log ::boost-link-fetch-failed :url (str url) :error (ex-message e))
      nil)))

(defn tx->boost!
  "A transaction turned into something publishable, from whichever source
   actually carries the metadata.

   The TLV comes first because it is authoritative and free. Only if there is
   none do we look for a boost link, which most Podcasting 2.0 apps use for
   LNURL payments -- the paths are mutually exclusive in practice, and a TLV
   never needs a network round trip to read."
  [ctx tx]
  (or (nwc/transaction->boost tx)
      (when-let [url (bg/boost-link (get tx "description") (:boost-link-origins ctx))]
        (when-let [b (some-> (fetch-boost-metadata! url) bg/normalize)]
          (when (bg/boost? b)
            (u/log ::boost-from-link :url url)
            {:payment-hash (get tx "payment_hash")
             :boostagram b
             :received-msat (let [a (get tx "amount")] (when (number? a) (long a)))
             :settled-at (let [t (get tx "settled_at")] (when (number? t) (long t)))
             ;; the record already exists at this URL -- publish-boost! must
             ;; reuse it rather than POST a second copy of the same boost
             :boost-url url
             :boost-id (bg/boost-id-from-url url)})))))

;; ~~~~~~~~~~~~~~~~~~~ Publishing ~~~~~~~~~~~~~~~~~~~

(defn build-note
  "The signed kind:1 event for a boost.

   `received-msat` is passed through because plenty of boostagrams omit
   value_msat_total; without it the note headline reads \"0 sats\"."
  [{:keys [seckey]} boostagram {:keys [boost-url received-msat]}]
  (nostr/sign-event seckey
                    {:kind 1
                     :content (bg/->note-content boostagram {:boost-url boost-url
                                                             :received-msat received-msat})
                     :tags (bg/->nip73-tags boostagram {:boost-url boost-url})}))

(defn publish-profile!
  "Publish the bot's own kind:0 metadata, so clients render a name instead of a
   bare hex pubkey. One-shot and opt-in: republishing on every restart would
   spam the relays for no benefit."
  [{:keys [seckey relays profile dry-run?] :as ctx}]
  (let [content (nostr/json-object profile)
        event (nostr/sign-event seckey {:kind 0 :content content :tags []})]
    (if dry-run?
      (u/log ::dry-run-profile :event (nostr/event->json event))
      (let [{:keys [ok? results]} (relay/publish-to-relays! relays event)]
        (u/log ::profile-published :accepted ok? :results results)))
    event))

(defn publish-relay-list!
  "Publish the bot's NIP-65 relay list (kind:10002).

   Without one, a client that finds the bot's npub has no idea where its notes
   live and can only guess from its own relay set -- so a boost published to
   damus and nos.lol is invisible to anyone whose client looks elsewhere. A
   kind:0 alone does not fix that; the relay list is the part that makes an
   npub followable.

   Every relay is marked `write`, which is the honest answer: the bot publishes
   to these and reads nothing from them. Its only inbound channel is the
   wallet's own relay, which is a wallet credential and has no business in a
   public list."
  [{:keys [seckey relays dry-run?]}]
  (let [event (nostr/sign-event seckey
                                {:kind 10002
                                 :content ""
                                 :tags (mapv (fn [r] ["r" r "write"]) relays)})]
    (if dry-run?
      (u/log ::dry-run-relay-list :event (nostr/event->json event))
      (let [{:keys [ok? results]} (relay/publish-to-relays! relays event)]
        (u/log ::relay-list-published :accepted ok? :results results)))
    event))

(defn publish-boost!
  "Store, then publish, then record. Returns the updated state.

   Ordering matters for idempotency: the BoostBox id is written to state before
   the note is published, so a publish failure retries without minting a second
   BoostBox record for the same payment."
  [{:keys [relays dry-run? min-sats] :as ctx} state
   {:keys [payment-hash boostagram received-msat settled-at boost-url boost-id]}]
  (let [seen (get (seen-index state) payment-hash)
        ;; value_msat_total is frequently absent -- Alby's parsed struct drops
        ;; it, and single-recipient splits never set it. Falling back to what
        ;; actually arrived is what ->boost-payload already does; without the
        ;; same fallback here a missing field reads as 0 sats and any non-zero
        ;; BBN_MIN_SATS drops the boost permanently.
        sats (quot (or (:value-msat-total boostagram) received-msat
                       (:value-msat boostagram) 0)
                   1000)]
    (cond
      (get seen "event_id")
      (do (u/log ::boost-already-published :payment-hash payment-hash) state)

      (< sats min-sats)
      (do (u/log ::boost-below-threshold :payment-hash payment-hash :sats sats)
          (remember state {"payment_hash" payment-hash "skipped" "below-threshold"}))

      :else
      (let [stored (cond
                     ;; already stored on an earlier attempt
                     (get seen "url")
                     {:id (get seen "boost_id") :url (get seen "url")}

                     ;; the boostagram came from a boost link, so the record it
                     ;; points at is the boost -- POSTing would mint a second
                     ;; copy of something BoostBox already holds
                     boost-url
                     {:id boost-id :url boost-url}

                     :else
                     (store-boost! ctx (bg/->boost-payload
                                        boostagram
                                        {:received-msat received-msat
                                         :settled-at settled-at})))
            state (remember state {"payment_hash" payment-hash
                                   "boost_id" (:id stored)
                                   "url" (:url stored)})
            _ (save-state! (:state-io ctx) state)
            event (build-note ctx boostagram {:boost-url (:url stored)
                                              :received-msat received-msat})]
        (if dry-run?
          (do (u/log ::dry-run-note :boost-url (:url stored)
                     :event (nostr/event->json event))
              state)
          (let [{:keys [ok? results]} (relay/publish-to-relays! relays event)]
            (when-not ok?
              (throw (ex-info "no relay accepted the note"
                              {:payment-hash payment-hash :results results})))
            (u/log ::boost-published :payment-hash payment-hash
                   :boost-url (:url stored) :event-id (:id event))
            ;; Persist the event_id right here rather than leaving it to the
            ;; caller's end-of-loop save. If a *later* boost in the same window
            ;; fails before its own save-state!, poll-once! reloads from disk to
            ;; recover -- and an unpersisted event_id would make this boost look
            ;; stored-but-unpublished, so the next poll would mint a second note
            ;; for it on the relays.
            (let [state (remember state {"payment_hash" payment-hash
                                         "boost_id" (:id stored)
                                         "url" (:url stored)
                                         "event_id" (:id event)})]
              (save-state! (:state-io ctx) state)
              state)))))))

;; ~~~~~~~~~~~~~~~~~~~ Poll ~~~~~~~~~~~~~~~~~~~

(def transactions-page-size 50)

(def max-transactions-per-poll
  "A runaway guard, not a limit. Pages come back newest-first, so *truncating*
   the walk would keep the newest transactions and drop the oldest -- exactly
   the data loss the paging exists to prevent. Blowing up instead is loud,
   leaves the cursor untouched, and points at the only realistic cause: a
   wallet that ignores `offset` and keeps returning the same page."
  10000)

(defn- tx-settled-at [tx]
  (let [v (get tx "settled_at")]
    (when (number? v) (long v))))

(defn fetch-transactions!
  "Every incoming transaction since the cursor, paged to exhaustion.

   list_transactions caps a response at `limit` and returns newest first, so a
   single call after an outage -- or on any BBN_BACKFILL_SEC backfill -- hands
   back only the newest page. Advancing the cursor past that page would strand
   everything older permanently, unread. Once the cursor is current a poll
   interval rarely holds even one full page; the long walk only happens on a
   deliberate backfill, and it is a one-time cost."
  [session from]
  (loop [offset 0
         acc []]
    (let [page (nwc/list-transactions! session {:from from
                                                :limit transactions-page-size
                                                :offset offset})
          acc (into acc page)]
      (when (> (count acc) max-transactions-per-poll)
        (throw (ex-info "list_transactions paging did not terminate; is the wallet ignoring offset?"
                        {:from from :fetched (count acc)})))
      (if (< (count page) transactions-page-size)
        acc
        (recur (+ offset transactions-page-size) acc)))))

(defn poll-once!
  "Fetch transactions since the cursor and publish any new boosts.

   Processes oldest first and advances the cursor only past boosts that were
   fully published. On the first failure it stops and leaves the cursor where
   it is, so the next pass retries from exactly that point rather than skipping
   the boost. Already-published payments are caught by the de-duplication index
   on the way back through.

   Once the whole window is published the cursor jumps to the newest
   *transaction* seen, not the newest boost: a wallet taking ordinary payments
   would otherwise pin the cursor forever while the paging walk got longer on
   every poll."
  [ctx session]
  (let [state0 (load-state (:state-io ctx))
        ;; First run: start from now rather than from the beginning of the
        ;; wallet's history. Without this the bot's very first poll would
        ;; republish every boost the wallet has ever received, all at once.
        ;; Set BBN_BACKFILL_SEC to deliberately reach back.
        [state cursor]
        (if-let [c (get state0 "cursor")]
          [state0 c]
          (let [c (- (quot (System/currentTimeMillis) 1000) (:backfill-sec ctx 0))
                s (assoc state0 "cursor" c)]
            (u/log ::first-run-watermark :cursor c :backfill-sec (:backfill-sec ctx 0))
            (save-state! (:state-io ctx) s)
            [s c]))
        txs (fetch-transactions! session cursor)
        boosts (->> txs (keep #(tx->boost! ctx %)) (sort-by #(or (:settled-at %) 0)))
        high-water (reduce max 0 (keep tx-settled-at txs))]
    (u/log ::poll :transactions (count txs) :boosts (count boosts) :cursor cursor)
    (loop [state state
           [b & more] boosts]
      (if-not b
        (let [state (cond-> state
                      (> high-water (or (get state "cursor") 0))
                      (assoc "cursor" high-water))]
          (save-state! (:state-io ctx) state)
          state)
        (let [next-state (try
                           (publish-boost! ctx state b)
                           (catch Exception e
                             (u/log ::boost-publish-failed
                                    :payment-hash (:payment-hash b)
                                    :error (ex-message e))
                             ::failed))]
          (if (= ::failed next-state)
            ;; Stop here. Do NOT re-save `state`: publish-boost! has already
            ;; persisted the BoostBox record for this payment, and writing the
            ;; pre-publish state back over it would lose that and mint a
            ;; duplicate record on the retry. The cursor still points before
            ;; this boost, so the next pass picks it up again.
            (load-state (:state-io ctx))
            (recur (cond-> next-state
                     (:settled-at b) (assoc "cursor" (:settled-at b)))
                   more)))))))

;; ~~~~~~~~~~~~~~~~~~~ Main ~~~~~~~~~~~~~~~~~~~

(defn -main [& _]
  (let [cfg (config)
        _ (check-state-durability! cfg)
        logger (u/start-publisher! {:type :console
                                    :pretty? (= "DEV" (:env (:bb-cfg cfg)))})
        ctx (assoc cfg :state-io (state-io cfg))]
    (u/log ::bot-starting
           :npub (:npub cfg)
           :pubkey (:pubkey cfg)
           :relays (:relays cfg)
           :boostbox (:boostbox-url cfg)
           :wallet-relay (first (:relays (:nwc cfg)))
           :dry-run (:dry-run? cfg))
    (println "boost bot identity:" (:npub cfg))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (u/log ::bot-shutting-down)
                                 (Thread/sleep 250)
                                 (logger))))
    (when (:publish-profile? cfg)
      (publish-profile! ctx)
      ;; the relay list goes with the profile: a name without one leaves
      ;; clients unable to find anything the bot has published
      (publish-relay-list! ctx))
    (loop [backoff 1000]
      (let [started (System/currentTimeMillis)
            failed?
            (try
              (let [session (nwc/open! (:nwc ctx))]
                (try
                  (u/log ::nwc-connected :info (nwc/get-info! session))
                  (nwc/subscribe-notifications! session)
                  (loop []
                    (poll-once! ctx session)
                    ;; returns as soon as a payment lands, else after the interval
                    (nwc/next-payment-received session (:poll-interval-ms ctx))
                    (recur))
                  (finally (nwc/close! session))))
              (catch Exception e
                (u/log ::session-failed :error (ex-message e) :retry-in-ms backoff)
                true))]
        (when failed? (Thread/sleep backoff))
        ;; a session that stayed up for a while was healthy; don't carry its
        ;; predecessor's backoff forward into the next transient failure
        (recur (if (> (- (System/currentTimeMillis) started) 60000)
                 1000
                 (min 60000 (* 2 backoff))))))))
