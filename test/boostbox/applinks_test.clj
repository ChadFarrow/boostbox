(ns boostbox.applinks-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [boostbox.applinks :as al]
            [boostbox.boostagram :as bg]))

(def castamatic
  "A real Castamatic boost: a feed guid and an episode guid, no feed URL, no
   Podcast Index ids."
  {"action" "boost"
   "app_name" "Castamatic"
   "app_version" "13.2.3"
   "feed_guid" "7c6f7875-2b73-491e-b32c-e2c8d6e91d53"
   "item_guid" "7100c94b-7e8e-4424-8c21-a591f95d8a43"
   "feed_title" "Chad and Reeds Podcast"
   "item_title" "005. Set it and forget it"
   "value_msat_total" 111000})

(def fountain
  {"action" "boost"
   "app_name" "Fountain"
   "feedID" 920666
   "itemID" "16795090"
   "guid" "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"
   "episode_guid" "d98d189b-dc7b-45b1-8720-d4b98690f31f"})

(deftest episode-preferred-over-show
  (testing "Fountain routes on the Podcast Index episode id, so a reader lands
            on the episode that was boosted"
    (is (= {:label "Fountain" :url "https://fountain.fm/episode/16795090" :episode? true}
           (al/app-link (bg/normalize fountain)))))

  (testing "and falls back to the show when the episode id is missing"
    (is (= {:label "Fountain" :url "https://fountain.fm/show/920666" :episode? false}
           (al/app-link (bg/normalize (dissoc fountain "itemID")))))))

(deftest castamatic-gets-the-show
  (testing "Castamatic episode links need a Castamatic-internal id a boost
            never carries, so the show link is the best available"
    (is (= {:label "Castamatic"
            :url "https://castamatic.com/guid/7c6f7875-2b73-491e-b32c-e2c8d6e91d53"
            :episode? false}
           (al/app-link (bg/normalize castamatic))))))

(deftest unknown-and-unbuildable
  (testing "an app with no row yields nil rather than a guess"
    (is (nil? (al/app-link (bg/normalize (assoc castamatic "app_name" "Some New App")))))
    (is (nil? (al/app-link (bg/normalize (dissoc castamatic "app_name"))))))

  (testing "a known app whose patterns need a key this boost lacks yields nil,
            never a URL with a hole in it"
    (let [b (bg/normalize {"action" "boost" "app_name" "Castamatic"})]
      (is (nil? (al/app-link b))))
    (let [b (bg/normalize {"action" "boost" "app_name" "CurioCaster"})]
      (is (nil? (al/app-link b))))))

(deftest names-are-matched-loosely
  (testing "apps disagree about casing and spacing in app_name"
    (doseq [n ["Castamatic" "castamatic" "CASTAMATIC" " Castamatic "]]
      (is (some? (al/app-link (bg/normalize (assoc castamatic "app_name" n))))
          (str "should match " (pr-str n))))))

(deftest payer-data-cannot-move-the-origin
  (testing "a feed guid that is not a UUID is refused, so it can never reach a
            URL path"
    (doseq [bad ["../../evil" "7c6f7875" "a/b" "https://evil.com"]]
      (is (nil? (al/app-link (bg/normalize (assoc castamatic "feed_guid" bad))))
          (str "should refuse " (pr-str bad)))))

  (testing "Podcast Index ids must be digits: they land in a path segment"
    (doseq [bad ["../x" "1/2" "abc" "16795090x"]]
      (is (nil? (:pi-episode-id (al/foreign-keys (bg/normalize (assoc fountain "itemID" bad)))))
          (str "should refuse " (pr-str bad)))))

  (testing "an episode guid is arbitrary text, so it is encoded rather than
            shape-checked -- base64url for a path, percent for a query"
    (let [b (bg/normalize (assoc castamatic
                                 "app_name" "Steno.fm"
                                 "item_guid" "a/b?c=d&e=f"))
          {:keys [url]} (al/app-link b)]
      (is (str/starts-with? url "https://steno.fm/show/7c6f7875-2b73-491e-b32c-e2c8d6e91d53/episode/"))
      (is (not (str/includes? (subs url (str/last-index-of url "/")) "?")))
      (is (not (str/includes? (subs url (str/last-index-of url "/")) "&")))))

  (testing "a feed URL in a query parameter is percent-encoded, so a payer
            cannot append parameters of their own"
    (let [b (bg/normalize {"action" "boost" "app_name" "Breez"
                           "url" "https://evil.example/rss?x=1&y=2"
                           "episode_guid" "e1"})
          {:keys [url]} (al/app-link b)]
      (is (str/starts-with? url "https://breez.link/p?feedURL="))
      (is (str/includes? url "%26y%3D2"))
      (is (= 1 (count (re-seq #"[?&]feedURL=" url))))
      (is (= 1 (count (re-seq #"[?&]episodeID=" url))))))

  (testing "only https feed URLs are used"
    (is (nil? (al/app-link (bg/normalize {"action" "boost" "app_name" "AntennaPod"
                                          "url" "http://plain.example/rss"}))))
    (is (nil? (al/app-link (bg/normalize {"action" "boost" "app_name" "AntennaPod"
                                          "url" "javascript:alert(1)"}))))))

(deftest every-row-builds-a-real-url
  (testing "no row produces a URL with a nil or blank segment in it"
    (let [b (bg/normalize {"action" "boost"
                           "guid" "7c6f7875-2b73-491e-b32c-e2c8d6e91d53"
                           "episode_guid" "ep-1"
                           "url" "https://example.com/rss"
                           "feedID" 920666
                           "itemID" "16795090"})]
      (doseq [{:keys [names label]} al/platforms
              :let [{:keys [url]} (al/app-link (assoc b :app-name (first names)))]]
        (is (some? url) (str label " should build a URL from a full key set"))
        (is (str/starts-with? url "https://") (str label " must be https"))
        (is (not (str/includes? url "null")) (str label " has a nil segment"))
        (is (not (re-find #"//\s*$|/{3,}" url)) (str label " has an empty segment"))))))

(deftest v4vmusic-links-to-its-front-page
  (testing "v4vmusic's own links use internal ids a boost never carries, so its
            boosts link the site itself -- with any key set, or none at all"
    (doseq [b [{"action" "boost" "app_name" "v4vmusic-com"
                "guid" "5aaac594-3b0a-561a-ab00-7043f7ed1cee"
                "episode_guid" "1afa133f-973e-4e7c-8c0f-8a60dbdd772f"}
               {"action" "boost" "app_name" "v4vmusic"}]]
      (is (= {:label "V4V Music" :url "https://v4vmusic.com" :episode? false}
             (al/app-link (bg/normalize b)))))))
