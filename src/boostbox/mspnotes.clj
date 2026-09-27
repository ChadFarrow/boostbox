(ns boostbox.mspnotes
  "One-off: move the MSP 2.0 notes Boostr_Bot published to MSP 2.0's own npub.

   Until 2026-09 the msp-bot signed with Boostr_Bot's key and announced every
   MSP boost. It now signs as MSP 2.0 and announces only the albums whose
   artists agreed (BBN_PUBLISH_FEED_GUIDS). This carries the history across:

     export   read Boostr_Bot's MSP notes from the relays into one file
     repost   re-sign the listed albums' notes as MSP 2.0 and publish them,
              slowly, skipping any the MSP npub already has
     delete   ask the relays to delete every exported note (NIP-09)

   The file is the only thing repost and delete read, so deleting can never
   lose what re-posting needs. Both write nothing without --apply. See
   scripts/msp-notes.sh."
  (:require [boostbox.boostagram :as bg]
            [boostbox.boostbox :as bb]
            [boostbox.nostr :as nostr]
            [boostbox.nostrbot :as bot]
            [boostbox.relay :as relay]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [manifold.stream :as s])
  (:gen-class))

(def boostr-npub
  "Boostr_Bot, whose key the msp-bot signed with until it had its own."
  "npub14k45enf38xt9yqcy5kc7cmzqw67zwx7x5v3kwq3jr3vpzqyaqeys97740t")

(def default-relays
  "The bot's defaults plus fountain, the one relay that took every note of the
   first MSP backfill (damus banned the npub partway through)."
  "wss://relay.damus.io,wss://nos.lol,wss://relay.primal.net,wss://relay.fountain.fm")

(def delete-reason
  "MSP 2.0 boost notes now come from MSP 2.0's own account, and only for artists who agreed.")

(def notes-per-deletion 50)

(def default-interval-ms
  "Between re-posts. damus counts notes per npub, and banned Boostr_Bot for the
   first MSP backfill, which went out with no pause at all."
  15000)

;; ~~~~~~~~~~~~~~~~~~~ Notes ~~~~~~~~~~~~~~~~~~~

(defn- pubkey-of [seckey] (nostr/bytes->hex (nostr/x-only-pubkey seckey)))

(defn- tag [e k] (some #(when (= k (first %)) (second %)) (:tags e)))

(defn wire->event
  "A relay's JSON event as nostr/sign-event shapes one."
  [m]
  {:id (get m "id")
   :pubkey (get m "pubkey")
   :created-at (long (get m "created_at"))
   :kind (long (get m "kind"))
   :tags (mapv #(mapv str %) (get m "tags"))
   :content (get m "content")
   :sig (get m "sig")})

(defn notes->json [notes]
  (str "[" (str/join ",\n" (map nostr/event->json notes)) "]\n"))

(defn parse-notes [s]
  (mapv wire->event (json/read-value s)))

(defn write-notes! [f notes]
  (io/make-parents f)
  (spit f (notes->json notes)))

(defn read-notes [f]
  (parse-notes (slurp f)))

(defn select-msp-notes
  "`author`'s kind:1 notes announcing an MSP 2.0 split, one copy of each,
   oldest first. A note that does not verify is dropped: it cannot be what the
   bot signed, and re-signing it would put someone else's words under MSP's
   name."
  [author events]
  (->> events
       (filter #(and (= 1 (:kind %))
                     (= author (:pubkey %))
                     (= "msp 2.0" (some-> (tag % "recipient") str/trim str/lower-case))
                     (nostr/verify-event? %)))
       (reduce (fn [m e] (assoc m (:id e) e)) {})
       vals
       (sort-by (juxt :created-at :id))
       vec))

(defn- feed-guids
  "The `podcast:guid` i tags: the feed, and the remote feed when there was one."
  [e]
  (into #{} (for [[k v] (:tags e)
                  :when (and (= "i" k) (str/starts-with? (str v) "podcast:guid:"))]
              (str/lower-case (subs v (count "podcast:guid:"))))))

(defn listed?
  "Whether the note names one of `guids` as its feed or remote feed. Unlike
   bg/feed-listed?, an empty list matches nothing: here it can only be an
   operator who forgot to set it."
  [guids e]
  (boolean (some guids (feed-guids e))))

(def ^:private banner-re #"https?://\S+/og/boost\.png(?:\?\S*)?")

(defn- with-by [url by]
  (if (re-find #"[?&]by=" url)
    url
    (str url (if (str/includes? url "?") "&" "?")
         "by=" (java.net.URLEncoder/encode (str by) "UTF-8"))))

(defn resign
  "The same note, published by `seckey` under `client-name`.

   Dated as before -- when the boost was paid -- and every tag kept, the
   permalink and the feed's p tags included. Two things change, so the note
   and its picture both name the account that now publishes it: the `client`
   tag, and `by` on the banner URL, in the body and in imeta alike, exactly as
   bg/banner-url adds it for a new note."
  [seckey client-name e]
  (let [old (re-find banner-re (:content e))
        by? (and old (not (str/blank? (str client-name)))
                 (not= client-name bg/default-client-name))
        swap (fn [s] (if by? (str/replace s old (with-by old client-name)) s))
        tags (->> (:tags e)
                  (keep (fn [[k :as t]]
                          (case k
                            "client" ["client" client-name]
                            "imeta" (let [t (mapv swap t)]
                                      ;; dropped, as bg/->nip73-tags drops it,
                                      ;; rather than refused by a relay
                                      (when (every? #(<= (count %) bg/max-tag-item-length) t) t))
                            t)))
                  vec)]
    (nostr/sign-event seckey {:kind 1
                              :created-at (:created-at e)
                              :content (swap (:content e))
                              :tags tags})))

(defn permalinks
  "The BoostBox permalinks, the `r` tag, of notes already published. A
   re-posted note keeps its permalink, so this is what a second run skips."
  [events]
  (into #{} (keep #(tag % "r")) events))

(defn to-repost [guids posted notes]
  (filterv #(and (listed? guids %) (not (contains? posted (tag % "r")))) notes))

(defn deletion-events
  "NIP-09 requests for `ids`, fifty notes to a request so no one event nears a
   relay's size limit."
  [seckey ids]
  (for [batch (partition-all notes-per-deletion ids)]
    (nostr/sign-event seckey {:kind 5
                              :content delete-reason
                              :tags (conj (mapv (fn [id] ["e" id]) batch) ["k" "1"])})))

(defn repost-key?
  "Re-posting under the key that published them would move nothing."
  [seckey author]
  (not= author (pubkey-of seckey)))

(defn delete-key?
  "Relays honour a deletion only from the note's own author."
  [seckey notes]
  (boolean (and (seq notes) (every? #(= (pubkey-of seckey) (:pubkey %)) notes))))

;; ~~~~~~~~~~~~~~~~~~~ Relays ~~~~~~~~~~~~~~~~~~~

(def ^:private page-size 500)

(defn- req!
  "One REQ, read until EOSE. Returns {:events :complete?}: only an EOSE says
   the relay sent everything it holds, and a timeout or a closed socket must
   not pass for one -- the export would be silently short."
  [conn filter]
  (let [sub (str "msp-notes-" (System/nanoTime))]
    @(relay/send-json! conn ["REQ" sub filter])
    (loop [acc []]
      (let [msg (relay/await-message conn #(and (vector? %)
                                                (#{"EVENT" "EOSE" "CLOSED"} (first %))
                                                (= sub (second %))))]
        (if (= "EVENT" (first msg))
          (recur (conj acc (nth msg 2)))
          (do (relay/send-json! conn ["CLOSE" sub])
              {:events acc :complete? (= "EOSE" (first msg))}))))))

(defn- fetch-all!
  "Every event `url` holds for `filter`, paging back with `until` because a
   relay caps what one REQ returns. Never throws."
  [url filter]
  (try
    (let [conn (relay/connect! url)]
      (try
        (loop [until nil
               seen {}]
          (let [{page :events :keys [complete?]}
                (req! conn (cond-> (assoc filter "limit" page-size)
                             until (assoc "until" until)))
                fresh (remove #(contains? seen (get % "id")) page)
                seen (into seen (map (juxt #(get % "id") identity)) fresh)]
            (cond
              (not complete?)
              {:relay url :events (vec (vals seen))
               :error (str "no EOSE after " (count seen) " events (timed out or closed)")}

              (empty? fresh)
              {:relay url :events (vec (vals seen))}

              :else
              (recur (reduce min (map #(long (get % "created_at")) page)) seen))))
        (finally (s/close! conn))))
    (catch Exception e
      {:relay url :events [] :error (ex-message e)})))

(defn- fetch-from-all! [relays filter]
  (mapv deref (mapv #(future (fetch-all! % filter)) relays)))

;; ~~~~~~~~~~~~~~~~~~~ Commands ~~~~~~~~~~~~~~~~~~~

(defn- date [epoch] (str (java.time.Instant/ofEpochSecond epoch)))

(defn- describe [e]
  (format "%s  %-40s %s" (date (:created-at e))
          (or (second (re-find #"→ (.*)" (:content e))) "?")
          (or (tag e "r") "")))

(defn- accepted-by [{:keys [results]}]
  (str/join " " (map #(str (:relay %) "=" (if (:ok? %) "ok" (str "refused(" (:message %) ")")))
                     results)))

(defn- export!
  "Adds what the relays hold to the file, and never removes a note from it:
   after a delete the relays hold nothing, and the file is then the only copy
   of what repost needs. So a second run, or a run during an outage, is safe."
  [{:keys [relays file author]}]
  (let [results (fetch-from-all! relays {"kinds" [1] "authors" [author]})
        before (if (.exists (io/file file)) (read-notes file) [])
        notes (select-msp-notes author (concat before (map wire->event (mapcat :events results))))
        failed (filter :error results)]
    (doseq [{:keys [relay events error]} results]
      (println (format "%-34s %4d notes by Boostr_Bot%s" relay (count events)
                       (if error (str "  (INCOMPLETE: " error ")") ""))))
    (write-notes! file notes)
    (println (count notes) "MSP 2.0 notes in" (str file) (str "(" (- (count notes) (count before)) " new)"))
    (when (seq notes)
      (println "oldest" (date (:created-at (first notes))) " newest" (date (:created-at (peek notes)))))
    (if (seq failed)
      (do (println "Some relays did not answer in full. Run export again; it only adds.") 1)
      0)))

(defn- repost! [{:keys [relays file seckey client-name guids apply? interval-ms]}]
  (let [author (nostr/bytes->hex (nostr/decode-key boostr-npub "npub"))
        me (pubkey-of seckey)]
    (cond
      (not (repost-key? seckey author))
      (do (println "that is Boostr_Bot's own key -- paste MSP 2.0's nsec") 2)

      (empty? guids)
      (do (println "BBN_PUBLISH_FEED_GUIDS is empty -- name the albums to re-post") 2)

      :else
      (let [notes (read-notes file)
            posted (permalinks (map wire->event
                                    (mapcat :events (fetch-from-all! relays {"kinds" [1] "authors" [me]}))))
            todo (to-repost guids posted notes)]
        (println (count notes) "exported;" (count (filter #(listed? guids %) notes)) "on the listed albums;"
                 (count todo) "not yet posted by" (nostr/->npub (nostr/hex->bytes me)))
        (doseq [e todo] (println " " (describe e)))
        (if-not apply?
          (do (println "dry run: nothing published. Add --apply to post these.") 0)
          (let [failed (reduce (fn [failed [i e]]
                                 (when (pos? i) (Thread/sleep (long interval-ms)))
                                 (let [r (relay/publish-to-relays! relays (resign seckey client-name e))]
                                   (println (inc i) "/" (count todo) (describe e) (accepted-by r))
                                   (cond-> failed (not (:ok? r)) inc)))
                               0 (map-indexed vector todo))]
            (println (- (count todo) failed) "posted," failed "refused by every relay")
            (if (pos? failed) 1 0)))))))

(defn- delete! [{:keys [relays file seckey apply?]}]
  (let [notes (read-notes file)]
    (if-not (delete-key? seckey notes)
      (do (println "that key is not the exported notes' author -- paste Boostr_Bot's nsec") 2)
      (let [events (deletion-events seckey (map :id notes))]
        (println (count notes) "notes in" (str file) "->" (count events) "deletion requests to" (str/join " " relays))
        (if-not apply?
          (do (println "dry run: nothing sent. Add --apply to send them.") 0)
          (let [failed (count (for [e events
                                    :let [r (relay/publish-to-relays! relays e)
                                          _ (println (:id e) (accepted-by r))]
                                    :when (not (:ok? r))]
                                e))]
            (println (- (count events) failed) "sent," failed "refused by every relay")
            (if (pos? failed) 1 0)))))))

(defn -main
  "java -cp boostbox.jar boostbox.mspnotes export|repost|delete [--apply] [--interval <sec>]
   Reads BBN_RELAYS, BBN_NOSTR_SECKEY (repost: MSP 2.0's; delete: Boostr_Bot's),
   BBN_PUBLISH_FEED_GUIDS and BBN_CLIENT_NAME; MSP_NOTES_FILE moves the export."
  [& [cmd & flags]]
  (let [opts {:relays (->> (str/split (bb/get-env "BBN_RELAYS" default-relays) #",")
                           (map str/trim) (remove str/blank?) vec)
              :file (io/file (bb/get-env "MSP_NOTES_FILE"
                                         (str (System/getProperty "user.home")
                                              "/.config/boostbox/msp/boostr-msp-notes.json")))
              :apply? (boolean (some #{"--apply"} flags))
              :interval-ms (if-let [sec (second (drop-while #(not= "--interval" %) flags))]
                             (* 1000 (Long/parseLong sec))
                             default-interval-ms)}
        key! #(nostr/decode-key (bb/get-env "BBN_NOSTR_SECKEY") "nsec")
        status (case cmd
                 "export" (export! (assoc opts :author (nostr/bytes->hex (nostr/decode-key boostr-npub "npub"))))
                 "repost" (repost! (assoc opts
                                          :seckey (key!)
                                          :client-name (bb/get-env "BBN_CLIENT_NAME" "MSP 2.0")
                                          :guids (bot/feed-guid-set (bb/get-env "BBN_PUBLISH_FEED_GUIDS" ""))))
                 "delete" (delete! (assoc opts :seckey (key!)))
                 (do (println "usage: boostbox.mspnotes export|repost|delete [--apply] [--interval <sec>]")
                     2))]
    (shutdown-agents)
    (System/exit status)))
