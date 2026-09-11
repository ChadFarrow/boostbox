(ns boostbox.applinks
  "Deep links back into the app a boost was sent from.

   A boostagram names the app that sent it and carries a handful of foreign
   keys for the show and episode. Several podcast apps expose routes built from
   exactly those keys, so for a good number of boosts we can point a reader at
   the episode -- or at least the show -- inside the app the booster used,
   rather than at the app's front page.

   The patterns come from nathangathright/podcast-platform-links. Only the ones
   constructible from what a boostagram actually carries are here: anything
   needing an Apple id, an enclosure URL or a platform-internal episode id is
   omitted, because a link we cannot build is worse than no link at all.

   THE ORIGIN ALWAYS COMES FROM THIS TABLE, NEVER FROM THE BOOST. That is the
   whole security story. Every value interpolated below is payer-written, so
   each one is either shape-checked (a UUID, digits) or encoded into a safe
   alphabet (base64url, hex, percent-encoding) before it goes anywhere near a
   URL. A payer choosing their own `app_name` can pick which of these rows is
   used; they cannot introduce an origin that is not written here. See
   boostbox.boostagram/->note-content, which signs the result into a note."
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

;; ~~~~~~~~~~~~~~~~~~~ Encoders ~~~~~~~~~~~~~~~~~~~

(defn- enc
  "Percent-encode for a query parameter."
  [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn- b64url [v]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str v) StandardCharsets/UTF_8)))

(defn- hexed [v]
  (str/join (for [b (.getBytes (str v) StandardCharsets/UTF_8)]
              (format "%02x" (bit-and b 0xff)))))

;; ~~~~~~~~~~~~~~~~~~~ Foreign keys ~~~~~~~~~~~~~~~~~~~

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def ^:private digits-re #"^[0-9]{1,19}$")

(def ^:private max-key-length
  "Longer than this is not a foreign key, and a URL built from it would be
   refused by half the clients that tried to render it."
  512)

(defn- bounded [v]
  (let [s (some-> v str str/trim not-empty)]
    (when (and s (<= (count s) max-key-length)) s)))

(defn foreign-keys
  "The identifiers a link pattern can be built from, or nil for each one this
   boost does not carry.

   `feedID` and `itemID` are the Podcast Index show and episode ids -- blip-10
   sends them under those names, and they are the keys Fountain and CurioCaster
   route on. They are required to be digits: they land in a URL path, where an
   arbitrary string could otherwise walk out of it."
  [b]
  {:podcast-guid (let [v (bounded (:feed-guid b))]
                   (when (and v (re-matches uuid-re v)) (str/lower-case v)))
   :episode-guid (bounded (:item-guid b))
   :feed-url (let [v (bounded (:url b))]
               (when (and v (str/starts-with? (str/lower-case v) "https://")) v))
   :pi-show-id (let [v (bounded (:feed-id b))]
                 (when (and v (re-matches digits-re v)) v))
   :pi-episode-id (let [v (bounded (:item-id b))]
                    (when (and v (re-matches digits-re v)) v))})

;; ~~~~~~~~~~~~~~~~~~~ The table ~~~~~~~~~~~~~~~~~~~

(defn- norm-name
  "App names arrive with whatever spacing and casing the app felt like."
  [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]" "")))

(def platforms
  "One row per app, `:episode` and `:show` each a function of the foreign keys
   that returns a URL or nil. Episode is tried first.

   Adding a row: take the pattern from podcast-platform-links, drop it if it
   needs a key `foreign-keys` cannot supply, and put the origin in the literal
   -- never in a value read from the boost."
  [{:names ["fountain"]
    :label "Fountain"
    :episode (fn [{:keys [pi-episode-id]}]
               (when pi-episode-id (str "https://fountain.fm/episode/" pi-episode-id)))
    :show (fn [{:keys [pi-show-id]}]
            (when pi-show-id (str "https://fountain.fm/show/" pi-show-id)))}

   {:names ["castamatic"]
    :label "Castamatic"
    ;; episode links need a Castamatic-internal id, which a boost never carries
    :show (fn [{:keys [podcast-guid]}]
            (when podcast-guid (str "https://castamatic.com/guid/" podcast-guid)))}

   {:names ["stenofm" "steno"]
    :label "Steno.fm"
    :episode (fn [{:keys [podcast-guid episode-guid]}]
               (when (and podcast-guid episode-guid)
                 (str "https://steno.fm/show/" podcast-guid
                      "/episode/" (b64url episode-guid))))
    :show (fn [{:keys [podcast-guid]}]
            (when podcast-guid (str "https://steno.fm/show/" podcast-guid)))}

   {:names ["breez"]
    :label "Breez"
    :episode (fn [{:keys [feed-url episode-guid]}]
               (when (and feed-url episode-guid)
                 (str "https://breez.link/p?feedURL=" (enc feed-url)
                      "&episodeID=" (enc episode-guid))))
    :show (fn [{:keys [feed-url]}]
            (when feed-url (str "https://breez.link/p?feedURL=" (enc feed-url))))}

   {:names ["truefans"]
    :label "TrueFans"
    :show (fn [{:keys [podcast-guid]}]
            (when podcast-guid (str "https://truefans.fm/" podcast-guid)))}

   {:names ["curiocaster"]
    :label "CurioCaster"
    :show (fn [{:keys [pi-show-id]}]
            (when pi-show-id (str "https://curiocaster.com/podcast/pi" pi-show-id)))}

   {:names ["metacast"]
    :label "Metacast"
    :show (fn [{:keys [podcast-guid]}]
            (when podcast-guid (str "https://open.metacast.app/podcasts/" podcast-guid)))}

   {:names ["podlp"]
    :label "PodLP"
    :show (fn [{:keys [podcast-guid]}]
            (when podcast-guid (str "https://link.podlp.app/" podcast-guid)))}

   {:names ["podcastguru"]
    :label "Podcast Guru"
    :show (fn [{:keys [feed-url]}]
            (when feed-url (str "https://app.podcastguru.io/podcast/X" (hexed feed-url))))}

   {:names ["podcastaddict"]
    :label "Podcast Addict"
    :show (fn [{:keys [feed-url]}]
            (when feed-url (str "https://podcastaddict.com/feed/" (enc feed-url))))}

   {:names ["antennapod"]
    :label "AntennaPod"
    :show (fn [{:keys [feed-url]}]
            (when feed-url
              (str "https://antennapod.org/deeplink/subscribe?url=" (enc feed-url))))}])

(def ^:private by-name
  (into {} (for [p platforms, n (:names p)] [n p])))

(defn app-link
  "{:label :url :episode?} for the app that sent this boost, or nil.

   The episode pattern is preferred and the show is the fallback, so a reader
   lands as close to what was boosted as the app allows. An app we have no row
   for, or one whose patterns need a key this boost did not carry, yields nil
   -- the note then simply says nothing about the app, which is what it did
   before any of this existed."
  [b]
  (when-let [p (by-name (norm-name (:app-name b)))]
    (let [ks (foreign-keys b)
          ep (when-let [f (:episode p)] (f ks))
          show (when-let [f (:show p)] (f ks))]
      (when-let [url (or ep show)]
        {:label (:label p) :url url :episode? (some? ep)}))))
