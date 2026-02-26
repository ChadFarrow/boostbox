(ns boostbox.boostbox
  (:gen-class)
  (:require [clojure.java.io :as io]
            [aleph.http :as httpd]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as muuntaja]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [reitit.ring.malli]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja-middleware]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.coercion.malli]
            [ring.util.codec :as rcodec]
            [clj-uuid :as uuid]
            [boostbox.ulid :as ulid]
            [com.brunobonacci.mulog :as u]
            [boostbox.images :as images]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]))

;; ~~~~~~~~~~~~~~~~~~~ Setup & Config ~~~~~~~~~~~~~~~~~~~
(defn get-env
  "System/getenv that throws with no default."
  ([key] (let [val (System/getenv key)]
           (if (nil? val)
             (throw (ex-info (str "Missing ENV VAR: " key) {:missing-var key}))
             val)))
  ([key default] (let [val (System/getenv key)]
                   (or val default))))

(defn assert-in-set [allowed val]
  (let [valid? (allowed val)]
    (assert valid? (str "Invalid value: " val ", allowed: " allowed))))

(def S3Endpoint
  [:map
   [:protocol [:enum :http :https]]
   [:hostname [:string {:min 1}]]
   [:port {:optional true} [:int {:min 1 :max 65535}]]])

(defn parse-s3-endpoint [url-string]
  (let [uri (java.net.URI. url-string)
        data {:protocol (keyword (.getScheme uri))
              :hostname (.getHost uri)}
        port (let [p (.getPort uri)] (when (pos? p) p))
        data (if port (assoc data :port port) data)]
    (if (m/validate S3Endpoint data)
      data
      (throw (ex-info "Invalid S3 endpoint configuration"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :url url-string
                       :errors (m/explain S3Endpoint data)})))))

(defn config []
  (let [env (get-env "ENV" "PROD")
        _ (assert-in-set #{"DEV" "STAGING" "PROD"} env)
        storage (get-env "BB_STORAGE" "FS")
        _ (assert-in-set #{"FS" "S3"} storage)
        port' (get-env "BB_PORT" "8080")
        port (Integer/parseInt port')
        base-url (get-env "BB_BASE_URL" (str "http://localhost:" port))
        allowed-keys (into #{} (map str/trim (-> (get-env "BB_ALLOWED_KEYS" "v4v4me")
                                                 (str/split #","))))
        _ (assert (seq allowed-keys) "must specify at least one key in BB_ALLOWED_KEYS (comma separated)")
        max-body-size (Long/parseLong (get-env "BB_MAX_BODY" "102400"))
        base-config {:env env :storage storage :port port :base-url base-url
                     :allowed-keys allowed-keys :max-body-size max-body-size}
        storage-config (case storage
                         "FS" {:root-path (get-env "BB_FS_ROOT_PATH" "boosts")}
                         "S3" {:endpoint (parse-s3-endpoint (get-env "BB_S3_ENDPOINT"))
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
  (retrieve [this id])
  (list-all [this]))

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
    (str year "/" month "/" day)))

(defn id->storage-key [id]
  (let [timestamp (ulid/ulid->timestamp id)
        prefix (timestamp->prefix timestamp)]
    (str prefix "/" id ".json")))

(defrecord LocalStorage [root-path]
  IStorage
  (store [_ id data]
    (let [output-file (io/file root-path (id->storage-key id))
          _ (-> output-file .getParentFile .mkdirs)]
      (json/write-value output-file data)))
  (retrieve [_ id]
    (let [input-file (io/file root-path (id->storage-key id))]
      (try
        (json/read-value input-file)
        (catch java.io.FileNotFoundException e
          (throw (ex-info "File not found during LocalStorage retrieve"
                          {:cognitect.anomalies/category :cognitect.anomalies/not-found :exception e}))))))
  (list-all [_]
    (let [root (io/file root-path)
          json-files (->> (file-seq root)
                          (filter #(str/ends-with? (.getName %) ".json")))]
      (->> json-files
           (map #(try (json/read-value %) (catch Exception _ nil)))
           (remove nil?)
           (sort-by #(get % "id") #(compare %2 %1))))))

;; ~~~~~~~~~~~~~~~~~~~ S3 ~~~~~~~~~~~~~~~~~~~
(defn s3-client
  [access-key secret-key region endpoint-override]
  (aws/client (merge
               (if region
                 {:region region}
                 {:region "us-east-1"})
               (when endpoint-override
                 {:endpoint-override endpoint-override})
               {:api :s3
                :credentials-provider
                (aws-creds/basic-credentials-provider
                 {:access-key-id access-key
                  :secret-access-key secret-key})})))

(defn s3-put [s3c bucket-name key-name content-type content]
  (aws/invoke s3c {:op :PutObject
                   :request {:Bucket bucket-name
                             :Key key-name
                             :Body (.getBytes content "UTF-8")
                             :ContentType content-type}}))
(defn s3-get [s3c bucket-name key-name]
  (aws/invoke s3c {:op :GetObject
                   :request {:Bucket bucket-name
                             :Key key-name}}))

(defn s3-list [s3c bucket-name]
  (aws/invoke s3c {:op :ListObjectsV2
                   :request {:Bucket bucket-name}}))

(defn- check-aws-response [response operation]
  (when (contains? response :cognitect.anomalies/category)
    (throw (ex-info (str "AWS Error during " operation) response)))
  response)

(defrecord S3Storage [client bucket]
  IStorage
  (store [_ id data]
    (let [key (id->storage-key id)
          response (s3-put client bucket key "application/json"
                           (json/write-value-as-string data))]
      (check-aws-response response "S3Storage store")))
  (retrieve [_ id]
    (let [get-response (check-aws-response
                        (s3-get client bucket (id->storage-key id))
                        "S3Storage retrieve")]
      (json/read-value (:Body get-response))))
  (list-all [_]
    (let [response (check-aws-response (s3-list client bucket) "S3Storage list-all")]
      (->> (:Contents response)
           (map :Key)
           (filter #(str/ends-with? % ".json"))
           (map (fn [key-name]
                  (let [get-response (s3-get client bucket key-name)]
                    (when-not (contains? get-response :cognitect.anomalies/category)
                      (json/read-value (:Body get-response))))))
           (remove nil?)
           (sort-by #(get % "id") #(compare %2 %1))))))

;; ~~~~~~~~~~~~~~~~ IStorage Utils ~~~~~~~~~~~~~~~~

(defn make-storage [cfg]
  (case  (:storage cfg)
    "FS" (LocalStorage. (:root-path cfg))
    "S3" (let [{:keys [:access-key :secret-key :region :endpoint :bucket]} cfg
               client  (s3-client access-key secret-key region endpoint)]
           (S3Storage. client bucket))))

;; ~~~~~~~~~~~~~~~~~~~ Shared Helpers ~~~~~~~~~~~~~~~~~~~
(defn format-sats
  "Convert millisatoshis to satoshis with comma formatting"
  [value-msat]
  (when value-msat
    (let [sats (Math/round (double (/ value-msat 1000)))
          formatter (java.text.DecimalFormat. "#,##0")]
      (.format formatter sats))))

(defn boost-metadata-row
  "Renders a single metadata row if value exists"
  [label value]
  (when value
    [:div.boost-field
     [:strong.boost-label label]
     [:span.boost-value value]]))

(defn format-timestamp
  "Format ISO-8601 timestamp to readable string like 'Feb 24, 2026 1:54 AM UTC'"
  [ts]
  (when ts
    (try
      (let [inst (java.time.Instant/parse ts)
            zdt (java.time.ZonedDateTime/ofInstant inst (java.time.ZoneId/of "UTC"))
            fmt (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy h:mm a z")]
        (.format zdt fmt))
      (catch Exception _ ts))))

(defn boost-sats-row
  "Renders the amount row with special styling for sats"
  [value-msat]
  (when-let [sats (format-sats value-msat)]
    [:div.boost-field
     [:strong.boost-label "Amount:"]
     [:span.boost-value.sats (str "⚡ " sats " sats")]]))

(defn boost-detail-rows
  "Extracts common boost fields and renders metadata rows"
  [data]
  [(boost-metadata-row "ID:" (get data "id"))
   (boost-metadata-row "Time:" (format-timestamp (get data "timestamp")))
   (boost-metadata-row "From:" (get data "sender_name"))
   (boost-sats-row (get data "value_msat_total"))
   (boost-metadata-row "Show:" (get data "feed_title"))
   (boost-metadata-row "Episode:" (get data "item_title"))
   (boost-metadata-row "App:" (get data "app_name"))
   (boost-metadata-row "Message:" (get data "message"))])

;; ~~~~~~~~~~~~~~~~~~~ Shared CSS ~~~~~~~~~~~~~~~~~~~
(def base-boost-css
  (str ".boost-field { display: grid; align-items: start; }"
       ".boost-field:last-child { border-bottom: none; }"
       ".boost-label { font-weight: 600; white-space: nowrap; font-size: 0.8rem; letter-spacing: 0.04em; text-transform: uppercase; }"
       ".boost-value { word-break: break-word; }"))

;; ~~~~~~~~~~~~~~~~~~~ Homepage ~~~~~~~~~~~~~~~~~~~
(def homepage-css
  (str base-boost-css
       "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }"
       "body { font-family: 'Inter', system-ui, -apple-system, sans-serif; text-align: center; margin: 0; padding: 0; background: #0f0a07; color: #e5e5e5; }"
       "main { width: 100vw; min-height: 100vh; background-size: cover; background-position: center; background-repeat: no-repeat; background-attachment: fixed; display: flex; flex-direction: column; align-items: center; overflow-y: auto; padding: 0 1rem; }"

       ;; Header / hero section
       ".overlay-top { margin-top: 2.5rem; width: 100%; max-width: 640px; padding: 2.5rem 2rem; "
       "background: rgba(15,10,7,0.6); backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px); "
       "border: 1px solid rgba(255,255,255,0.08); border-radius: 16px; flex-shrink: 0; }"
       ".overlay-top h1 { margin: 0; color: #fff; font-size: 2.75rem; font-weight: 800; letter-spacing: -0.02em; }"
       ".overlay-top h1 .accent { color: #f7931a; }"
       ".overlay-top p { font-size: 1.05rem; color: rgba(255,255,255,0.6); margin: 0.75rem 0 0; font-weight: 400; letter-spacing: 0.01em; }"

       ;; Boost count badge
       ".boost-count { display: inline-block; margin-top: 1rem; padding: 0.3rem 0.9rem; background: rgba(247,147,26,0.15); border: 1px solid rgba(247,147,26,0.3); border-radius: 100px; color: #f7931a; font-size: 0.8rem; font-weight: 600; letter-spacing: 0.03em; }"

       ;; Cards section
       ".overlay-middle { width: 100%; max-width: 640px; padding: 1.25rem 0; display: flex; flex-direction: column; gap: 0.75rem; flex-shrink: 0; }"
       ".boost-card-link { text-decoration: none; color: inherit; display: block; }"
       ".boost-card { background: rgba(20,15,10,0.65); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); "
       "border: 1px solid rgba(255,255,255,0.06); border-radius: 12px; padding: 1rem 1.25rem; text-align: left; "
       "transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1); }"
       ".boost-card:hover { background: rgba(25,20,15,0.8); border-color: rgba(247,147,26,0.2); transform: translateY(-1px); "
       "box-shadow: 0 8px 25px rgba(0,0,0,0.3), 0 0 0 1px rgba(247,147,26,0.1); }"
       ".boost-card .boost-field { grid-template-columns: 5.5rem 1fr; gap: 0.5rem; padding: 0.35rem 0; border-bottom: 1px solid rgba(255,255,255,0.05); }"
       ".boost-card .boost-label { color: rgba(255,255,255,0.4); font-size: 0.75rem; }"
       ".boost-card .boost-value { color: rgba(255,255,255,0.9); font-size: 0.85rem; }"
       ".boost-card .boost-value.sats { color: #f7931a; font-weight: 600; }"

       ;; Empty state
       ".empty-state { color: rgba(255,255,255,0.4); font-size: 1rem; padding: 3rem 2rem; "
       "background: rgba(20,15,10,0.5); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); "
       "border: 1px dashed rgba(255,255,255,0.1); border-radius: 12px; }"
       ".empty-state-icon { font-size: 2.5rem; margin-bottom: 0.75rem; opacity: 0.5; }"
       ".empty-state-text { font-size: 1rem; }"

       ;; Footer
       ".overlay-bottom { margin-bottom: 2.5rem; margin-top: 0.5rem; width: 100%; max-width: 640px; padding: 1rem; flex-shrink: 0; }"
       ".button-group { display: flex; gap: 0.75rem; justify-content: center; flex-wrap: wrap; }"
       ".button-group a { margin: 0; display: inline-flex; align-items: center; gap: 0.5rem; "
       "padding: 0.6rem 1.25rem; border-radius: 8px; font-size: 0.85rem; font-weight: 500; "
       "text-decoration: none; transition: all 0.2s ease; }"
       ".btn-primary { background: #f7931a; color: #fff; border: none; }"
       ".btn-primary:hover { background: #e8850f; transform: translateY(-1px); box-shadow: 0 4px 12px rgba(247,147,26,0.3); }"
       ".btn-secondary { background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.7); border: 1px solid rgba(255,255,255,0.1); }"
       ".btn-secondary:hover { background: rgba(255,255,255,0.1); color: #fff; border-color: rgba(255,255,255,0.2); }"

       ;; Responsive
       "@media (max-width: 640px) { "
       ".overlay-top { margin-top: 1.5rem; padding: 1.75rem 1.25rem; } "
       ".overlay-top h1 { font-size: 2rem; } "
       ".overlay-top p { font-size: 0.95rem; } "
       ".boost-card { padding: 0.85rem 1rem; } "
       ".boost-card .boost-field { grid-template-columns: 4.5rem 1fr; } "
       "main { padding: 0 0.75rem; } "
       "}"))

(defn boost-card
  "Renders a single boost as a card for the homepage overlay"
  [boost]
  [:a.boost-card-link {:href (str "/boost/" (get boost "id"))}
   (into [:div.boost-card] (boost-detail-rows boost))])

(defn homepage-head []
  (html/html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
    [:meta {:name "color-scheme", :content "dark"}]
    [:meta {:name "theme-color", :content "#0f0a07"}]
    [:title "TardBox"]
    [:link {:rel "icon" :type "image/png" :href (str "data:image/png;base64," images/favicon)}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap"}]
    [:style homepage-css]]))

(defn homepage-body [boosts]
  (let [boost-count (count boosts)]
    (html/html
     [:body
      [:main {:style (str "background-image: url('data:image/png;base64," images/v4vbox "');")}
       [:div.overlay-top
        [:h1 "Tard" [:span.accent "Box"]]
        [:p "Store and serve your boostagrams"]
        (when (pos? boost-count)
          [:div.boost-count (str boost-count (if (= 1 boost-count) " boost" " boosts"))])]
       [:div.overlay-middle
        (if (seq boosts)
          (map boost-card boosts)
          [:div.empty-state
           [:div.empty-state-icon "⚡"]
           [:div.empty-state-text "No boosts yet"]])]
       [:div.overlay-bottom
        [:div.button-group
         [:a.btn-secondary {:href "/docs"} "API Docs"]
         [:a.btn-secondary {:href "https://github.com/ChadFarrow/boostbox"} "GitHub"]]]]])))

(defn homepage [storage]
  (fn [_]
    (let [boosts (try (.list-all storage) (catch Exception _ []))]
      {:status 200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body (str "<!DOCTYPE html><html>" (homepage-head) (homepage-body boosts) "</html>")})))

;; ~~~~~~~~~~~~~~~~~~~ Boost Schemas ~~~~~~~~~~~~~~~~~~~

(defn valid-iso8601? [s]
  (try
    (java.time.Instant/parse s)
    true
    (catch Exception _ false)))

(def BoostMetadata
  [:map
   ;; provided by us
   #_[:id {:optional true} :string]
   ;; provided by boost client
   [:action {:decode/json str/lower-case
             :decode/string str/lower-case} [:enum "boost" "stream"]]
   [:split {:json-schema/default 1.0} [:double {:min 0.0}]]
   [:value_msat {:json-schema/default 2222000} [:int {:min 1}]]
   [:value_msat_total {:json-schema/default 2222000} [:int {:min 1}]]
   [:timestamp {:json-schema/default (java.time.Instant/now)}
    [:and :string
     [:fn {:error/message "must be ISO-8601"} valid-iso8601?]]]
   ;; optional keys
   [:group {:optional true} [:maybe :string]]
   [:message {:optional true :json-schema/default "row of ducks"} [:maybe :string]]
   [:app_name {:optional true} [:maybe :string]]
   [:app_version {:optional true} [:maybe :string]]
   [:sender_id {:optional true} [:maybe :string]]
   [:sender_name {:optional true} [:maybe :string]]
   [:recipient_name {:optional true} [:maybe :string]]
   [:recipient_address {:optional true} [:maybe :string]]
   [:value_usd {:optional true} [:maybe [:double {:min 0.0}]]]
   [:position {:optional true} [:maybe :int]]
   [:feed_guid {:optional true} [:maybe :string]]
   [:feed_title {:optional true} [:maybe :string]]
   [:item_guid {:optional true} [:maybe :string]]
   [:item_title {:optional true} [:maybe :string]]
   [:publisher_guid {:optional true} [:maybe :string]]
   [:publisher_title {:optional true} [:maybe :string]]
   [:remote_feed_guid {:optional true} [:maybe :string]]
   [:remote_item_guid {:optional true} [:maybe :string]]
   [:remote_publisher_guid {:optional true} [:maybe :string]]])

;; ~~~~~~~~~~~~~~~~~~~ GET View ~~~~~~~~~~~~~~~~~~~
(defn encode-header [data]
  (let [json-str (json/write-value-as-string data)
        encoded (rcodec/url-encode json-str)]
    encoded))

(def boost-view-css
  (str base-boost-css
       "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }"
       "body { font-family: 'Inter', system-ui, -apple-system, sans-serif; margin: 0; padding: 0; background: #0f0a07; color: #e5e5e5; }"
       "main { width: 100vw; min-height: 100vh; background-size: cover; background-position: center; background-repeat: no-repeat; background-attachment: fixed; "
       "display: flex; flex-direction: column; align-items: center; overflow-y: auto; padding: 0 1rem; }"

       ;; Nav bar
       ".nav-bar { width: 100%; max-width: 720px; padding: 1.25rem 0; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }"
       ".nav-back { display: inline-flex; align-items: center; gap: 0.4rem; color: rgba(255,255,255,0.5); text-decoration: none; font-size: 0.85rem; font-weight: 500; transition: color 0.2s; }"
       ".nav-back:hover { color: #f7931a; }"
       ".nav-title { color: #fff; font-size: 1rem; font-weight: 700; }"
       ".nav-title .accent { color: #f7931a; }"

       ;; Boost detail card
       ".boost-detail-card { width: 100%; max-width: 720px; "
       "background: rgba(15,10,7,0.6); backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px); "
       "border: 1px solid rgba(255,255,255,0.08); border-radius: 16px; padding: 2rem; margin-bottom: 1rem; }"
       ".boost-detail-card h2 { color: #fff; font-size: 1.25rem; font-weight: 700; margin: 0 0 1.25rem; padding-bottom: 0.75rem; border-bottom: 1px solid rgba(255,255,255,0.08); }"
       ".boost-detail-card .boost-field { grid-template-columns: 6.5rem 1fr; gap: 0.75rem; padding: 0.6rem 0; border-bottom: 1px solid rgba(255,255,255,0.05); }"
       ".boost-detail-card .boost-label { color: rgba(255,255,255,0.4); }"
       ".boost-detail-card .boost-value { color: rgba(255,255,255,0.9); font-size: 0.9rem; }"
       ".boost-detail-card .boost-value.sats { color: #f7931a; font-weight: 600; }"

       ;; JSON section
       ".json-section { width: 100%; max-width: 720px; margin-bottom: 2.5rem; "
       "background: rgba(15,10,7,0.6); backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px); "
       "border: 1px solid rgba(255,255,255,0.08); border-radius: 16px; padding: 2rem; overflow: hidden; }"
       ".json-section h2 { color: #fff; font-size: 1.25rem; font-weight: 700; margin: 0 0 1rem; padding-bottom: 0.75rem; border-bottom: 1px solid rgba(255,255,255,0.08); }"
       "pre { background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.06); padding: 1.25rem; border-radius: 10px; overflow-x: auto; margin: 0; }"
       "code { font-size: 0.8rem; font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace; line-height: 1.6; }"

       ;; Responsive
       "@media (max-width: 640px) { "
       ".boost-detail-card { padding: 1.25rem; } "
       ".boost-detail-card .boost-field { grid-template-columns: 5rem 1fr; } "
       ".json-section { padding: 1.25rem; } "
       "pre { padding: 1rem; } "
       "main { padding: 0 0.75rem; } "
       "}"))

(defn boost-view
  "Renders RSS payment metadata in a simple HTML page with JSON display."
  [data]
  (let [boost-id (get data "id")
        json-pretty (json/write-value-as-string data (json/object-mapper {:pretty true}))]
    [html/doctype-html5
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
       [:meta {:name "color-scheme", :content "dark"}]
       [:meta {:name "theme-color", :content "#0f0a07"}]
       [:title (str "Boost " boost-id " | TardBox")]
       [:link {:rel "icon" :type "image/png" :href (str "data:image/png;base64," images/favicon)}]
       [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
       [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
       [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap"}]
       [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/atom-one-dark.min.css"}]
       [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"}]
       [:style boost-view-css]]
      [:body
       [:main {:style (str "background-image: url('data:image/png;base64," images/v4vbox "');")}
        [:nav.nav-bar
         [:a.nav-back {:href "/"} "\u2190 All Boosts"]
         [:span.nav-title "Tard" [:span.accent "Box"]]]
        (into [:div.boost-detail-card [:h2 "Boost Details"]] (boost-detail-rows data))
        [:div.json-section
         [:h2 "Raw Metadata"]
         [:pre [:code {:class "language-json"} json-pretty]]]]
       [:script "hljs.highlightAll();"]]]]))

(defn get-boost-by-id [cfg storage]
  (fn [{{:keys [:id]} :path-params :as request}]
    (try
      (let [data (.retrieve storage id)
            data-header (encode-header data)
            data-hiccup (boost-view data)]
        {:status 200
         :headers {"access-control-expose-headers" "x-rss-payment"
                   "x-rss-payment" data-header
                   "content-type" "text/html; charset=utf-8"}
         :body (html/html data-hiccup)})
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [:cognitect.anomalies/category]} (ex-data e)]
          (if (= category :cognitect.anomalies/not-found)
            {:status 404 :body {:error "unknown boost" :id id}}
            (throw e)))))))

;; ~~~~~~~~~~~~~~~~~~~ POST View ~~~~~~~~~~~~~~~~~~~

(defn bolt11-desc [action url message]
  (if-let [s (seq message)]
    (let [s-len (count s)
          action-len (count action)
          url-len (count url)
          ;; n.b. bolt11 max is 639, rss:payment:: + two spaces is 16; 639 - 16 = 623
          max-desc-len (max 0 (- 623 url-len action-len))
          ;; truncate to fit in desc
          truncd-s (take max-desc-len s)
          truncd-s-len (count truncd-s)
          ;; when truncated replace last 3 digits to ... to indicate truncation
          new-s (if (<= truncd-s-len 2)
                  ""
                  (if (< truncd-s-len s-len)
                    (concat (take (- truncd-s-len 3) truncd-s) "...")
                    truncd-s))
          new-message (str/join new-s)
          ;; only add space if message is non-empty
          separator (if (seq new-message) " " "")]
      (str "rss::payment::" action " " url separator new-message))
    (str "rss::payment::" action " " url)))

(defn add-boost [cfg storage]
  (fn [{{body-params :body} :parameters :as request}]
    (let [id (gen-ulid)
          url (str (:base-url cfg) "/boost/" id)
          boost (assoc body-params :id id)
          desc (bolt11-desc (:action boost) url (:message boost))]
      (try
        (.store storage id boost)
        {:status 201
         :body {:id id
                :url url
                :desc desc}}
        (catch Exception e
          {:status 500
           ::exception e
           :body {:error "error during boost storage"}})))))

;; ~~~~~~~~~~~~~~~~~~~ GET /boosts ~~~~~~~~~~~~~~~~~~~
(defn list-boosts [cfg storage]
  (fn [request]
    (let [boosts (vec (.list-all storage))]
      {:status 200
       :body boosts})))

;; ~~~~~~~~~~~~~~~~~~~ HTTP Server ~~~~~~~~~~~~~~~~~~~
(defn auth-middleware [allowed-keys]
  (fn [handler]
    (fn [request]
      (if (allowed-keys (get-in request [:headers :x-api-key]))
        (handler request)
        {:status 401
         :body {:error :unauthorized}}))))

(defn routes [cfg storage]
  [["/" {:get {:no-doc true :handler (homepage storage)}}]
   ["/openapi.json" {:get {:no-doc true :handler (swagger/create-swagger-handler)
                           :swagger {:info {:title "BoostBox API"
                                            :description "simple API to store boost metadata"
                                            :version "0.1.0"}
                                     :tags [{:name "boosts" :description "boost api"}
                                            {:name "admin" :description "admin api"}]
                                     :securityDefinitions {"auth" {:type :apiKey
                                                                   :in :header
                                                                   :name "x-api-key"}}
                                     :definitions {}}}}]
   ["/health" {:get {:handler (fn [_] {:status 200 :body {:status :ok}})
                     :tags #{"admin"}
                     :summary "healthcheck"
                     :responses {200 {:body [:map [:status [:enum :ok]]]}}}}]
   ["/boosts" {:get {:handler (list-boosts cfg storage)
                     :tags #{"boosts"}
                     :middleware [(auth-middleware (:allowed-keys cfg))]
                     :summary "List all boosts"
                     :swagger {:security [{"auth" []}]}
                     :responses {200 {:body [:vector :map]}}}}]
   ["/boost" {:post {:handler (add-boost cfg storage)
                     :tags #{"boosts"}
                     :middleware [(auth-middleware (:allowed-keys cfg))]
                     :summary "Store boost metadata"
                     :swagger {:security [{"auth" []}]}
                     :parameters {:body BoostMetadata}
                     :responses {201 {:body [:map [:id :string] [:url :string] [:desc :string]]}}}}]
   ["/boost/:id" {:get {:handler (get-boost-by-id cfg storage)
                        :tags #{"boosts"}
                        :summary "lookup boost by id"
                        :parameters {:path {:id [:and :string
                                                 [:fn {:error/message "must be valid ULID"} valid-ulid?]]}}
                        :responses {200 {:body :string}
                                    400 {:body [:map [:error :string] [:id :string]]}
                                    401 {:body [:map [:error :string] [:id :string]]}
                                    404 {:body [:map [:error :string] [:id :string]]}}}
                   :head {:handler (get-boost-by-id cfg storage)
                          :no-doc true
                          :parameters {:path {:id [:and :string
                                                   [:fn {:error/message "must be valid ULID"} valid-ulid?]]}}}}]])

(def cors-middleware
  {:name ::cors
   :wrap (fn [handler]
           (fn [request]
             (if (= :options (:request-method request))
               {:status 204
                :headers {"Access-Control-Allow-Origin" "*"
                          "Access-Control-Allow-Methods" "GET, HEAD, POST, OPTIONS"
                          "Access-Control-Allow-Headers" "Content-Type, X-API-Key"
                          "Access-Control-Max-Age" "3600"}}
               (let [response (handler request)]
                 (assoc-in response [:headers "Access-Control-Allow-Origin"] "*")))))})

(defn default-exception-handler
  "Default safe handler for any exception."
  [^Exception e _]
  {:status 500
   :headers {}
   ::exception e
   :body {:error "internal server error"}})

(defn body-size-limiter-middleware [max-body-size]
  (fn [handler]
    (fn [request]
      (let
       [body-stream (:body request)
        body-bytes (when body-stream (-> body-stream slurp (.getBytes "UTF-8")))
        body-size (if body-stream (alength body-bytes) 0)
        request (if body-stream (assoc request :body (java.io.ByteArrayInputStream. body-bytes)) request)]
        (if (< max-body-size body-size)
          {:status 413 :body {:error "payload too large"}}
          (handler request))))))

(defn http-handler [cfg storage]
  (ring/ring-handler
   (ring/router
    (routes cfg storage)
    {:data {:muuntaja muuntaja/instance
            :coercion (reitit.coercion.malli/create
                       {:error-keys #{:in :humanized}
                        :compile mu/open-schema
                        :strip-extra-keys false
                        :default-values true})
            :middleware [swagger/swagger-feature
                         parameters/parameters-middleware
                         muuntaja-middleware/format-negotiate-middleware
                         ;; end of response middleware
                         muuntaja-middleware/format-response-middleware
                         (exception/create-exception-middleware
                          (assoc exception/default-handlers
                                 ;; replace default handler with ours
                                 ::exception/default
                                 default-exception-handler))
                         (body-size-limiter-middleware (:max-body-size cfg))
                         cors-middleware
                         muuntaja-middleware/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/docs"
      :config {:urls [{:name "openapi" :url "/openapi.json"}]}})
    (ring/create-default-handler))))

(defn exception-wrapper-of-last-resort [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        {:status 500
         ::exception e
         :headers {"content-type" "application/json"}
         :body "{\"error\": \"internal server error\"}"}))))

(defn correlation-id-wrapper
  [handler]
  (fn [request]
    (let [existing (get-in request [:headers "x-correlation-id"])
          correlation-id (or existing (gen-ulid))
          request (assoc request :correlation-id correlation-id)
          response (handler request)]
      (assoc-in response [:headers "x-correlation-id"] correlation-id))))

(defn mulog-wrapper [handler]
  (fn [{:keys [:request-method :uri :correlation-id] :as request}]
    (u/trace ::http-request
             {:pairs [:correlation-id correlation-id
                      :method request-method
                      :uri uri]
              :capture (fn [{:keys [:status ::exception] :as response}]
                         (let [success (< status 400)
                               base {:status status
                                     :success success}]
                           (if exception
                             (assoc base :exception exception)
                             base)))}
             (handler request))))

(defn vthread-wrapper [handler]
  (fn [request]
    (let [df (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (mf/success! df (handler request))))
      df)))

(def runner
  (comp vthread-wrapper
        correlation-id-wrapper
        mulog-wrapper
        exception-wrapper-of-last-resort))

(defn serve
  [cfg storage]
  (let [env (:env cfg)
        dev (= env "DEV")
        handler-factory (fn [] (runner (http-handler cfg storage)))
        handler (if dev (ring/reloading-ring-handler handler-factory) (handler-factory))]
    (httpd/start-server
     handler
     {:port (:port cfg)
      ;; When other than :none our handler is run on a thread pool.
      ;; As we are wrapping our handler in a new virtual thread per request
      ;; on our own, we have no risk of blocking the (aleph) handler thread and
      ;; don't need an additional threadpool onto which to offload.
      :executor :none})))

(defn -main [& _]
  (let [cfg (config)
        storage (make-storage cfg)
        logger (u/start-publisher! {:type :console :pretty? (= (:env cfg) "DEV")})
        srv (serve cfg storage)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (u/log ::app-shutting-down)
                                 (.close srv)
                                 (Thread/sleep 500)
                                 (logger)
                                 (Thread/sleep 500))))
    (u/log ::app-starting-up :app "BoostBox")
    {:srv srv :logger logger :config cfg :storage storage}))

(defn stop [state]
  (.close (:srv state))
  ((:logger state)))

(comment
  (def state (-main))
  (stop state)
  state)
