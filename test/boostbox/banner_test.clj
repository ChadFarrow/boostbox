(ns boostbox.banner-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [boostbox.boostagram :as bg]
            [boostbox.banner :as banner])
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream)
           (javax.imageio ImageIO)))

(defn- cover
  "A stand-in cover, so the layout can be exercised without a network."
  [w h]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (.setColor g (Color. 120 30 90))
    (.fillRect g 0 0 w h)
    (.dispose g)
    img))

(defn- decode [^bytes bs]
  (ImageIO/read (ByteArrayInputStream. bs)))

(deftest renders-a-png-of-the-declared-size
  ;; The `dim 1200x300` in the note's imeta tag is a promise to every client
  ;; that reserves space before the bytes arrive, so the two must agree.
  (is (= "1200x300" bg/banner-dimensions)
      "boostagram's imeta tag and this renderer must name the same size")
  (doseq [[label m]
          [["everything" {:title "Podcasting 2.0" :episode "Episode 42"
                          :sats "2,100" :wordmark "tardbox.com" :art (cover 1400 1400)}]
           ["no art" {:title "Podcasting 2.0" :episode "Episode 42"
                      :sats "2,100" :wordmark "tardbox.com"}]
           ["nothing but the wordmark" {:wordmark "tardbox.com"}]
           ["art that is not square" {:title "A Show" :sats "21"
                                      :wordmark "tardbox.com" :art (cover 2000 500)}]]]
    (let [img (decode (banner/render m))]
      (is (= [banner/width banner/height] [(.getWidth img) (.getHeight img)]) label))))

(deftest the-bundled-font-is-actually-used
  ;; It fails as a blank picture rather than an error: Java2D substitutes
  ;; silently, and the runtime image ships no fonts at all. The note carrying
  ;; the URL is signed by then, so there is nothing to fix afterwards.
  (is (= "Bricolage Grotesque" (.getFamily (#'banner/font-of :bold 20.0)))))

(deftest query-text-is-bounded
  (testing "an over-long title is cut and marked, not passed through"
    (let [s (banner/clean-line (apply str (repeat 100 "x")) banner/max-title-length)]
      (is (= banner/max-title-length (count s)))
      (is (str/ends-with? s "…"))))

  (testing "control characters become spaces and runs of whitespace collapse.
            Mapped by code point rather than matched by a character class: one
            written literally into a class is invisible in review"
    (is (= "a b" (banner/clean-line (str "a" (char 7) "b") 44)))
    (is (= "a b" (banner/clean-line "  a \n\t b  " 44))))

  (testing "nothing to say is nil, so the caller renders a default rather than
            an empty line"
    (is (nil? (banner/clean-line "   " 44)))
    (is (nil? (banner/clean-line nil 44)))))

(deftest sats-is-a-number-or-nothing
  (is (= "2,100" (banner/clean-sats "2100")))
  (is (= "1" (banner/clean-sats "1")))
  (testing "anything else is refused rather than coerced: a `sats` that is not
            a plain number is not a number to guess at"
    (is (nil? (banner/clean-sats "12a")))
    (is (nil? (banner/clean-sats "-5")))
    (is (nil? (banner/clean-sats "1234567890123")))
    (is (nil? (banner/clean-sats "")))
    (is (nil? (banner/clean-sats nil)))))

(deftest the-per-address-allowance-holds
  (let [addr (str "test-" (System/nanoTime))]
    (is (every? true? (repeatedly banner/requests-per-minute #(banner/allow? addr))))
    (is (false? (banner/allow? addr)) "one past the allowance is refused")
    (testing "and one caller's burst does not spend another's"
      (is (true? (banner/allow? (str addr "-other")))))))
