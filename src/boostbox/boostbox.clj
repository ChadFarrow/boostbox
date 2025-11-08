(ns boostbox.boostbox
  (:gen-class)
  (:require [clojure.java.io :as io]
            [aleph.http :as httpd]
            [babashka.http-client :as http]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters]
            [clj-uuid :as uuid]
            [boostbox.ulid :as ulid]))

;; ~~~~~~~~~~~~~~~~~~~ Setup & Config ~~~~~~~~~~~~~~~~~~~
(defmacro str$
  "Compile-time string interpolation with ~{expr} syntax.
   
   (str$ \"Name: ~{name}, Age: ~{(+ age 1)}\")
   => compiles to: (str \"Name: \" name \", Age: \" (+ age 1))"
  [s]
  (let [pattern #"~\{([^}]*)\}|([^~]+)"
        parts (re-seq pattern s)
        forms (reduce (fn [acc [_ expr text]]
                        (cond-> acc
                          text (conj text)
                          expr (conj (read-string expr))))
                      []
                      parts)]
    (if (empty? forms)
      ""
      `(str ~@forms))))

(defn get-env
  "System/getenv that throws with no default."
  ([key] (let [val (System/getenv key)]
           (if (nil? val)
             (throw (ex-info (str$ "Missing ENV VAR: ~{key}") {:missing-var key}))
             val)))
  ([key default] (let [val (System/getenv key)]
                   (or val default))))

(defn assert-in-set [allowed val]
  (let [valid? (allowed val)]
    (assert valid? (str$ "Invalid value: ~{val}, allowed: ~{allowed}"))))

(defn config []
  (let [env (get-env "ENV" "PROD")
        _ (assert-in-set #{"DEV" "STAGING" "PROD"} env)
        storage (get-env "BB_STORAGE" "FS")
        _ (assert-in-set #{"FS" "S3"} storage)
        base-config {:env env :storage storage}
        storage-config (case storage
                         "FS" {:root-path (get-env "BB_FS_ROOT_PATH" "boosts")}
                         "S3" {:endpoint (get-env "BB_S3_ENDPOINT")
                               :region (get-env "BB_S3_REGION")
                               :access-key (get-env "BB_S3_ACCESS_KEY")
                               :secret-key (get-env "BB_S3_SECRET_KEY")
                               :bucket (get-env "BB_S3_BUCKET")})]
    (into base-config storage-config)))

;; ~~~~~~~~~~~~~~~~~~~ UUID/ULID ~~~~~~~~~~~~~~~~~~~

(defn gen-ulid []
  (let [id (uuid/v7)
        id-bytes (uuid/as-byte-array id)
        ;; n.b. treat as unsigned
        id-int (BigInteger. 1 id-bytes)]
    (ulid/encode id-int 26)))

(defn ulid->uuid [u]
  (-> u ulid/ulid->bytes uuid/as-uuid))

(defn valid-ulid? [u]
  (try
    (let [as-uuid (ulid->uuid u)]
      (= 7 (uuid/get-version as-uuid)))
    (catch Exception _ false)))

;; ~~~~~~~~~~~~~~~~~~~ Storage ~~~~~~~~~~~~~~~~~~~
(defprotocol IStorage
  (store [this id data])
  (retrieve [this id]))

;; ~~~~~~~~~~~~~~~~~~~ FS ~~~~~~~~~~~~~~~~~~~
(defn timestamp->prefix
  "Convert unix timestamp (milliseconds) to \"YYYY/MM/DD\" string.
   
   (timestamp->prefix 1762637504140) => \"2025/11/08\""
  [unix-ms]
  (let [inst (java.time.Instant/ofEpochMilli unix-ms)
        zdt (java.time.ZonedDateTime/ofInstant inst (java.time.ZoneId/of "UTC"))
        year (.getYear zdt)
        month (format "%02d" (.getMonthValue zdt))
        day (format "%02d" (.getDayOfMonth zdt))]
    (str$ "~{year}/~{month}/~{day}")))

(defrecord LocalStorage [root-path]
  IStorage
  (store [_ id data]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          output-file (io/file root-path prefix (str$ "~{id}.json"))
          _ (-> output-file .getParentFile .mkdirs)]
      (json/write-value output-file data)))
  (retrieve [_ id]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          input-file (io/file root-path prefix (str$ "~{id}.json"))]
      (json/read-value input-file))))

;; ~~~~~~~~~~~~~~~~~~~ S3 ~~~~~~~~~~~~~~~~~~~
(defn s3-client [endpoint region access-key secret-key]
  (aws/client {:api :s3
               :region region
               :endpoint-override {:protocol :https :hostname endpoint}
               :credentials-provider
               (aws-creds/basic-credentials-provider
                {:access-key-id access-key
                 :secret-access-key secret-key})}))
(defrecord S3Storage [client bucket]
  IStorage
  (store [_ id data] nil)
  (retrieve [_ id] nil))

;; ~~~~~~~~~~~~~~~~~~~ GET View ~~~~~~~~~~~~~~~~~~~

;; ~~~~~~~~~~~~~~~~~~~ POST View ~~~~~~~~~~~~~~~~~~~

;; ~~~~~~~~~~~~~~~~~~~ HTTP Server ~~~~~~~~~~~~~~~~~~~

(defn routes [cfg]
  [["/" {:get {:handler (fn [_] {:status 200 :body cfg})}}]
   ["/boost" {:post {:handler (fn [_] {:status 200 :body {:success true}})}}]])

(defn http-handler [cfg]
  (ring/ring-handler
   (ring/router
    (routes cfg)
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         reitit.ring.middleware.parameters/parameters-middleware]}})
   (ring/create-default-handler)))

(defn make-virtual [f]
  (fn [& args]
    (let [deferred (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (try
           (mf/success! deferred (apply f args))
           (catch Exception e (mf/error! deferred e)))))
      deferred)))

(defn serve
  [cfg]
  (let [env (or (System/getenv "ENV") "PROD")
        dev (= env "DEV")
        handler-factory (fn [] (make-virtual (http-handler cfg)))
        handler (if dev (ring/reloading-ring-handler handler-factory) (handler-factory))]
    (httpd/start-server
     handler
     {:port 8080
      ;; When other than :none our handler is run on a thread pool.
      ;; As we are wrapping our handler in a new virtual thread per request
      ;; on our own, we have no risk of blocking the (aleph) handler thread and
      ;; don't need an additional threadpool onto which to offload.
      :executor :none})))

(defn -main [& _]
  (let [cfg (config)]
    (serve cfg)))

(comment
  (def server (-main))
  (.close server)
  (def mystorage (LocalStorage. "/tmp/boosts"))
  (def myid (gen-ulid))
  (.store mystorage myid {:abc 123 :bcd "234"})
  (.retrieve mystorage myid)
  (config))
