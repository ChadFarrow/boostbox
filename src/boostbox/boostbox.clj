(ns boostbox.boostbox
  (:gen-class)
  (:require [aleph.http :as httpd]
            [babashka.http-client :as http]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters]))

;; ~~~~~~~~~~~~~~~~~~~ Setup & Config ~~~~~~~~~~~~~~~~~~~

(defn s3-client [account-id access-key secret-key]
  (aws/client {:api :s3
               :region "us-east-1" ;; TODO: need to put something? auto doesn't work. Seems to not matter with the endpoint override.
               :endpoint-override {:protocol :https :hostname (str account-id ".r2.cloudflarestorage.com")}
               :credentials-provider (aws-creds/basic-credentials-provider {:access-key-id access-key
                                                                            :secret-access-key secret-key})}))
;; ~~~~~~~~~~~~~~~~~~~ GET View ~~~~~~~~~~~~~~~~~~~

;; ~~~~~~~~~~~~~~~~~~~ POST View ~~~~~~~~~~~~~~~~~~~

;; ~~~~~~~~~~~~~~~~~~~ HTTP Server ~~~~~~~~~~~~~~~~~~~

(defn routes [s3-client podping-token]
  [["/" {:get {:handler (fn [_] {:status 200 :body "GOT"})}}]
   ["/store" {:post {:handler (fn [_] {:status 200 :body "POSTED"})}}]])

(defn http-handler [s3-client podping-token]
  (ring/ring-handler
   (ring/router
    (routes s3-client podping-token)
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
  [s3-client podping-token]
  (let [env (or (System/getenv "ENV") "PROD")
        dev (= env "DEV")
        handler-factory (fn [] (make-virtual (http-handler s3-client podping-token)))
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
  (serve
   (s3-client (System/getenv "CF_ACCOUNT_ID")
              (System/getenv "CF_ACCESS_KEY")
              (System/getenv "CF_SECRET_KEY"))
   (System/getenv "PODPING_API_KEY")))

(comment
  (def server (-main))
  (.close server))
