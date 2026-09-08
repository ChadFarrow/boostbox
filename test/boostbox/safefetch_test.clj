(ns boostbox.safefetch-test
  (:require [clojure.test :refer [deftest testing is]]
            [boostbox.safefetch :as sf]))

(defn- reader-over [^String s]
  (java.io.BufferedReader. (java.io.StringReader. s)))

(defn- read-body [^String wire headers cap]
  (#'sf/read-body! (reader-over wire) headers cap))

(deftest a-body-is-read-under-a-cap
  (testing "a plain body comes back whole"
    (is (= "hello" (read-body "hello" {} 100))))

  (testing "a body over the cap is REFUSED, not truncated. A half-read document
            parses as a different document, and this one is an RSS feed whose
            npubs become p tags"
    (is (nil? (read-body "0123456789" {} 5))))

  (testing "and a content-length already over it never opens the read at all"
    (is (nil? (read-body "short" {"content-length" "999999"} 10))))

  (testing "a body exactly at the cap fits"
    (is (= "12345" (read-body "12345" {} 5)))))

(deftest a-declared-length-is-read-exactly
  ;; We ask for `Connection: close`, so reading to EOF usually works. A server
  ;; that keeps the connection open anyway never sends one, and reading to EOF
  ;; then sits until the socket timeout fires and the whole fetch is discarded
  ;; -- a feed behind such a server would simply never produce a picture, with
  ;; nothing in the log to say why.
  (testing "exactly content-length, and nothing after it"
    (is (= "hello" (read-body "helloTRAILING" {"content-length" "5"} 100))))

  (testing "a stream that ends early answers nil rather than a partial document"
    (is (nil? (read-body "hi" {"content-length" "50"} 100))))

  (testing "with no content-length the read runs to EOF"
    (is (= "hello" (read-body "hello" {} 100)))))

(deftest a-chunked-body-is-de-chunked
  ;; We ask for `Connection: close`, so most servers simply close the socket
  ;; and the identity path above is enough. Chunked is still legal on that
  ;; answer, and a CDN in front of a feed does use it -- a reader that cannot
  ;; de-chunk reads the hex sizes as if they were feed content.
  (let [hdrs {"transfer-encoding" "chunked"}]
    (testing "the sizes are consumed, not returned"
      (is (= "hello world" (read-body "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n" hdrs 100))))

    (testing "a chunk extension after the size is ignored"
      (is (= "hello" (read-body "5;foo=bar\r\nhello\r\n0\r\n\r\n" hdrs 100))))

    (testing "the cap applies across chunks, not per chunk"
      (is (nil? (read-body "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n" hdrs 8))))

    (testing "a malformed size ends the read rather than being read as content"
      (is (nil? (read-body "notahexnumber\r\nhello\r\n0\r\n\r\n" hdrs 100))))

    (testing "a truncated body answers nil rather than a partial document"
      (is (nil? (read-body "20\r\nonly-a-few\r\n" hdrs 100))))))

(deftest headers-are-read-under-their-own-budget
  (let [{:keys [status headers]}
        (#'sf/read-headers (reader-over "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nX-A: b\r\n\r\nbody"))]
    (is (= 200 status))
    (is (= "text/xml" (get headers "content-type")) "names are lower-cased")
    (is (= "b" (get headers "x-a")))))

(deftest a-url-must-be-https-and-must-not-resolve-inward
  ;; This is the check that matters. The shape check upstream is not the
  ;; protection: the URL comes out of a payment description or a query string,
  ;; so an unguarded fetch lets anyone aim us at a cloud metadata endpoint or
  ;; something on the deploy's private network.
  (testing "loopback, RFC1918, link-local, CGNAT and the metadata address"
    (doseq [u ["https://127.0.0.1/x"
               "https://localhost/x"
               "https://10.0.0.1/x"
               "https://192.168.1.1/x"
               "https://172.16.0.1/x"
               "https://169.254.169.254/x"
               "https://100.64.0.1/x"
               "https://[::1]/x"]]
      (is (not (sf/fetchable-url? u)) u)))

  (testing "and a name that resolves inward is refused just the same -- an
            address check that only looks at IP literals is beaten by a public
            DNS record needing no attacker infrastructure"
    (is (not (sf/fetchable-url? "https://127.0.0.1.nip.io/x"))))

  (testing "http is refused: pinning the address relies on TLS to keep
            validating the hostname"
    (is (not (sf/fetchable-url? "http://example.com/x"))))

  (testing "and so is anything that is not a URL at all"
    (is (not (sf/fetchable-url? "not-a-url")))
    (is (not (sf/fetchable-url? "https://no-such-host.invalid/x")))))

(deftest bodies-round-trip-byte-for-byte
  ;; The body is read as ISO-8859-1 chars -- one char per byte -- so that it
  ;; converts back to exactly what the server sent. A feed is UTF-8, and
  ;; decoding it twice mangles every non-ASCII title in it.
  (let [utf8 (.getBytes "héllo 🚀 podcast" "UTF-8")
        latin (String. utf8 "ISO-8859-1")]
    (is (= (seq utf8) (seq (sf/body-bytes latin))))
    (is (= "héllo 🚀 podcast" (String. (sf/body-bytes latin) "UTF-8")))))
