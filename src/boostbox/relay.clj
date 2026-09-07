(ns boostbox.relay
  "Minimal Nostr relay client over aleph's websocket support.

   Only what the bot needs: open a socket, send a message, read messages until
   one matches, publish an event and wait for its OK. Aleph is already the
   HTTP server dependency, so this adds no new library."
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [jsonista.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [boostbox.nostr :as nostr]))

(def default-timeout-ms 15000)

(defn connect!
  "Open a websocket to a relay. Throws on failure."
  ([url] (connect! url default-timeout-ms))
  ([url timeout-ms]
   (let [conn @(d/timeout! (http/websocket-client url {:max-frame-payload 1048576})
                           timeout-ms)]
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

(defn publish-to-relays!
  "Publish to every relay, opening and closing a socket per relay.

   One acceptance is enough: the event is public and relays gossip, so
   demanding unanimity would mean a single rate-limiting relay could block a
   boost from ever being posted. Returns {:ok? :results}."
  [relay-urls event]
  (let [results
        (doall
         (for [url relay-urls]
           (let [conn (try (connect! url) (catch Exception e
                                            (u/log ::relay-connect-failed
                                                   :relay url :error (ex-message e))
                                            nil))]
             (if-not conn
               {:relay url :ok? false :message "connect failed"}
               (try
                 (publish! conn url event)
                 (catch Exception e
                   {:relay url :ok? false :message (ex-message e)})
                 (finally (s/close! conn)))))))]
    {:ok? (boolean (some :ok? results))
     :results results}))
