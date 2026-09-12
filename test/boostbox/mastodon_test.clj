(ns boostbox.mastodon-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.http-client :as http]
            [jsonista.core :as json]
            [boostbox.mastodon :as mastodon]))

(def cfg {:mastodon-url "https://example.social"
          :mastodon-token "tok-secret"
          :mastodon-visibility "public"})

(defn- json-resp [status body]
  {:status status :body (json/write-value-as-string body)})

(def ^:private png (byte-array (map unchecked-byte [0x89 0x50 0x4e 0x47])))

;; ~~~~~~~~~~~~~~~~~~~ Opt-in ~~~~~~~~~~~~~~~~~~~

(deftest configured-gates-everything
  (testing "an instance and a token are both needed; one alone does nothing"
    (is (false? (mastodon/configured? {})))
    (is (false? (mastodon/configured? {:mastodon-url "https://example.social"})))
    (is (false? (mastodon/configured? {:mastodon-token "t"})))
    (is (false? (mastodon/configured? {:mastodon-url "  " :mastodon-token "t"})))
    (is (true? (mastodon/configured? cfg))))
  (testing "unconfigured, nothing is posted and nothing throws"
    (with-redefs [http/post (fn [& _] (throw (AssertionError. "must not be called")))
                  http/get (fn [& _] (throw (AssertionError. "must not be called")))]
      (is (false? (:ok? (mastodon/post-status! {} {:text "hi"}))))
      (is (nil? (mastodon/upload-media! {} png "alt"))))))

;; ~~~~~~~~~~~~~~~~~~~ Statuses ~~~~~~~~~~~~~~~~~~~

(deftest status-request-shape
  (let [seen (atom nil)]
    (with-redefs [http/post (fn [url opts]
                              (reset! seen [url opts])
                              (json-resp 200 {"id" "109"}))]
      (let [r (mastodon/post-status! cfg {:text "⚡ Boost ⚡"
                                          :media-ids ["m1"]
                                          :idempotency-key "hash-abc"})
            [url opts] @seen
            body (json/read-value (:body opts))]
        (is (= {:ok? true :id "109"} r))
        (is (= "https://example.social/api/v1/statuses" url))
        (testing "the token travels as a bearer header"
          (is (= "Bearer tok-secret" (get-in opts [:headers "Authorization"]))))
        (testing "the payment hash is the idempotency key -- this is what makes
                  a retry safe, so it must actually be sent"
          (is (= "hash-abc" (get-in opts [:headers "Idempotency-Key"]))))
        (is (= "⚡ Boost ⚡" (get body "status")))
        (is (= ["m1"] (get body "media_ids")))
        (is (= "public" (get body "visibility")))))))

(deftest status-omits-media-when-there-is-none
  (with-redefs [http/post (fn [_url opts]
                            (is (not (contains? (json/read-value (:body opts)) "media_ids")))
                            (json-resp 200 {"id" "1"}))]
    (is (true? (:ok? (mastodon/post-status! cfg {:text "hi"}))))))

(deftest every-status-failure-answers-rather-than-throws
  (testing "a rejection is reported, not raised -- the boost is already on the relays"
    (with-redefs [http/post (fn [& _] {:status 422 :body "{}"})]
      (is (= false (:ok? (mastodon/post-status! cfg {:text "hi"}))))))
  (testing "a revoked token reads like any other failure"
    (with-redefs [http/post (fn [& _] {:status 401 :body "{}"})]
      (is (= false (:ok? (mastodon/post-status! cfg {:text "hi"}))))))
  (testing "a thrown transport error is caught"
    (with-redefs [http/post (fn [& _] (throw (ex-info "connection reset" {})))]
      (is (= false (:ok? (mastodon/post-status! cfg {:text "hi"})))))))

;; ~~~~~~~~~~~~~~~~~~~ Media ~~~~~~~~~~~~~~~~~~~

(deftest media-upload-shape
  (let [seen (atom nil)]
    (with-redefs [http/post (fn [url opts]
                              (reset! seen [url opts])
                              (json-resp 200 {"id" "media-1"}))]
      (let [id (mastodon/upload-media! cfg png "a boost banner")
            [url opts] @seen
            parts (into {} (map (juxt :name identity) (:multipart opts)))]
        (is (= "media-1" id))
        (is (= "https://example.social/api/v2/media" url))
        (testing "the PNG goes as a file part, typed and named"
          (is (= png (get-in parts ["file" :content])))
          (is (= "image/png" (get-in parts ["file" :content-type])))
          (is (= "boost.png" (get-in parts ["file" :file-name]))))
        (testing "alt text rides along, because a banner with none is unreadable
                  to anyone using a screen reader"
          (is (= "a boost banner" (get-in parts ["description" :content]))))))))

(deftest a-202-is-polled-until-the-attachment-is-ready
  (testing "attaching a still-processing id yields a post with a blank image,
            which looks like success everywhere anyone would check"
    (let [polls (atom 0)]
      (with-redefs [http/post (fn [& _] (json-resp 202 {"id" "media-2"}))
                    http/get (fn [& _]
                               (if (< (swap! polls inc) 3)
                                 {:status 206 :body "{}"}
                                 (json-resp 200 {"id" "media-2"})))
                    mastodon/media-poll-interval-ms 1]
        (is (= "media-2" (mastodon/upload-media! cfg png "alt")))
        (is (= 3 @polls))))))

(deftest an-attachment-that-never-becomes-ready-is-dropped
  (testing "the boost posts with no picture rather than with an empty frame"
    (with-redefs [http/post (fn [& _] (json-resp 202 {"id" "stuck"}))
                  http/get (fn [& _] {:status 206 :body "{}"})
                  mastodon/media-poll-interval-ms 1
                  mastodon/media-ready-deadline-ms 5]
      (is (nil? (mastodon/upload-media! cfg png "alt"))))))

(deftest every-media-failure-answers-nil
  (with-redefs [http/post (fn [& _] {:status 500 :body "nope"})]
    (is (nil? (mastodon/upload-media! cfg png "alt"))))
  (with-redefs [http/post (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (mastodon/upload-media! cfg png "alt"))))
  (testing "an empty render is not uploaded"
    (with-redefs [http/post (fn [& _] (throw (AssertionError. "must not be called")))]
      (is (nil? (mastodon/upload-media! cfg (byte-array 0) "alt")))
      (is (nil? (mastodon/upload-media! cfg nil "alt"))))))

;; ~~~~~~~~~~~~~~~~~~~ Operator checks ~~~~~~~~~~~~~~~~~~~

(deftest credentials-and-limit-are-readable
  (with-redefs [http/get (fn [url _opts]
                           (cond
                             (re-find #"verify_credentials" url)
                             (json-resp 200 {"acct" "boostr" "display_name" "Boostr" "bot" true})

                             :else
                             (json-resp 200 {"configuration" {"statuses" {"max_characters" 1000}}})))]
    (is (= {:acct "boostr" :display-name "Boostr" :bot? true}
           (mastodon/verify-credentials cfg)))
    (is (= 1000 (mastodon/instance-max-chars cfg))))
  (testing "a bad token reads as nil rather than as a crash"
    (with-redefs [http/get (fn [& _] {:status 401 :body "{}"})]
      (is (nil? (mastodon/verify-credentials cfg)))
      (is (nil? (mastodon/instance-max-chars cfg))))))

(deftest trailing-slashes-do-not-double-up
  (with-redefs [http/post (fn [url _opts]
                            (is (= "https://example.social/api/v1/statuses" url))
                            (json-resp 200 {"id" "1"}))]
    (mastodon/post-status! (assoc cfg :mastodon-url "https://example.social///")
                           {:text "hi"})))
