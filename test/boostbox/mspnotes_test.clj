(ns boostbox.mspnotes-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [boostbox.boostagram :as bg]
            [boostbox.mspnotes :as ms]
            [boostbox.nostr :as nostr]
            [boostbox.nostrbot :as bot]))

(def boostr-key (nostr/hex->bytes (apply str (repeat 64 "9"))))
(def msp-key (nostr/hex->bytes (apply str (repeat 64 "7"))))
(def boostr (nostr/bytes->hex (nostr/x-only-pubkey boostr-key)))
(def msp (nostr/bytes->hex (nostr/x-only-pubkey msp-key)))

(def album "c90e609a-df1e-596a-bd5e-57bcc8aad6cc")
(def other-album "917393e3-1b1e-5cef-ace4-edaa54e1f810")
(def music-show "8d9ea0ef-ca36-5e1f-9a1a-ea7fc1dd4b77")

(defn- note
  "A note exactly as Boostr_Bot built it: through bot/build-note."
  [{:keys [name guid remote id at] :or {name "MSP 2.0" at 1790000000}}]
  (bot/build-note {:seckey boostr-key :boostbox-url "https://tardbox.com" :feed-lookup? false}
                  (bg/normalize (cond-> {"action" "boost" "name" name "podcast" "Some Album"
                                         "episode" "Some Song" "message" "⚡ \"great\" \\o/"
                                         "value_msat_total" 21000}
                                  guid (assoc "guid" guid)
                                  remote (assoc "remote_feed_guid" remote)))
                  {:boost-url (str "https://tardbox.com/boost/" id) :received-msat 21000
                   :settled-at at}))

(defn- tag [e k] (some #(when (= k (first %)) (second %)) (:tags e)))

(deftest a-note-survives-the-file-with-its-signature-intact
  ;; The export is the only copy the re-post and the delete read. A note that
  ;; comes back from it with one escape changed has a different id.
  (let [e (note {:guid album :id "01A"})]
    (is (= [e] (ms/parse-notes (ms/notes->json [e]))))
    (is (nostr/verify-event? (first (ms/parse-notes (ms/notes->json [e])))))))

(deftest only-boostr-bots-own-verified-msp-notes-are-kept
  (let [kept (note {:guid album :id "01A" :at 1790000200})
        older (note {:guid album :id "01B" :at 1790000100})
        not-msp (note {:name "Boostr" :guid album :id "01C"})
        forged (assoc (note {:guid album :id "01D"}) :content "edited")
        someone-else (bot/build-note {:seckey msp-key :boostbox-url "https://tardbox.com" :feed-lookup? false}
                                     (bg/normalize {"action" "boost" "name" "MSP 2.0"}) {})]
    (is (= [older kept] (ms/select-msp-notes boostr [kept not-msp forged someone-else older kept]))
        "one copy each, oldest first, whichever relays returned it")))

(deftest an-album-is-listed-by-its-feed-or-by-the-song-it-was-sent-for
  (let [guids #{album}]
    (is (ms/listed? guids (note {:guid album})))
    (is (ms/listed? guids (note {:guid music-show :remote album}))
        "a music show's boost names the album only as the remote feed")
    (is (not (ms/listed? guids (note {:guid other-album}))))
    (is (not (ms/listed? guids (note {}))) "a note naming no feed is nobody's album")
    (is (not (ms/listed? #{} (note {:guid album})))
        "unlike the bot, an empty list re-posts nothing: it is an operator's mistake, not a choice")))

(deftest a-re-posted-note-is-the-same-note-under-msps-name
  (let [old (note {:guid album :id "01A" :at 1780000000})
        banner (tag old "imeta")
        e (ms/resign msp-key "MSP 2.0" old)]
    (is (nostr/verify-event? e))
    (is (= msp (:pubkey e)))
    (is (= 1780000000 (:created-at e)) "still dated when the boost was paid")
    (is (= "MSP 2.0" (tag e "client")))
    (is (= (str banner "&by=MSP+2.0") (tag e "imeta")) "the picture names the account that posts it")
    (is (clojure.string/includes? (:content e) "boost.png?title=Some+Album&ep=Some+Song&sats=21&by=MSP+2.0"))
    (is (= (remove #(#{"client" "imeta"} (first %)) (:tags old))
           (remove #(#{"client" "imeta"} (first %)) (:tags e)))
        "every other tag, the permalink and the feed's p tags included, is kept as it was")))

(deftest a-second-run-skips-what-the-first-already-posted
  (let [a (note {:guid album :id "01A"})
        b (note {:guid album :id "01B"})
        c (note {:guid other-album :id "01C"})
        posted (ms/permalinks [(ms/resign msp-key "MSP 2.0" a)])]
    (is (= [b] (ms/to-repost #{album} posted [a b c])))))

(deftest deletion-requests-name-every-note-once
  (let [ids (mapv #(format "%064x" %) (range 120))
        events (ms/deletion-events boostr-key ids)]
    (is (= [51 51 21] (map #(count (:tags %)) events)) "fifty notes per request, plus its k tag")
    (is (every? #(= 5 (:kind %)) events))
    (is (every? #(some #{["k" "1"]} (:tags %)) events))
    (is (every? nostr/verify-event? events))
    (is (= ids (for [e events [k id] (:tags e) :when (= "e" k)] id)))))

(deftest the-wrong-key-is-refused
  (let [notes [(note {:guid album :id "01A"})]]
    (testing "re-posting under Boostr_Bot's own key would move nothing"
      (is (ms/repost-key? msp-key boostr))
      (is (not (ms/repost-key? boostr-key boostr))))
    (testing "a delete signed by any other key is ignored by every relay"
      (is (ms/delete-key? boostr-key notes))
      (is (not (ms/delete-key? msp-key notes)))
      (is (not (ms/delete-key? boostr-key [])) "nothing exported, nothing to delete"))))

(deftest the-export-file-round-trips
  (let [f (io/file (System/getProperty "java.io.tmpdir") (str "msp-notes-" (System/nanoTime) ".json"))
        notes [(note {:guid album :id "01A"}) (note {:guid other-album :id "01B"})]]
    (try
      (ms/write-notes! f notes)
      (is (= notes (ms/read-notes f)))
      (finally (io/delete-file f true)))))
