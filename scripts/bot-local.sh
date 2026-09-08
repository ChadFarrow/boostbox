#!/usr/bin/env bash
# Run the boost bot locally, read-only, to see what it actually makes of the
# payments already in the wallet.
#
#   ./scripts/bot-local.sh            # prompts for the NWC string
#   ./scripts/bot-local.sh nwc.txt    # or reads it from a file
#   HOURS=6 ./scripts/bot-local.sh    # reach further back (default 1 hour)
#
# Safe to run while the deployed bot is up:
#
#   - BBN_DRY_RUN=1, so nothing is ever published to a relay.
#   - Its cursor lives in a local temp directory, so the deployed bot's own
#     cursor is untouched.
#   - The BoostBox API key is deliberately a dummy. A boost whose metadata came
#     from a boost link needs no POST at all; one that came from a TLV will try
#     to POST, be rejected, and log `boost-publish-failed`. That rejection is a
#     *result*, not a fault -- it means the TLV path worked.
#
# What to look for, in order:
#
#   nwc-connected            the wallet is reachable at all
#   poll :transactions N     N payments seen since the cursor
#   poll :boosts M           M of them had a readable boostagram
#   dry-run-note             the event it would have published
#
# transactions > 0 with boosts = 0 is the interesting failure: the payment
# arrived but carried no metadata the bot could read.

set -euo pipefail

cd "$(dirname "$0")/.."

JAR="${JAR:-target/boostbox.jar}"
HOURS="${HOURS:-1}"
SECRETS="${SECRETS:-$HOME/.config/boostbox/bot-secrets.env}"
STATE="$(mktemp -d -t boostbot-local-XXXXXX)"

[ -f "$JAR" ] || {
	echo "missing $JAR -- run:  clojure -T:build uber" >&2
	exit 1
}
[ -f "$SECRETS" ] || {
	echo "missing $SECRETS (expected a BBN_NOSTR_SECKEY=... line)" >&2
	exit 1
}

BBN_NOSTR_SECKEY=$(grep -m1 '^BBN_NOSTR_SECKEY=' "$SECRETS" | cut -d= -f2-)
[ -n "$BBN_NOSTR_SECKEY" ] || {
	echo "no BBN_NOSTR_SECKEY line in $SECRETS" >&2
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
	printf '\n\n'
else
	echo "stdin is not a terminal -- run from a normal terminal, or pass a file path" >&2
	exit 1
fi

[ -n "$BBN_NWC_URI" ] || {
	echo "no connection string given" >&2
	exit 1
}

echo "reaching back ${HOURS}h; state in $STATE; nothing will be published"
echo "ctrl-c to stop"
echo

export BBN_NWC_URI BBN_NOSTR_SECKEY
export BBN_BOOSTBOX_API_KEY="local-diagnostic-not-a-real-key"
export BBN_BOOSTBOX_URL="${BBN_BOOSTBOX_URL:-https://tardbox.com}"
export BBN_DRY_RUN=1
export BBN_BACKFILL_SEC=$((HOURS * 3600))
export BBN_POLL_INTERVAL_SEC="${BBN_POLL_INTERVAL_SEC:-30}"
export ENV=DEV
export BB_STORAGE=FS
export BB_FS_ROOT_PATH="$STATE"

exec java -cp "$JAR" boostbox.nostrbot
