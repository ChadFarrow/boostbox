#!/usr/bin/env bash
# Validate an NWC connection string and mint the bot's Nostr key.
#
# Neither secret is printed or passed as an argument (argv is visible in `ps`):
# the URI is read silently into the environment, and the generated key is
# written straight to a 0600 file. Only non-secret fields reach the terminal.
#
#   ./scripts/bot-setup.sh
#
# Requires target/boostbox.jar (clojure -T:build uber).

set -euo pipefail

JAR="${JAR:-target/boostbox.jar}"
OUT_DIR="${OUT_DIR:-$HOME/.config/boostbox}"
OUT="$OUT_DIR/bot-secrets.env"

[ -f "$JAR" ] || {
	echo "missing $JAR -- run: clojure -T:build uber" >&2
	exit 1
}

# Either read the URI from a file given as $1, or prompt for it. The prompt
# needs a real terminal: run this from a normal terminal window, not through a
# harness that hands the script a non-interactive stdin.
if [ $# -ge 1 ]; then
	[ -f "$1" ] || {
		echo "no such file: $1" >&2
		exit 1
	}
	BBN_NWC_URI=$(tr -d '\r\n' <"$1")
elif [ -t 0 ]; then
	printf 'Paste the NWC connection string (input hidden): '
	# `|| true`: read reports EOF on input with no trailing newline, which would
	# otherwise trip `set -e`.
	read -rs BBN_NWC_URI || true
	printf '\n'
else
	cat >&2 <<'MSG'
stdin is not a terminal, so there is nothing to type into.

Run this from a normal terminal window:

    ./scripts/bot-setup.sh

Or, if you must run it non-interactively, put the connection string in a file
and pass the path (then delete the file):

    ./scripts/bot-setup.sh /path/to/nwc.txt
MSG
	exit 1
fi

export BBN_NWC_URI
printf '\n'

[ -n "$BBN_NWC_URI" ] || {
	echo "no connection string given" >&2
	exit 1
}

echo "~~~ NWC string ~~~"
java -cp "$JAR" clojure.main -e '
(require (quote [boostbox.nwc :as nwc]))
(let [p (nwc/parse-uri (System/getenv "BBN_NWC_URI"))]
  (println "  parsed OK")
  (println "  wallet pubkey :" (str (subs (:wallet-pubkey p) 0 12) "..."))
  (println "  relays        :" (clojure.string/join ", " (:relays p)))
  (println "  lud16         :" (or (:lud16 p) "(none -- set BBN_PROFILE_LUD16 by hand)"))
  (println "  secret        : present," (alength (bytes (:secret-bytes p))) "bytes"))' \
	|| {
		echo "  FAILED to parse -- copy the whole string, including the nostr+walletconnect:// prefix" >&2
		exit 1
	}

echo
echo "~~~ bot identity ~~~"
mkdir -p "$OUT_DIR"
umask 077
OUT_FILE="$OUT" java -cp "$JAR" clojure.main -e '
(require (quote [boostbox.nostr :as n]))
(let [k (byte-array 32)]
  (.nextBytes (java.security.SecureRandom.) k)
  (spit (System/getenv "OUT_FILE")
        (str "BBN_NOSTR_SECKEY=" (n/bytes->hex k) "\n"))
  (println "  npub :" (n/->npub (n/x-only-pubkey k))))'
chmod 600 "$OUT"

echo "  seckey written to $OUT (0600, never printed)"
echo
echo "Next: paste BBN_NWC_URI and the BBN_NOSTR_SECKEY from that file into"
echo "Railway's variables for the bot service. Keep BBN_DRY_RUN=1 for the"
echo "first run."
