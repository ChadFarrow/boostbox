(ns boostbox.mastodon
  "Post a boost announcement to Mastodon.

   The second place the bot says a boost happened. Podcasters buy the
   announcement with a 1% split; which networks it lands on is our problem, not
   theirs, so this is a destination alongside the relays rather than a separate
   product.

   Two POSTs and a bearer token -- there is no equivalent here of the BIP-340
   signing or the canonical serialization the Nostr path needs. What it does
   have is an asynchronous media pipeline and a hard character limit, and both
   fail quietly if ignored: see upload-media! below.

   Deliberately NOT on boostbox.safefetch. That machinery exists for URLs a
   payer chose; this origin is operator configuration, sitting in the bot's
   environment next to its nostr key. Routing it through an SSRF guard would
   suggest the origin were in doubt, and would refuse a perfectly good instance
   that happens to run on a private network.

   Optional everywhere: with no instance and no token configured every function
   here answers nil or {:ok? false} and the bot behaves exactly as it did
   before this existed. Nothing here throws -- a boost is announced on one
   network rather than not announced at all."
  (:require [clojure.string :as str]
            [babashka.http-client :as http]
            [jsonista.core :as json]
            [com.brunobonacci.mulog :as u]))

(def user-agent
  "Identifies the bot to the instance. Admins read user agents when deciding
   whether an automated account is behaving, so it names the application and
   where to complain about it."
  "Boostr_Bot/1.0 (+https://tardbox.com)")

(def media-poll-interval-ms 1000)

(def media-ready-deadline-ms
  "How long to wait for an instance to finish processing an upload.

   Generous because the alternative is worse: giving up early posts the boost
   with no picture, and the picture is most of what makes the post readable in
   a timeline."
  30000)

(defn configured?
  "Whether an instance and a token are both set. Everything here is a no-op
   without them, and one alone does nothing."
  [{:keys [mastodon-url mastodon-token]}]
  (boolean (and (not-empty (str/trim (str mastodon-url)))
                (not-empty (str/trim (str mastodon-token))))))

(defn auth-headers
  "The bearer token, and nothing else that varies.

   The token grants write:statuses and write:media on the bot's account, so it
   must never be logged: callers log the status code and the boost url, never
   this map."
  [{:keys [mastodon-token]}]
  {"User-Agent" user-agent
   "Authorization" (str "Bearer " (str/trim (str mastodon-token)))})

(defn- api [{:keys [mastodon-url]} path]
  (str (str/replace (str mastodon-url) #"/+$" "") path))

(defn- read-body [resp]
  (try (json/read-value (:body resp)) (catch Exception _ nil)))

(defn media-ready?
  "Whether an uploaded attachment has finished processing.

   `GET /api/v1/media/:id` answers 200 once the instance has derived its
   thumbnails and 206 while it is still working."
  [cfg id]
  (try
    (= 200 (:status (http/get (api cfg (str "/api/v1/media/" id))
                              {:headers (auth-headers cfg)
                               :timeout (or (:mastodon-timeout-ms cfg) 15000)
                               :throw false})))
    (catch Exception _ false)))

(defn upload-media!
  "Upload the banner PNG and return its media id, or nil.

   **The 202 is the trap.** `POST /api/v2/media` answers 200 when the
   attachment is ready to be used and 202 when the instance is still processing
   it. Both carry an id, and attaching a 202's id to a status yields a post
   with a blank image -- a failure that looks exactly like success at every
   point where anyone would check it. So a 202 is polled until the attachment
   reports ready.

   Past the deadline the id is dropped rather than used, so the boost posts
   with no picture instead of with an empty frame."
  [cfg ^bytes png alt-text]
  (when (and (configured? cfg) png (pos? (alength png)))
    (try
      (let [resp (http/post (api cfg "/api/v2/media")
                            {:headers (auth-headers cfg)
                             :multipart [{:name "file"
                                          :content png
                                          :file-name "boost.png"
                                          :content-type "image/png"}
                                         {:name "description"
                                          :content (str alt-text)}]
                             :timeout (or (:mastodon-timeout-ms cfg) 15000)
                             :throw false})
            id (some-> (read-body resp) (get "id") str not-empty)]
        (cond
          (nil? id)
          (do (u/log ::media-upload-failed :status (:status resp)) nil)

          (= 200 (:status resp)) id

          :else
          (let [deadline (+ (System/currentTimeMillis) media-ready-deadline-ms)]
            (loop []
              (cond
                (media-ready? cfg id) id
                (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep media-poll-interval-ms) (recur))
                :else
                (do (u/log ::media-still-processing :media-id id) nil))))))
      (catch Exception e
        (u/log ::media-upload-error :error (ex-message e))
        nil))))

(defn post-status!
  "Post one status. Answers {:ok? true :id ...} or {:ok? false :error ...}.

   `idempotency-key` is the payment hash, and it is what makes an at-least-once
   caller safe here: Mastodon remembers the key and returns the original status
   rather than creating a second one, so a crash between posting and recording
   the id cannot put the same boost in the timeline twice.

   Never throws. A caller has nothing different to do for a timeout, a revoked
   token or a 500, and a boost that cannot be posted here has already been
   published on the relays."
  [cfg {:keys [text media-ids idempotency-key]}]
  (if-not (configured? cfg)
    {:ok? false :error "not configured"}
    (try
      (let [body (cond-> {:status text
                          :visibility (or (:mastodon-visibility cfg) "public")}
                   (seq media-ids) (assoc :media_ids (vec media-ids)))
            resp (http/post (api cfg "/api/v1/statuses")
                            {:headers (cond-> (assoc (auth-headers cfg)
                                                     "Content-Type" "application/json")
                                        idempotency-key
                                        (assoc "Idempotency-Key" (str idempotency-key)))
                             :body (json/write-value-as-string body)
                             :timeout (or (:mastodon-timeout-ms cfg) 15000)
                             :throw false})]
        (if (= 200 (:status resp))
          {:ok? true :id (some-> (read-body resp) (get "id") str not-empty)}
          (do (u/log ::status-failed :status (:status resp))
              {:ok? false :error (str "HTTP " (:status resp))})))
      (catch Exception e
        (u/log ::status-error :error (ex-message e))
        {:ok? false :error (ex-message e)}))))

(defn verify-credentials
  "The account the token belongs to, as {:acct :display-name :bot?}, or nil.

   Only used by scripts/mastodon-check.sh, which needs to answer \"whose
   account is this, and does the instance consider it a bot\" before anything
   is posted from it."
  [cfg]
  (when (configured? cfg)
    (try
      (let [resp (http/get (api cfg "/api/v1/accounts/verify_credentials")
                           {:headers (auth-headers cfg)
                            :timeout (or (:mastodon-timeout-ms cfg) 15000)
                            :throw false})]
        (when (= 200 (:status resp))
          (when-let [b (read-body resp)]
            {:acct (get b "acct")
             :display-name (get b "display_name")
             :bot? (true? (get b "bot"))})))
      (catch Exception e
        (u/log ::verify-error :error (ex-message e))
        nil))))

(defn instance-max-chars
  "What this instance actually allows in a status, or nil.

   Instances raise the stock 500 and there is no way to know from the outside
   which one you are pointed at. Finding out here beats finding out from
   truncated posts in production."
  [cfg]
  (try
    (let [resp (http/get (api cfg "/api/v1/instance")
                         {:headers {"User-Agent" user-agent}
                          :timeout (or (:mastodon-timeout-ms cfg) 15000)
                          :throw false})]
      (when (= 200 (:status resp))
        (some-> (read-body resp)
                (get-in ["configuration" "statuses" "max_characters"])
                long)))
    (catch Exception _ nil)))
