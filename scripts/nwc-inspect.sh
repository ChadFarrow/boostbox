#!/usr/bin/env bash
# Read-only look at what the wallet actually reports for recent incoming
# payments -- specifically whether the blip-10 boostagram TLV (record 7629169)
# survived, which is what decides whether the bot can publish anything.
#
# A payment that arrives via LNURL-pay (a lightning address) does not
# necessarily carry the TLV that a raw keysend does. If the records below show
# transactions but no boostagram, the bot is working correctly and the value
# block needs a `node` keysend recipient rather than an `lnaddress`.
#
#   ./scripts/nwc-inspect.sh              # prompts for the NWC string
#   ./scripts/nwc-inspect.sh nwc.txt      # or reads it from a file
#
# Calls list_transactions only. Nothing is sent, spent, or published.
#
# BBN_ACTIONS and BBN_RECIPIENT_NAMES are honoured exactly as the bot reads
# them, so `publishable` answers for the bot you are about to run:
#
#   BBN_RECIPIENT_NAMES="MSP 2.0" BBN_ACTIONS=boost,auto ./scripts/nwc-inspect.sh

set -euo pipefail

cd "$(dirname "$0")/.."

JAR="${JAR:-target/boostbox.jar}"
HOURS="${HOURS:-24}"
MAX_TXS="${MAX_TXS:-50}" # newest first; raise it to see further back

[ -f "$JAR" ] || {
	echo "missing $JAR -- run: clojure -T:build uber" >&2
	exit 1
}

if [ $# -ge 1 ]; then
	[ -f "$1" ] || {
		echo "no such file: $1" >&2
		exit 1
	}
	BBN_NWC_URI=$(tr -d '\r\n' <"$1")
elif [ -t 0 ]; then
	printf 'Paste the NWC connection string (input hidden): '
	read -rs BBN_NWC_URI || true
	printf '\n'
else
	echo "stdin is not a terminal -- run from a normal terminal window, or pass a file path" >&2
	exit 1
fi
export BBN_NWC_URI HOURS MAX_TXS

[ -n "$BBN_NWC_URI" ] || {
	echo "no connection string given" >&2
	exit 1
}

exec java -cp "$JAR" clojure.main -e '
(require (quote [boostbox.nwc :as nwc])
         (quote [boostbox.boostagram :as bg])
         (quote [clojure.string :as str]))

(let [origins [(str/replace (or (System/getenv "BBN_BOOSTBOX_URL") "https://tardbox.com") #"/+$" "")]
      hours (Long/parseLong (or (System/getenv "HOURS") "24"))
      max-txs (Long/parseLong (or (System/getenv "MAX_TXS") "50"))
      folded (fn [v] (into #{} (comp (map str/trim) (remove str/blank?) (map str/lower-case))
                           (str/split (or v "") #",")))
      opts  {:actions (let [a (folded (System/getenv "BBN_ACTIONS"))] (if (seq a) a #{"boost"}))
             :recipient-names (folded (System/getenv "BBN_RECIPIENT_NAMES"))}
      _     (println "filters :" (pr-str opts))
      from  (- (quot (System/currentTimeMillis) 1000) (* 3600 hours))
      nwc   (nwc/parse-uri (System/getenv "BBN_NWC_URI"))
      _     (println "relay   :" (first (:relays nwc)))
      _     (println "origins :" origins)
      sess  (nwc/open! nwc)]
  (try
    ;; pages of 10, as the bot reads them: a page of 50 boostagrams is too big
    ;; for the relay to carry, and the request just times out
    (let [txs (loop [offset 0 acc []]
                (let [page (nwc/list-transactions! sess {:from from :limit 10 :offset offset})
                      acc (into acc page)]
                  (if (or (< (count page) 10) (>= (count acc) max-txs))
                    acc
                    (recur (+ offset 10) acc))))]
      (println "window:" hours "h    incoming transactions:" (count txs))
      (println)
      (doseq [tx txs]
        (let [md   (get tx "metadata")
              tlvs (get md "tlv_records")
              types (mapv #(get % "type") tlvs)
              has-bg (some #(= bg/boostagram-tlv-type
                               (try (Long/parseLong (str %)) (catch Exception _ nil)))
                           types)
              parsed (nwc/extract-boostagram tx)
              boost  (nwc/transaction->boost tx opts)]
          (println "---")
          (println "  settled_at   :" (get tx "settled_at"))
          (println "  amount msat  :" (get tx "amount"))
          (println "  tx keys      :" (vec (sort (keys tx))))
          (println "  description  :" (pr-str (get tx "description")))
          (println "  desc_hash    :" (pr-str (get tx "description_hash")))
          (println "  boost link   :" (pr-str (bg/boost-link (get tx "description") origins)))
          (println "  metadata?    :" (some? md)
                   (if md (str "keys=" (vec (sort (keys md)))) ""))
          (println "  tlv types    :" (if (seq types) types "(none)"))
          (println "  has 7629169  :" (boolean has-bg))
          (println "  wallet-parsed:" (some? (get md "boostagram")))
          (println "  extracted    :" (if parsed
                                        (str "yes  action=" (:action parsed)
                                             " recipient=" (pr-str (:recipient-name parsed))
                                             " feed-guid=" (:feed-guid parsed)
                                             " total=" (:value-msat-total parsed))
                                        "NO -- no readable boostagram"))
          (println "  publishable  :" (if (:boostagram boost)
                                        "yes"
                                        (str "no   reason=" (:skip boost)
                                             (when (:action boost)
                                               (str " action=" (:action boost))))))))
      (println)
      (println "publishable boosts in window:"
               (count (filter :boostagram (map #(nwc/transaction->boost % opts) txs)))))
    (finally (nwc/close! sess))))'
