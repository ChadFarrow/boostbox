(ns boostbox.safefetch
  "The one door for outbound HTTP to a URL we did not choose.

   Three separate things guard such a fetch, and they are not interchangeable:

   1. the caller decides a URL's SHAPE -- https, and inside whatever origin
      allowlist that caller has. That is not the protection.
   2. `public-addresses` decides the DESTINATION: it resolves the host and
      refuses loopback, link-local, RFC1918, CGNAT, IPv6 ULA, multicast and
      reserved space. This is the check that matters.
   3. `request-pinned!` opens the socket to the address already checked,
      rather than handing a hostname to an HTTP client that would resolve it a
      second time. That second lookup is the DNS-rebind window.

   Redirects are refused rather than followed and re-checked -- a public host
   answering 302 with a private address is the standard way around an address
   check. Headers are read under a byte cap and a body only under an explicit
   one.

   This started life private to boostbox.nostrbot, guarding the boost-link
   HEAD. It moved here when the feed read and the banner route needed the same
   guarantees; a second copy of this is a second place to get it wrong."
  (:require [clojure.string :as str]))

(def default-timeout-ms 10000)

(def max-header-bytes
  "The status line and headers are all a server can send before we decide
   whether to read anything else. Stop reading rather than let it send them
   forever."
  65536)

(defn- reserved-address?
  "Addresses we must never be talked into contacting."
  [^java.net.InetAddress a]
  (let [bs (map #(bit-and % 0xff) (.getAddress a))
        [b0 b1] bs]
    (or (.isAnyLocalAddress a)                 ; 0.0.0.0, ::
        (.isLoopbackAddress a)                 ; 127/8, ::1
        (.isLinkLocalAddress a)                ; 169.254/16, fe80::/10
        (.isSiteLocalAddress a)                ; 10/8, 172.16/12, 192.168/16
        (.isMulticastAddress a)
        (and (= 4 (count bs))
             (or (and (= 100 b0) (<= 64 b1 127))   ; CGNAT 100.64/10
                 (>= b0 240)))                     ; reserved 240/4
        (and (= 16 (count bs))
             (= 0xfc (bit-and b0 0xfe))))))        ; IPv6 ULA fc00::/7

(defn public-addresses
  "Every address `host` resolves to, or nil if any one of them is an address
   we must not contact.

   All-or-nothing on purpose: a name answering with both a public and a private
   address is not a misconfiguration to be worked around, it is the shape of an
   attack."
  [^String host]
  (let [addrs (seq (java.net.InetAddress/getAllByName host))]
    (when (and addrs (not-any? reserved-address? addrs))
      addrs)))

(defn fetchable-url?
  "Whether a URL from an untrusted source is safe to request.

   The caller decides the shape of a URL; this decides its destination, and it
   is the half that matters. The URL comes out of a payment description written
   by whoever paid us, or out of a query string, so an unguarded fetch would
   let anyone aim us at whatever we can reach -- a cloud metadata endpoint,
   something on the deploy's private network."
  [^String url]
  (try
    (let [uri (java.net.URI. url)
          host (.getHost uri)]
      (boolean (and host
                    (= "https" (str/lower-case (str (.getScheme uri))))
                    (public-addresses host))))
    (catch Exception _ false)))

;; ~~~~~~~~~~~~~~~~~~~ The pinned request ~~~~~~~~~~~~~~~~~~~

(defn- read-headers
  "Status line and headers, under the byte cap. Leaves the reader positioned at
   the first byte of the body."
  [^java.io.BufferedReader rdr]
  (let [status (some-> (.readLine rdr)
                       (str/split #"\s+")
                       second
                       (as-> s (try (Long/parseLong s) (catch Exception _ nil))))]
    (loop [headers {} budget max-header-bytes]
      (let [line (.readLine rdr)]
        (if (or (nil? line) (str/blank? line) (neg? budget))
          {:status status :headers headers}
          (let [i (str/index-of line ":")]
            (recur (if i
                     (assoc headers
                            (str/lower-case (str/trim (subs line 0 i)))
                            (str/trim (subs line (inc i))))
                     headers)
                   (- budget (count line)))))))))

(defn- read-n!
  "Exactly `n` chars, or nil if the stream ends first."
  [^java.io.BufferedReader rdr ^long n ^StringBuilder sb]
  (let [buf (char-array 8192)]
    (loop [left n]
      (if (zero? left)
        sb
        (let [got (.read rdr buf 0 (int (min left 8192)))]
          (when-not (neg? got)
            (.append sb buf 0 got)
            (recur (- left got))))))))

(defn- read-chunked!
  "De-chunk a `Transfer-Encoding: chunked` body under `cap`.

   We ask for `Connection: close`, so most servers just close the socket and
   the identity path below is enough. Chunked is still legal on that answer and
   a CDN in front of a feed does use it, so a reader that cannot de-chunk reads
   the hex sizes as if they were feed content."
  [^java.io.BufferedReader rdr ^long cap]
  (let [sb (StringBuilder.)]
    (loop []
      (let [line (.readLine rdr)]
        (when line
          ;; a chunk size may carry ";ext" after it
          (let [hex (str/trim (first (str/split line #";" 2)))
                n (try (Long/parseLong hex 16) (catch Exception _ nil))]
            (cond
              (nil? n) nil
              (zero? n) (.toString sb)
              (> (+ (.length sb) n) cap) nil   ; refuse, never truncate
              :else (when (read-n! rdr n sb)
                      (.readLine rdr)          ; the CRLF closing the chunk
                      (recur)))))))))

(defn- read-body!
  "The response body as a String of ISO-8859-1 chars -- one char per byte, so
   it converts back to the exact bytes the server sent. Returns nil when the
   body is missing or would exceed `cap`; refusing beats truncating, because a
   half-read document parses as a different document."
  [^java.io.BufferedReader rdr headers ^long cap]
  (let [chunked? (str/includes? (str/lower-case (str (get headers "transfer-encoding")))
                                "chunked")
        declared (try (some-> (get headers "content-length") str/trim Long/parseLong)
                      (catch Exception _ nil))]
    (cond
      chunked? (read-chunked! rdr cap)
      (and declared (> declared cap)) nil

      ;; A declared length is read exactly. We ask for `Connection: close`, so
      ;; reading to EOF usually works -- but a server that keeps the connection
      ;; open anyway never sends one, and the read then sits until the socket
      ;; timeout fires and the whole fetch is thrown away. A feed behind such a
      ;; server would simply never produce a picture, with nothing in the log
      ;; to say why.
      declared (some-> (read-n! rdr declared (StringBuilder.)) str)

      :else
      (let [sb (StringBuilder.)
            buf (char-array 8192)]
        (loop []
          (let [got (.read rdr buf 0 8192)]
            (cond
              (neg? got) (.toString sb)
              (> (+ (.length sb) got) cap) nil
              :else (do (.append sb buf 0 got) (recur)))))))))

(defn request-pinned!
  "`method` `url` over a socket opened directly to `addr` -- the address already
   checked by `public-addresses`.

   Connecting to a verified address rather than a name is the whole point. An
   ordinary client resolves the host itself, so checking a name and then handing
   it to the client leaves a window in which DNS can answer differently the
   second time, and the request lands inside the network the check existed to
   keep it out of. One lookup, one address, no window.

   Pinning the address does not weaken TLS: SNI is set explicitly and endpoint
   identification stays on, so the certificate is still validated against the
   hostname.

   Returns {:status :headers :body}, where :body is present only when
   `:max-body-bytes` is given and the body fits inside it. The body is a String
   of ISO-8859-1 chars; call `body-bytes` for the raw bytes."
  [^String method ^String url ^java.net.InetAddress addr timeout-ms
   & [{:keys [max-body-bytes]}]]
  (let [uri (java.net.URI. url)
        host (.getHost uri)
        port (if (pos? (.getPort uri)) (.getPort uri) 443)
        path (let [p (.getRawPath uri)
                   q (.getRawQuery uri)]
               (str (if (str/blank? p) "/" p) (when q (str "?" q))))
        ^javax.net.ssl.SSLSocketFactory factory (javax.net.ssl.SSLSocketFactory/getDefault)
        ^javax.net.ssl.SSLSocket sock (.createSocket factory)]
    (try
      (.connect sock (java.net.InetSocketAddress. addr (int port)) (int timeout-ms))
      (.setSoTimeout sock (int timeout-ms))
      (let [params (.getSSLParameters sock)]
        (.setServerNames params [(javax.net.ssl.SNIHostName. host)])
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (.setSSLParameters sock params))
      (.startHandshake sock)
      (doto (.getOutputStream sock)
        (.write (.getBytes (str method " " path " HTTP/1.1\r\n"
                                "Host: " host "\r\n"
                                "User-Agent: boostbox-bot\r\n"
                                "Accept: */*\r\n"
                                "Connection: close\r\n\r\n")
                           "UTF-8"))
        (.flush))
      (let [rdr (java.io.BufferedReader.
                 (java.io.InputStreamReader. (.getInputStream sock) "ISO-8859-1"))
            {:keys [status headers]} (read-headers rdr)]
        (cond-> {:status status :headers headers}
          max-body-bytes (assoc :body (read-body! rdr headers (long max-body-bytes)))))
      (finally
        (try (.close sock) (catch Exception _ nil))))))

(defn head-pinned!
  "HEAD `url`, reading the status line and headers and never a body."
  [^String url ^java.net.InetAddress addr timeout-ms]
  (request-pinned! "HEAD" url addr timeout-ms))

(defn body-bytes
  "The raw bytes behind a `:body` string. ISO-8859-1 round-trips byte for byte."
  ^bytes [^String body]
  (.getBytes body "ISO-8859-1"))

(defn fetch-pinned!
  "The whole guarded GET: shape, destination, pinned socket, capped body.

   Returns {:status :headers :body} with :body as raw bytes, or nil for any
   failure at all -- a bad shape, a private address, a redirect, a non-200, an
   over-cap body, a timeout. Callers treat nil as 'no answer' rather than
   distinguishing why: none of the reasons change what they do next."
  [url {:keys [max-bytes timeout-ms]
        :or {max-bytes (* 2 1024 1024) timeout-ms default-timeout-ms}}]
  (try
    (let [uri (java.net.URI. (str url))
          host (.getHost uri)]
      (when (and host (= "https" (str/lower-case (str (.getScheme uri)))))
        (when-let [addrs (public-addresses host)]
          (let [{:keys [status headers body]}
                (request-pinned! "GET" (str url) (first addrs) timeout-ms
                                 {:max-body-bytes max-bytes})]
            (when (and (= 200 status) body)
              {:status status :headers headers :body (body-bytes body)})))))
    (catch Exception _ nil)))
