(ns boostbox.feed-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [boostbox.feed :as feed]))

;; Real npubs, so the bech32 checksum in decode-npub has something to verify.
(def npub-a "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")
(def npub-b "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m")

(def feed-xml
  (str "<?xml version=\"1.0\"?><rss><channel>"
       "<title>Podcasting 2.0</title>"
       "<itunes:image href=\"https://cdn.example.com/show.png\"/>"
       "<podcast:txt purpose=\"nostr\">" npub-a "</podcast:txt>"
       "<podcast:txt purpose=\"applepodcastsverify\">not-an-npub</podcast:txt>"
       ;; a decoy attribute, one character away from the real one
       "<podcast:person role=\"host\" x-npub=\"" npub-b "\">Decoy</podcast:person>"
       "<podcast:person role=\"host\" npub=\"nostr:" npub-b "\">Adam</podcast:person>"
       ;; must not match <podcast:person>
       "<podcast:personality npub=\"" npub-a "\">nope</podcast:personality>"
       "<!-- <podcast:txt purpose=\"nostr\">npub1commented</podcast:txt> -->"
       "<item><title>Ep 41</title><guid>https://ex.com/41</guid>"
       "<itunes:image href=\"https://cdn.example.com/ep41.png\"/></item>"
       "<item><title>Ep 42</title><guid>https://ex.com/42?a=B</guid>"
       "<itunes:image href=\"https://cdn.example.com/ep42.png\"/></item>"
       "</channel></rss>"))

(deftest npubs-from-a-feed
  (let [{:keys [npubs]} (feed/read-feed feed-xml nil)]
    (testing "both spellings are read, and `nostr:` is stripped"
      (is (= [npub-a npub-b] (mapv :npub npubs))))
    (testing "and each carries the 32-byte pubkey a p tag needs"
      (is (every? #(re-matches #"[0-9a-f]{64}" (:pubkey %)) npubs))))

  (testing "podcast:txt comes before podcast:person, because the cap truncates:
            the show's own npub should survive a feed that lists a dozen guests"
    (is (= npub-a (:npub (first (:npubs (feed/read-feed feed-xml nil)))))))

  (testing "a decoy attribute is not read as the real one. `\\bnpub` matches
            inside `x-npub`, which is why read-attr anchors on `(?:^|\\s)`"
    (is (nil? (feed/read-attr "role=\"host\" x-npub=\"abc\"" "npub")))
    (is (= "abc" (feed/read-attr "role=\"host\" npub=\"abc\"" "npub"))))

  (testing "a tag name is matched whole: <podcast:person> is not
            <podcast:personality>"
    (is (= 2 (count (feed/find-tags feed-xml "podcast:person")))))

  (testing "a quoted > does not end the tag early"
    (is (= "a > b" (feed/read-attr "title=\"a > b\" npub=\"x\"" "title"))))

  (testing "a commented-out npub is not read: it is how a feed's old npub
            outlives the person who left the show"
    (is (not (str/includes? (feed/strip-comments feed-xml) "npub1commented"))))

  (testing "only an npub names a person. Bare hex, an nprofile and a typo each
            decode to somebody, or to nobody, and a p tag built from one
            notifies whoever that turns out to be -- permanently"
    (is (nil? (feed/decode-npub "nprofile1qqsw3dy8cpu")))
    (is (nil? (feed/decode-npub (apply str (repeat 64 "a")))))
    (is (nil? (feed/decode-npub "npub1zzzzzzzzzzzzzz")))
    (is (nil? (feed/decode-npub nil))))

  (testing "the cap is four, whatever the feed says"
    (let [many (str "<rss><channel>"
                    (apply str (repeat 10 (str "<podcast:person npub=\"" npub-a "\"/>")))
                    (apply str (repeat 10 (str "<podcast:person npub=\"" npub-b "\"/>")))
                    "</channel></rss>")]
      (is (>= feed/max-feed-npubs (count (:npubs (feed/read-feed many nil)))))
      (testing "and duplicates collapse rather than filling it"
        (is (= 2 (count (:npubs (feed/read-feed many nil)))))))))

(deftest art-from-a-feed
  (testing "the episode's own cover wins, matched on the guid the boostagram
            carries -- an item list is not ordered by anything we know, so the
            wrong item's art is a picture of a different episode"
    (is (= "https://cdn.example.com/ep42.png"
           (:art (feed/read-feed feed-xml "https://ex.com/42?a=B")))))

  (testing "and the show's cover when the episode has none, or is unknown"
    (is (= "https://cdn.example.com/show.png"
           (:art (feed/read-feed feed-xml "https://ex.com/999"))))
    (is (= "https://cdn.example.com/show.png"
           (:art (feed/read-feed feed-xml nil)))))

  (testing "an item's cover is never read as the show's: it would put one
            episode's art on every boost for the feed"
    (let [only-items (str "<rss><channel><item><guid>g</guid>"
                          "<itunes:image href=\"https://cdn/ep.png\"/></item></channel></rss>")]
      (is (nil? (:art (feed/read-feed only-items "other"))))))

  (testing "<image><url> is the fallback for a feed with no itunes:image"
    (is (= "https://x.com/b.png"
           (:art (feed/read-feed
                  "<rss><channel><image><url>https://x.com/b.png</url></image></channel></rss>"
                  nil)))))

  (testing "http art is refused. The banner route fetches this URL, and that
            fetch is https-only"
    (is (nil? (:art (feed/read-feed
                     "<rss><channel><itunes:image href=\"http://x.com/a.png\"/></channel></rss>"
                     nil)))))

  (testing "an absurdly long URL is not a real artwork address"
    (is (nil? (:art (feed/read-feed
                     (str "<rss><channel><itunes:image href=\"https://x/"
                          (apply str (repeat 700 "a")) ".png\"/></channel></rss>")
                     nil))))))

(deftest a-feed-that-says-nothing-answers-nil
  (is (nil? (feed/read-feed "<rss></rss>" nil)))
  (is (nil? (feed/read-feed "" nil)))
  (is (nil? (feed/read-feed nil nil))))

(deftest the-scan-is-linear
  ;; The failure this guards is not hypothetical: locating the close tag from
  ;; each open tag, rather than once for the whole document, measured 72 s on
  ;; the second case below -- on a document a feed can serve by accident, and
  ;; on the thread the bot's poll loop runs on.
  (doseq [[label doc]
          [["800 KB of unclosed comment opens"
            (str "<rss><channel>" (apply str (repeat 200000 "<!--")) "</channel></rss>")]
           ["200k unclosed <podcast:txt> opens"
            (str "<rss>" (apply str (repeat 200000 "<podcast:txt purpose=\"nostr\">")) "</rss>")]
           ["200k unclosed <item> opens"
            (str "<rss><channel>" (apply str (repeat 200000 "<item>")) "</channel></rss>")]]]
    (let [t0 (System/currentTimeMillis)
          _ (feed/read-feed doc "g1")
          ms (- (System/currentTimeMillis) t0)]
      (is (< ms 5000) (str label " took " ms " ms")))))

(deftest a-real-feed-still-parses-quickly
  (let [many (str "<rss><channel>"
                  "<itunes:image href=\"https://cdn/show.png\"/>"
                  (apply str (for [i (range 5000)]
                               (str "<item><guid>g" i "</guid>"
                                    "<itunes:image href=\"https://cdn/" i ".png\"/></item>")))
                  "</channel></rss>")
        t0 (System/currentTimeMillis)
        r (feed/read-feed many "g100")
        ms (- (System/currentTimeMillis) t0)]
    (is (= "https://cdn/100.png" (:art r)))
    (is (< ms 5000) (str "5000 items took " ms " ms"))))
