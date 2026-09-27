(ns boostbox.mspnotes-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [boostbox.boostagram :as bg]
            [boostbox.mspnotes :as ms]
            [boostbox.nostr :as nostr]
            [boostbox.nostrbot :as bot]
            [boostbox.podcastindex :as pi]
            [boostbox.relay :as relay]
            [jsonista.core :as json]
            [manifold.stream :as s]))

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
    (is (= [b] (ms/to-repost #{album} {} posted [a b c])))))

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

(defn- temp-file []
  (io/file (System/getProperty "java.io.tmpdir") (str "msp-notes-" (System/nanoTime) ".json")))

(defn- wire [e] (json/read-value (nostr/event->json e)))

(deftest a-second-export-adds-and-never-removes
  ;; After `delete --apply` the relays hold nothing, and the file is the only
  ;; copy left of what repost needs.
  (let [f (temp-file)
        a (note {:guid album :id "01A" :at 1790000100})
        b (note {:guid album :id "01B" :at 1790000200})]
    (try
      (ms/write-notes! f [a])
      (with-redefs [ms/fetch-from-all! (fn [_ _] [{:relay "wss://r" :events [(wire b)]}])]
        (#'ms/export! {:relays ["wss://r"] :file f :author boostr}))
      (is (= [a b] (ms/read-notes f)) "the new note is added and the old one kept")
      (with-redefs [ms/fetch-from-all! (fn [_ _] [{:relay "wss://r" :events []}])]
        (#'ms/export! {:relays ["wss://r"] :file f :author boostr}))
      (is (= [a b] (ms/read-notes f)) "relays that hold nothing any more take nothing away")
      (finally (io/delete-file f true)))))

(deftest an-export-that-missed-a-relay-says-so
  (let [f (temp-file)]
    (try
      (with-redefs [ms/fetch-from-all! (fn [_ _] [{:relay "wss://r" :events [] :error "no EOSE"}])]
        (is (= 1 (#'ms/export! {:relays ["wss://r"] :file f :author boostr}))
            "an incomplete read is a failed run, so it is run again"))
      (finally (io/delete-file f true)))))

(defn- scripted-relay
  "A relay that answers a REQ with `events` and then `ending` -- \"EOSE\", or
   nil for a relay that goes quiet."
  [events ending]
  (let [sub (atom nil)
        queue (atom nil)]
    {:send-json! (fn [_ msg]
                   (when (= "REQ" (first msg))
                     (reset! sub (second msg))
                     (reset! queue (concat (map #(vector "EVENT" @sub %) events)
                                           (when ending [[ending @sub]]))))
                   (doto (manifold.deferred/deferred) (manifold.deferred/success! true)))
     :await-message (fn [_ pred & _]
                      (loop []
                        (when-let [m (first @queue)]
                          (swap! queue rest)
                          (if (pred m) m (recur)))))}))

(deftest a-relay-that-goes-quiet-is-an-incomplete-read
  (let [e (wire (note {:guid album :id "01A"}))]
    (testing "EOSE is the only proof a relay sent everything"
      (let [{:keys [send-json! await-message]} (scripted-relay [e] "EOSE")]
        (with-redefs [relay/connect! (fn [& _] (s/stream))
                      relay/send-json! send-json!
                      relay/await-message await-message]
          (is (= {:relay "wss://r" :events [e]} (#'ms/fetch-all! "wss://r" {"kinds" [1]}))))))
    (testing "a timeout keeps what arrived but is not mistaken for the end"
      (let [{:keys [send-json! await-message]} (scripted-relay [e] nil)]
        (with-redefs [relay/connect! (fn [& _] (s/stream))
                      relay/send-json! send-json!
                      relay/await-message await-message]
          (let [r (#'ms/fetch-all! "wss://r" {"kinds" [1]})]
            (is (= [e] (:events r)))
            (is (:error r))))))))

(def artist "1a197bac-95ae-53bd-bf6d-40ba8b551088")

(deftest an-artist-is-listed-through-the-feeds-a-note-names
  (let [resolved {album artist}]
    (is (ms/listed? #{artist} resolved (note {:guid album})))
    (is (ms/listed? #{artist} resolved (note {:guid music-show :remote album}))
        "a music show's boost, through the song's album")
    (is (not (ms/listed? #{artist} {} (note {:guid album})))
        "an album whose feed could not be read names no artist")
    (is (= [artist] (ms/publishers-of resolved (note {:guid music-show :remote album}))))))

(deftest a-re-posted-note-names-the-artist
  (let [old (note {:guid music-show :remote album :id "01A"})
        e (ms/resign msp-key "MSP 2.0" old [artist])
        ids (vec (for [[k :as t] (:tags e) :when (#{"i" "k"} k)] t))]
    (is (nostr/verify-event? e))
    (is (= [["i" (str "podcast:publisher:guid:" artist)] ["k" "podcast:publisher:guid"]]
           (subvec ids (- (count ids) 2)))
        "after the feed and item ids, as the bot emits it")
    (is (= (count (:tags old)) (- (count (:tags e)) 2)) "and nothing else is added")
    (is (= (:tags e) (:tags (ms/resign msp-key "MSP 2.0" e [artist])))
        "named once, however often it is re-signed")))

(deftest each-albums-artist-is-read-from-its-own-feed
  (with-redefs [pi/feed-by-guid (fn [_ guid] (when (= guid album) {:url "https://x.example/album.xml"}))
                bot/read-feed-at (fn [_ url _] (when (= url "https://x.example/album.xml") {:publisher-guid artist}))]
    (is (= {album artist} (ms/resolve-publishers! {:pi-key "k" :pi-secret "s"} [album other-album]))
        "an album the index does not know is simply absent")))

(deftest a-second-run-skips-by-artist-too
  (let [a (note {:guid album :id "01A"})
        b (note {:guid album :id "01B"})]
    (is (= [b] (ms/to-repost #{artist} {album artist} (ms/permalinks [a]) [a b])))))
