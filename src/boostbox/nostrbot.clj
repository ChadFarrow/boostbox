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
        seckey (nostr/decode-key (bb/get-env "BBN_NOSTR_SECKEY"))]
    {:bb-cfg bb-cfg
     :nwc nwc
     :seckey seckey
     :pubkey (nostr/bytes->hex (nostr/x-only-pubkey seckey))
     :npub (nostr/->npub (nostr/x-only-pubkey seckey))
     :relays (csv (bb/get-env "BBN_RELAYS" default-relays))
     :boostbox-url (str/replace (bb/get-env "BBN_BOOSTBOX_URL" "https://tardbox.com")
                                #"/+$" "")
     :boostbox-api-key (bb/get-env "BBN_BOOSTBOX_API_KEY")
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

;; ~~~~~~~~~~~~~~~~~~~ Publishing ~~~~~~~~~~~~~~~~~~~

(defn build-note
  "The signed kind:1 event for a boost."
  [{:keys [seckey]} boostagram boost-url]
  (nostr/sign-event seckey
                    {:kind 1
                     :content (bg/->note-content boostagram {:boost-url boost-url})
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

(defn publish-boost!
  "Store, then publish, then record. Returns the updated state.

   Ordering matters for idempotency: the BoostBox id is written to state before
   the note is published, so a publish failure retries without minting a second
   BoostBox record for the same payment."
  [{:keys [relays dry-run? min-sats] :as ctx} state
   {:keys [payment-hash boostagram received-msat settled-at]}]
  (let [seen (get (seen-index state) payment-hash)
        sats (quot (or (:value-msat-total boostagram) 0) 1000)]
    (cond
      (get seen "event_id")
      (do (u/log ::boost-already-published :payment-hash payment-hash) state)

      (< sats min-sats)
      (do (u/log ::boost-below-threshold :payment-hash payment-hash :sats sats)
          (remember state {"payment_hash" payment-hash "skipped" "below-threshold"}))

      :else
      (let [stored (if-let [url (get seen "url")]
                     {:id (get seen "boost_id") :url url}
                     (store-boost! ctx (bg/->boost-payload
                                        boostagram
                                        {:received-msat received-msat
                                         :settled-at settled-at})))
            state (remember state {"payment_hash" payment-hash
                                   "boost_id" (:id stored)
                                   "url" (:url stored)})
            _ (save-state! (:state-io ctx) state)
            event (build-note ctx boostagram (:url stored))]
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
            (remember state {"payment_hash" payment-hash
                             "boost_id" (:id stored)
                             "url" (:url stored)
                             "event_id" (:id event)})))))))

;; ~~~~~~~~~~~~~~~~~~~ Poll ~~~~~~~~~~~~~~~~~~~

(defn poll-once!
  "Fetch transactions since the cursor and publish any new boosts.

   Processes oldest first and advances the cursor only past boosts that were
   fully published. On the first failure it stops and leaves the cursor where
   it is, so the next pass retries from exactly that point rather than skipping
   the boost. Already-published payments are caught by the de-duplication index
   on the way back through."
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
        txs (nwc/list-transactions! session {:from cursor})
        boosts (->> txs (keep nwc/transaction->boost) (sort-by #(or (:settled-at %) 0)))]
    (u/log ::poll :transactions (count txs) :boosts (count boosts) :cursor cursor)
    (loop [state state
           [b & more] boosts]
      (if-not b
        (do (save-state! (:state-io ctx) state) state)
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
      (publish-profile! ctx))
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
