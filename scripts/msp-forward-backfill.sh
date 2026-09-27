#!/usr/bin/env bash
# One-off: send MSP 2.0's split payments since FROM to MSP-2.0's chart ingest.
# Publishes nothing to Nostr and never touches the deployed bot's state.
# Re-running it is always safe: MSP ignores a record it already holds.
#
#   clojure -T:build uber              # once, builds target/boostbox.jar
#   scripts/msp-forward-backfill.sh    # FROM defaults to the chart's cutover
#
# It prompts for the NWC connection string ("MSP nostr info" in Alby Hub) and the
# MSP ingest token, both with echo off, so neither lands in shell history or `ps`.
# Reading the wallet history since the cutover takes about 17 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${JAR:-target/boostbox.jar}"
FROM="${FROM:-1769721533}" # 2026-01-29T21:18:53Z, BOOSTBOX_CUTOVER in MSP-2.0

[ -f "$JAR" ] || {
	echo "missing $JAR -- run:  clojure -T:build uber" >&2
	exit 1
}
[ -t 0 ] || {
	echo "stdin is not a terminal -- run this from a normal terminal" >&2
	exit 1
}

printf 'Paste the NWC connection string (input hidden): '
read -rs BBN_NWC_URI || true
printf '\nPaste the MSP ingest token (input hidden): '
read -rs BBN_FORWARD_TOKEN || true
printf '\n\n'
[ -n "$BBN_NWC_URI" ] && [ -n "$BBN_FORWARD_TOKEN" ] || {
	echo "both are required" >&2
	exit 1
}

export BBN_NWC_URI BBN_FORWARD_TOKEN
export BBN_FORWARD_URL="${BBN_FORWARD_URL:-https://musicsideproject.com/api/boosts/ingest}"
export BBN_RECIPIENT_NAMES="${BBN_RECIPIENT_NAMES:-MSP 2.0}"
export BBN_FORWARD_ACTIONS="${BBN_FORWARD_ACTIONS:-boost,auto,stream}"

echo "sending MSP split payments since unix time $FROM to $BBN_FORWARD_URL"
exec java -cp "$JAR" boostbox.forwardbackfill "$FROM"
