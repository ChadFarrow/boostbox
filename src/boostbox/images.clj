(ns boostbox.images
  (:require [clojure.java.io :as io]))

(defn- load-resource [path]
  (slurp (io/resource path)))

(def v4vbox (load-resource "v4vbox.b64"))
(def favicon (load-resource "favicon.b64"))
