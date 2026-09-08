(ns boostbox.banner
  "The picture on a boost note: a 1200x300 PNG naming the amount, the show and
   the episode, beside the show's cover.

   Laid out to match boostmebitch's /api/og/boost.png, down to the palette and
   the proportions, so a reader scrolling a feed sees one kind of card whichever
   app published the boost.

   Two things about this route are not obvious from reading it.

   1. **Its path, its parameter names and its `.png` suffix are a permanent
      public contract.** Every boost note ever published writes that URL into a
      signed kind:1, which cannot be edited, so renaming a parameter blanks the
      picture on all of them at once. Add parameters; never repurpose one. The
      extension is in the PATH because a Nostr client decides whether a bare URL
      is an image before it fetches it.
   2. **Every input is attacker-chosen.** The text comes from a query string and
      the art from a URL in one, so the text is bounded and stripped and the
      fetch goes through boostbox.safefetch. The size checks below are not
      belt-and-braces: `ImageIO/read` on an unbounded image allocates
      width*height*4 bytes before anything else can refuse it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boostbox.safefetch :as sf])
  (:import (java.awt Color Font Graphics2D RenderingHints)
           (java.awt.geom Path2D$Double)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(def width 1200)
(def height 300)

;; The palette, taken from boostmebitch's tailwind config.
(def ^:private ink (Color. 10 10 8))
(def ^:private bone (Color. 253 250 243))
(def ^:private bolt (Color. 250 229 0))
(def ^:private muted (Color. 138 133 122))
(def ^:private bolt-rule (Color. 250 229 0 89))   ; 0.35 alpha

(def max-title-length 44)
(def max-episode-length 52)
(def max-art-url-length 600)
(def max-art-bytes (* 2 1024 1024))
(def max-art-pixels
  "Refuse before decoding. A 30000x30000 PNG is a few hundred KB on the wire
   and 3.6 GB once ImageIO has allocated a raster for it."
  (* 6000 6000))
(def art-deadline-ms
  "Shared across all three candidates, so three slow hosts cost one wait."
  6000)

(def ^:private rasterizable
  #{"image/png" "image/jpeg" "image/jpg" "image/gif"})

;; ~~~~~~~~~~~~~~~~~~~ Text ~~~~~~~~~~~~~~~~~~~

(defn clean-line
  "Bound one line of query-string text.

   Control characters are mapped by code point rather than matched by a
   character class: one written literally into a class is invisible in review,
   and an editor that normalizes the file silently changes what it matches."
  [s ^long max-len]
  (when s
    (let [stripped (->> (str s)
                        (map (fn [^Character c]
                               (if (and (> (int c) 31) (not= 127 (int c))) c \space)))
                        (apply str))
          collapsed (->> (str/split stripped #"\s+") (remove str/blank?) (str/join " "))]
      (when-not (str/blank? collapsed)
        (if (> (count collapsed) max-len)
          (str (subs collapsed 0 (dec max-len)) "…")
          collapsed)))))

(defn clean-sats
  "The amount, grouped for display. Rejected rather than coerced: a `sats` that
   is not a plain number is not a number we should be guessing at."
  [s]
  (when (and s (re-matches #"\d{1,12}" (str s)))
    (String/format java.util.Locale/US "%,d" (object-array [(Long/parseLong (str s))]))))

;; ~~~~~~~~~~~~~~~~~~~ Fonts ~~~~~~~~~~~~~~~~~~~
;;
;; The font is bundled and loaded from the jar rather than asked of the
;; platform. The runtime image is eclipse-temurin:21-jre-alpine, which ships no
;; fonts at all, and a missing font does not throw here -- Java2D substitutes
;; and draws blank or tofu boxes, so the failure arrives as an empty picture on
;; a note that is already signed.

(defn- load-font [path]
  ;; io/resource, not Class.getResourceAsStream: the latter resolves against
  ;; the classloader of whatever class it is called on, and the bootstrap
  ;; loader behind java.awt.Font cannot see anything in this jar. It fails by
  ;; returning nil, so the fallback below hides it -- which is how this shipped
  ;; drawing in the platform sans while claiming to use the bundled face.
  (with-open [in (io/input-stream (or (io/resource path)
                                      (throw (ex-info "font resource missing"
                                                      {:path path}))))]
    (Font/createFont Font/TRUETYPE_FONT in)))

(def ^:private base-font
  (delay
    (try
      {:bold (load-font "fonts/BricolageGrotesque-Bold.ttf")
       :regular (load-font "fonts/BricolageGrotesque-Regular.ttf")}
      (catch Exception _
        ;; Not fatal: a banner in the platform's own sans is worth more than no
        ;; banner, and this is the branch that runs if the resource is ever
        ;; dropped from the jar.
        {:bold (Font. Font/SANS_SERIF Font/BOLD 12)
         :regular (Font. Font/SANS_SERIF Font/PLAIN 12)}))))

(defn- font-of [weight ^double size]
  (.deriveFont ^Font (get @base-font weight) (float size)))

;; ~~~~~~~~~~~~~~~~~~~ Artwork ~~~~~~~~~~~~~~~~~~~

(defn- content-type-ok? [headers]
  (let [ct (some-> (get headers "content-type") str/lower-case (str/split #";") first str/trim)]
    (contains? rasterizable ct)))

(defn- decode-bounded
  "Decode image bytes, refusing anything whose declared dimensions are absurd.

   The dimensions are read from the header through an ImageReader before the
   raster is allocated. Calling ImageIO/read first and checking afterwards is
   checking after the damage."
  [^bytes bs]
  (with-open [in (ImageIO/createImageInputStream (ByteArrayInputStream. bs))]
    (let [readers (iterator-seq (ImageIO/getImageReaders in))]
      (when-let [rdr (first readers)]
        (try
          (.setInput rdr in true true)
          (let [w (.getWidth rdr 0)
                h (.getHeight rdr 0)]
            (when (and (pos? w) (pos? h) (<= (* (long w) (long h)) max-art-pixels))
              ;; frame 0: an animated GIF is drawn as its first frame
              (.read rdr 0)))
          (finally (.dispose rdr)))))))

(defn fetch-art
  "The first candidate URL that answers with something we can draw, or nil.

   Candidates rather than one URL because a feed's cover, a CDN's copy and the
   show's own art routinely disagree about which is reachable, and a banner
   with no picture still says the amount."
  [urls]
  (let [deadline (+ (System/currentTimeMillis) art-deadline-ms)]
    (some (fn [url]
            (when (and url
                       (<= (count url) max-art-url-length)
                       (< (System/currentTimeMillis) deadline))
              (try
                (let [{:keys [headers body]} (sf/fetch-pinned!
                                              url {:max-bytes max-art-bytes
                                                   :timeout-ms 4000})]
                  (when (and body (content-type-ok? headers))
                    (decode-bounded body)))
                (catch Exception _ nil))))
          urls)))

;; ~~~~~~~~~~~~~~~~~~~ Drawing ~~~~~~~~~~~~~~~~~~~

(defn- bolt-glyph
  "The lightning mark, drawn rather than typed. An emoji would need a font with
   colour glyph tables, which is not something to ship for one character."
  ^Path2D$Double [^double x ^double y ^double size]
  (let [p (Path2D$Double.)
        sx (fn [^double f] (+ x (* f size)))
        sy (fn [^double f] (+ y (* f size)))]
    (.moveTo p (sx 0.62) (sy 0.0))
    (.lineTo p (sx 0.10) (sy 0.56))
    (.lineTo p (sx 0.44) (sy 0.56))
    (.lineTo p (sx 0.36) (sy 1.0))
    (.lineTo p (sx 0.90) (sy 0.42))
    (.lineTo p (sx 0.55) (sy 0.42))
    (.closePath p)
    p))

(defn- draw-cover
  "The art square, cropped to fill rather than squashed to fit."
  [^Graphics2D g ^BufferedImage art]
  (let [aw (.getWidth art)
        ah (.getHeight art)
        scale (max (/ (double height) aw) (double (/ height ah)))
        dw (int (Math/ceil (* aw scale)))
        dh (int (Math/ceil (* ah scale)))
        dx (int (/ (- height dw) 2))
        dy (int (/ (- height dh) 2))]
    (.setClip g 0 0 height height)
    (.drawImage g art dx dy dw dh nil)
    (.setClip g nil)))

(defn render
  "The PNG bytes for one boost banner.

   `art` is an already-decoded image or nil; fetching is the caller's job, so
   this stays a pure function of its inputs and can be looked at without a
   network."
  ^bytes [{:keys [title episode sats wordmark art]}]
  (let [img (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        ^Graphics2D g (.createGraphics img)]
    (try
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING
                         RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                         RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.setColor g ink)
      (.fillRect g 0 0 width height)

      (when art (draw-cover g art))

      (let [left (if art (+ height 44) 44)]
        (when art
          (.setColor g bolt-rule)
          (.fillRect g height 0 2 height))

        ;; headline: the bolt, then the amount
        (.setColor g bolt)
        (.fill g (bolt-glyph left 88.0 44.0))
        (.setFont g (font-of :bold 46.0))
        (.drawString g ^String (if sats (str sats " SATS") "BOOST")
                     (int (+ left 56)) (int 128))

        ;; the show
        (.setColor g bone)
        (.setFont g (font-of :bold 40.0))
        (.drawString g ^String (or title wordmark) (int left) (int 186))

        ;; the episode
        (when episode
          (.setColor g muted)
          (.setFont g (font-of :regular 27.0))
          (.drawString g ^String episode (int left) (int 228))))

      ;; the host, bottom right
      (.setColor g muted)
      (.setFont g (font-of :regular 20.0))
      (let [mark (str/upper-case (str wordmark))
            w (.stringWidth (.getFontMetrics g) mark)]
        (.drawString g mark (int (- width 30 w)) (int (- height 22))))

      (let [out (ByteArrayOutputStream.)]
        (ImageIO/write img "png" out)
        (.toByteArray out))
      (finally (.dispose g)))))

(defn banner-png
  "Query params in, PNG bytes out. The whole route, minus the HTTP."
  [{:keys [title ep sats art art2 art3]} wordmark]
  (render {:title (clean-line title max-title-length)
           :episode (clean-line ep max-episode-length)
           :sats (clean-sats sats)
           :wordmark wordmark
           :art (fetch-art [art art2 art3])}))

;; ~~~~~~~~~~~~~~~~~~~ Rate limit ~~~~~~~~~~~~~~~~~~~

(def requests-per-minute
  "A boost note is public, so this route is fetched by every reader's client
   rather than by ours -- many addresses, one image each. The allowance is per
   address, so it bounds one caller re-rendering variants, not a note's
   popularity."
  60)

(def ^:private max-tracked-addresses
  "The counter is a map keyed by a value the caller controls, so it needs a
   ceiling of its own. Over it, the window is dropped and everyone starts
   again -- forgetting is the safe direction for a limiter that is protecting
   an image, and it costs one window."
  10000)

(defonce ^:private hits (atom {:minute 0 :counts {}}))

(defn allow?
  "One request against the per-address allowance for the current minute."
  [address]
  (let [minute (quot (System/currentTimeMillis) 60000)
        k (or (not-empty (str address)) "unknown")
        {:keys [counts]} (swap! hits
                                (fn [{m :minute c :counts}]
                                  (let [c (if (or (not= m minute)
                                                  (> (count c) max-tracked-addresses))
                                            {}
                                            c)]
                                    {:minute minute :counts (update c k (fnil inc 0))})))]
    (<= (long (get counts k 0)) requests-per-minute)))
