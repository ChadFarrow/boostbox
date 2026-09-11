(ns boostbox.podcastindex
  "Resolve a `<podcast:guid>` to the feed it names, through the Podcast Index
   API.

   This exists because apps disagree about what they send. A boostagram always
   carries the feed guid; only some carry the feed address, and without an
   address there is no feed read -- no cover art on the banner, and no `p` tags
   for the people the feed declares. The memo in boostbox.nostrbot covers a
   show once *some* app has sent its address, but a show only ever boosted from
   an app that sends none never warms up. This resolves it on the first boost.

   Deliberately NOT on boostbox.safefetch. That machinery exists for URLs a
   payer chose; this origin is a literal below, and the only payer-written part
   is a guid that must match a UUID before it is used, url-encoded, as a query
   parameter. Routing a URL we picked ourselves through an SSRF guard would
   suggest the origin were in doubt.

   Optional everywhere: with no credentials configured every function here
   answers nil and the bot behaves exactly as it did before this existed."
  (:require [clojure.string :as str]
            [babashka.http-client :as http]
            [jsonista.core :as json]
            [com.brunobonacci.mulog :as u])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def api-base "https://api.podcastindex.org/api/1.0")

(def user-agent
  "The Podcast Index rejects generic user agents with a 403 whose body talks
   about user agents, not about credentials -- which reads exactly like an auth
   failure if you are not looking closely. It must identify this application."
  "Boostr_Bot/1.0 (+https://tardbox.com)")

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn- sha1-hex ^String [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-1")
                   (.getBytes s StandardCharsets/UTF_8))]
    (str/join (for [b d] (format "%02x" (bit-and b 0xff))))))

(defn configured?
  "Whether credentials are set. Everything here is a no-op without them."
  [{:keys [pi-key pi-secret]}]
  (boolean (and (not-empty (str pi-key)) (not-empty (str pi-secret)))))

(defn auth-headers
  "The Podcast Index signs each request with sha1(key + secret + unix seconds),
   with that same timestamp sent alongside so the server can recompute it.

   The secret is never transmitted -- only the digest -- so it must not be
   logged either; callers log the guid and the outcome, never this map."
  [{:keys [pi-key pi-secret]} ^long now-seconds]
  (let [date (str now-seconds)]
    {"User-Agent" user-agent
     "X-Auth-Key" (str pi-key)
     "X-Auth-Date" date
     "Authorization" (sha1-hex (str pi-key pi-secret date))}))

(defn- feed-of
  "The `feed` member of a byguid response, or nil.

   The API answers `\"feed\": []` rather than omitting the key when nothing
   matches, so an empty vector has to read as a miss."
  [body]
  (let [feed (get body "feed")]
    (when (map? feed) feed)))

(defn feed-by-guid
  "{:url :artwork :pi-id} for a `<podcast:guid>`, or nil.

   `:url` is the feed address, which is the point. `:artwork` comes along
   because the API already returns it and it is a usable banner picture when
   the feed read finds none. `:pi-id` is the Podcast Index feed id, which is
   the key Fountain and CurioCaster build show links from.

   Every failure answers nil -- no credentials, an unparseable guid, a non-200,
   a timeout, a miss -- because none of them changes what a caller does next.
   A boost is announced with no picture rather than not announced."
  [{:keys [pi-timeout-ms] :as cfg} guid]
  (let [g (some-> guid str str/trim not-empty)]
    (when (and (configured? cfg) g (re-matches uuid-re g))
      (try
        (let [url (str api-base "/podcasts/byguid?guid="
                       (URLEncoder/encode g StandardCharsets/UTF_8))
              resp (http/get url {:headers (auth-headers cfg (quot (System/currentTimeMillis) 1000))
                                  :timeout (or pi-timeout-ms 8000)
                                  :throw false})]
          (if (= 200 (:status resp))
            (when-let [feed (feed-of (json/read-value (:body resp)))]
              (let [pick (fn [& ks] (some #(let [v (get feed %)]
                                             (when (and (string? v) (not (str/blank? v))) (str/trim v)))
                                          ks))
                    feed-url (pick "url" "originalUrl")
                    art (pick "artwork" "image")
                    pi-id (let [v (get feed "id")] (when (integer? v) (str v)))]
                (when (or feed-url art pi-id)
                  {:url feed-url :artwork art :pi-id pi-id})))
            (do (u/log ::lookup-failed :guid g :status (:status resp)) nil)))
        (catch Exception e
          (u/log ::lookup-error :guid g :error (ex-message e))
          nil)))))
