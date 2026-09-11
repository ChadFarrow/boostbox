(ns boostbox.podcastindex-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.http-client :as http]
            [jsonista.core :as json]
            [boostbox.podcastindex :as pi]))

(def creds {:pi-key "TESTKEY" :pi-secret "TESTSECRET"})

(def ^:private feed-guid "7c6f7875-2b73-491e-b32c-e2c8d6e91d53")

(defn- ok [body]
  (fn [_url _opts] {:status 200 :body (json/write-value-as-string body)}))

(deftest configured-gates-everything
  (testing "credentials are optional, and without them nothing here acts"
    (is (false? (pi/configured? {})))
    (is (false? (pi/configured? {:pi-key "a"})))
    (is (false? (pi/configured? {:pi-secret "b"})))
    (is (false? (pi/configured? {:pi-key "" :pi-secret "b"})))
    (is (true? (pi/configured? creds))))

  (testing "a lookup with no credentials answers nil without a request"
    (with-redefs [http/get (fn [& _] (throw (AssertionError. "must not request")))]
      (is (nil? (pi/feed-by-guid {} feed-guid))))))

(deftest auth-is-sha1-of-key-secret-date
  (let [h (pi/auth-headers creds 1700000000)]
    (testing "the scheme the API documents"
      ;; sha1("TESTKEY" + "TESTSECRET" + "1700000000")
      (is (= "9f3f70e10a7efc2ad526a3378ef08a5e304790af" (get h "Authorization")))
      (is (= "TESTKEY" (get h "X-Auth-Key")))
      (is (= "1700000000" (get h "X-Auth-Date"))))

    (testing "the secret is never transmitted, only the digest"
      (is (not-any? #(str/includes? (str %) "TESTSECRET") (vals h))))

    (testing "a generic user agent is rejected by the API with a 403 that reads
              like an auth failure, so it must identify this application"
      (is (str/includes? (get h "User-Agent") "Boostr_Bot")))))

(deftest guid-must-be-a-uuid
  (testing "the guid is the only payer-written part of the URL"
    (with-redefs [http/get (fn [& _] (throw (AssertionError. "must not request")))]
      (doseq [bad ["../../etc/passwd" "a b" "" "   " "7c6f7875" nil
                   "7c6f7875-2b73-491e-b32c-e2c8d6e91d53 OR 1=1"]]
        (is (nil? (pi/feed-by-guid creds bad)) (str "should refuse " (pr-str bad)))))))

(deftest reads-the-feed-out-of-a-response
  (testing "the address is the point; artwork and the Podcast Index id come
            along because the response already carries them"
    (with-redefs [http/get (ok {"status" "true"
                                "feed" {"id" 920666
                                        "url" "https://serve.podhome.fm/rss/x"
                                        "artwork" "https://cdn.example/art.jpg"
                                        "image" "https://cdn.example/img.jpg"}})]
      (is (= {:url "https://serve.podhome.fm/rss/x"
              :artwork "https://cdn.example/art.jpg"
              :pi-id "920666"}
             (pi/feed-by-guid creds feed-guid)))))

  (testing "originalUrl is accepted when url is missing, and image when
            artwork is"
    (with-redefs [http/get (ok {"feed" {"originalUrl" "https://old.example/rss"
                                        "image" "https://cdn.example/img.jpg"}})]
      (is (= {:url "https://old.example/rss"
              :artwork "https://cdn.example/img.jpg"
              :pi-id nil}
             (pi/feed-by-guid creds feed-guid)))))

  (testing "the API answers \"feed\": [] rather than omitting the key when
            nothing matches, so an empty vector must read as a miss"
    (with-redefs [http/get (ok {"status" "true" "feed" [] "description" "no match"})]
      (is (nil? (pi/feed-by-guid creds feed-guid)))))

  (testing "a feed with nothing usable in it is a miss, not an empty map"
    (with-redefs [http/get (ok {"feed" {"title" "Only a title"}})]
      (is (nil? (pi/feed-by-guid creds feed-guid))))
    (with-redefs [http/get (ok {"feed" {"url" "   " "artwork" ""}})]
      (is (nil? (pi/feed-by-guid creds feed-guid))))))

(deftest every-failure-answers-nil
  (testing "none of these changes what a caller does next, so none of them
            throws: a boost is announced with no picture, not left unannounced"
    (doseq [[label stub] [["a non-200" (fn [& _] {:status 401 :body "nope"})]
                          ["a 500" (fn [& _] {:status 500 :body ""})]
                          ["unparseable JSON" (fn [& _] {:status 200 :body "<html>"})]
                          ["a timeout" (fn [& _] (throw (java.net.SocketTimeoutException. "timeout")))]
                          ["a DNS failure" (fn [& _] (throw (java.net.UnknownHostException. "nope")))]]]
      (with-redefs [http/get stub]
        (is (nil? (pi/feed-by-guid creds feed-guid)) (str "should survive " label))))))

(deftest request-shape
  (testing "the origin is a literal and the guid is url-encoded into the query"
    (let [seen (atom nil)]
      (with-redefs [http/get (fn [url opts] (reset! seen [url opts]) {:status 200 :body "{}"})]
        (pi/feed-by-guid (assoc creds :pi-timeout-ms 1234) feed-guid)
        (let [[url opts] @seen]
          (is (= (str pi/api-base "/podcasts/byguid?guid=" feed-guid) url))
          (is (str/starts-with? url "https://api.podcastindex.org/"))
          (is (= 1234 (:timeout opts)))
          (is (false? (:throw opts)) "a non-200 must not throw")
          (is (= #{"User-Agent" "X-Auth-Key" "X-Auth-Date" "Authorization"}
                 (set (keys (:headers opts))))))))))
