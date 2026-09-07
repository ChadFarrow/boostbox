(ns boostbox.relay
  "Minimal Nostr relay client over aleph's websocket support.

   Only what the bot needs: open a socket, send a message, read messages until
   one matches, publish an event and wait for its OK. Aleph is already the
   HTTP server dependency, so this adds no new library."
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [jsonista.core :as json]
            [com.brunobonacci.mulog :as u]
            [boostbox.nostr :as nostr]))

(def default-timeout-ms 15000)

(defn connect!
  "Open a websocket to a relay. Throws on failure."
  ([url] (connect! url default-timeout-ms))
  ([url timeout-ms]
   ;; The timeout goes on a derived deferred, not on `pending` itself: erroring
   ;; the handshake deferred would leave nobody holding the socket if it lands
   ;; a moment late. publish-to-relays! opens one per relay per note, so a slow
   ;; relay would otherwise leak a connection on every boost.
   (let [pending (http/websocket-client url {:max-frame-payload 1048576})
         conn (try
                @(d/timeout! (d/chain pending) timeout-ms)
                (catch Exception e
                  (d/on-realized pending
                                 (fn [late] (when late (s/close! late)))
                                 (fn [_] nil))
                  (throw e)))]
     (u/log ::relay-connected :relay url)
     conn)))

(defn send-json! [conn v]
  (s/put! conn (json/write-value-as-string v)))

(defn- read-message
  "Read one message, parsed as a JSON vector. Returns ::timeout or ::closed."
  [conn timeout-ms]
  (let [msg @(d/timeout! (s/take! conn ::closed) timeout-ms ::timeout)]
    (cond
      (= msg ::timeout) ::timeout
      (or (= msg ::closed) (nil? msg)) ::closed
      :else (try
              (json/read-value msg)
              (catch Exception e
                (u/log ::relay-unparseable-message :error (ex-message e))
                ::skip)))))

(defn await-message
  "Read messages until `pred` returns truthy, then return that message. Returns
   nil on timeout or close. The deadline is for the whole wait, not per read,
   so a chatty relay cannot extend it indefinitely."
  ([conn pred] (await-message conn pred default-timeout-ms))
  ([conn pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [remaining (- deadline (System/currentTimeMillis))]
         (when (pos? remaining)
           (let [msg (read-message conn remaining)]
             (cond
               (#{::timeout ::closed} msg) nil
               (= ::skip msg) (recur)
               (pred msg) msg
               :else (recur)))))))))

(defn publish!
  "Publish a signed event to one relay and wait for its OK.

   Returns {:relay :ok? :message}. A relay that accepts the event answers
   [\"OK\", <id>, true, \"\"]; one that rejects it answers with false and a
   reason, which is worth surfacing -- rate limits and spam filters show up
   here and are otherwise invisible."
  [conn relay-url event]
  (let [id (:id event)]
    @(s/put! conn (str "[\"EVENT\"," (nostr/event->json event) "]"))
    (let [reply (await-message conn #(and (vector? %)
                                          (= "OK" (first %))
                                          (= id (second %))))]
      (if reply
        (let [[_ _ ok? message] reply]
          (u/log ::relay-publish-result :relay relay-url :event-id id
                 :accepted ok? :message message)
          {:relay relay-url :ok? (boolean ok?) :message message})
        (do
          (u/log ::relay-publish-no-reply :relay relay-url :event-id id)
          {:relay relay-url :ok? false :message "no OK received before timeout"})))))

(defn- publish-to-relay!
  "One relay, one socket, opened and closed here. Never throws."
  [url event]
  (let [conn (try (connect! url)
                  (catch Exception e
                    (u/log ::relay-connect-failed :relay url :error (ex-message e))
                    nil))]
    (if-not conn
      {:relay url :ok? false :message "connect failed"}
      (try
        (publish! conn url event)
        (catch Exception e
          {:relay url :ok? false :message (ex-message e)})
        (finally (s/close! conn))))))

(defn publish-to-relays!
  "Publish to every relay in parallel, opening and closing a socket per relay.

   One acceptance is enough: the event is public and relays gossip, so
   demanding unanimity would mean a single rate-limiting relay could block a
   boost from ever being posted. For the same reason the relays are not tried
   in sequence -- three unreachable ones would stall the bot's poll loop for
   the sum of their timeouts on every note. Returns {:ok? :results}."
  [relay-urls event]
  (let [results (->> relay-urls
                     (mapv (fn [url] (future (publish-to-relay! url event))))
                     (mapv deref))]
    {:ok? (boolean (some :ok? results))
     :results results}))
