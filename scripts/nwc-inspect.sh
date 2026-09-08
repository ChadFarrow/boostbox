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

set -euo pipefail

JAR="${JAR:-target/boostbox.jar}"
HOURS="${HOURS:-24}"

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
export BBN_NWC_URI HOURS

[ -n "$BBN_NWC_URI" ] || {
	echo "no connection string given" >&2
	exit 1
}

exec java -cp "$JAR" clojure.main -e '
(require (quote [boostbox.nwc :as nwc])
         (quote [boostbox.boostagram :as bg])
         (quote [clojure.string :as str]))

(let [hours (Long/parseLong (or (System/getenv "HOURS") "24"))
      from  (- (quot (System/currentTimeMillis) 1000) (* 3600 hours))
      nwc   (nwc/parse-uri (System/getenv "BBN_NWC_URI"))
      _     (println "relay :" (first (:relays nwc)))
      sess  (nwc/open! nwc)]
  (try
    (let [txs (nwc/list-transactions! sess {:from from :limit 50})]
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
              boost  (nwc/transaction->boost tx)]
          (println "---")
          (println "  settled_at   :" (get tx "settled_at"))
          (println "  amount msat  :" (get tx "amount"))
          (println "  metadata?    :" (some? md)
                   (if md (str "keys=" (vec (sort (keys md)))) ""))
          (println "  tlv types    :" (if (seq types) types "(none)"))
          (println "  has 7629169  :" (boolean has-bg))
          (println "  wallet-parsed:" (some? (get md "boostagram")))
          (println "  extracted    :" (if parsed
                                        (str "yes  action=" (:action parsed)
                                             " feed-guid=" (:feed-guid parsed)
                                             " total=" (:value-msat-total parsed))
                                        "NO -- no readable boostagram"))
          (println "  publishable  :" (some? boost))))
      (println)
      (println "publishable boosts in window:"
               (count (keep nwc/transaction->boost txs))))
    (finally (nwc/close! sess))))'
