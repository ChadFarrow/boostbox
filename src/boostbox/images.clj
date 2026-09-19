(ns boostbox.images
  "The page artwork, served at its own URL and never inlined.

   These used to be base64 `data:` URIs written into every page: 3.65 MB of
   picture around a 9 KB boost, which gzip cannot shrink and which a crawler
   walking the homepage's links paid for on every page. On Railway that was
   96 GB of egress in a fortnight. A page now names each image by a path with
   its content hash in it, so a browser fetches it once and caches it forever,
   and a crawler that reads only HTML never fetches it at all."
  (:require [clojure.java.io :as io])
  (:import (java.security MessageDigest)))

(defn- load-bytes ^bytes [path]
  (with-open [in (io/input-stream (io/resource path))]
    (.readAllBytes in)))

(defn- asset
  "An asset whose path changes whenever its bytes do, so it can be cached as
   immutable without ever serving a stale picture."
  [resource-path stem ext content-type]
  (let [bs (load-bytes resource-path)
        digest (.digest (MessageDigest/getInstance "SHA-256") bs)
        h (apply str (map #(format "%02x" %) (take 4 digest)))]
    {:path (str "/assets/" stem "." h "." ext)
     :content-type content-type
     :bytes bs}))

(def v4vbox (asset "v4vbox.jpg" "v4vbox" "jpg" "image/jpeg"))
(def favicon (asset "favicon.png" "favicon" "png" "image/png"))
