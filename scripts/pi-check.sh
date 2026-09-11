#!/usr/bin/env bash
# Read-only check that a Podcast Index API key resolves a feed guid to a feed
# -- the lookup the bot does when an app sends a boost naming a show but no
# feed address (Castamatic, among others).
#
# Run this before setting BBN_PI_KEY/BBN_PI_SECRET on the deployed bot. It
# answers the only question that matters: does this key turn this show's guid
# into an address the bot can read art and npubs out of.
#
#   ./scripts/pi-check.sh <feed-guid>          # prompts for key and secret
#   ./scripts/pi-check.sh <feed-guid> pi.txt   # or reads them from a file,
#                                              #   key on line 1, secret on line 2
#
# Credentials are prompted with echo off rather than taken as arguments, where
# `ps` would expose them to every other user on the machine. Nothing is
# written, and only the key's first four characters are ever printed.

set -euo pipefail

cd "$(dirname "$0")/.."

GUID="${1:-}"

if [ -z "$GUID" ]; then
	echo "usage: $0 <feed-guid> [credentials-file]" >&2
	echo "" >&2
	echo "The feed guid is the <podcast:guid> from the show's RSS, and the same" >&2
	echo "value the bot puts in a note's podcast:guid tag." >&2
	exit 1
fi

if [ $# -ge 2 ]; then
	[ -f "$2" ] || {
		echo "no such file: $2" >&2
		exit 1
	}
	PI_KEY=$(sed -n '1p' "$2" | tr -d ' \r\n')
	PI_SECRET=$(sed -n '2p' "$2" | tr -d ' \r\n')
elif [ -t 0 ]; then
	printf 'Podcast Index API key (input hidden): '
	read -rs PI_KEY || true
	printf '\n'
	printf 'Podcast Index API secret (input hidden): '
	read -rs PI_SECRET || true
	printf '\n'
else
	echo "stdin is not a terminal -- run from a normal terminal window, or pass a file path" >&2
	exit 1
fi

[ -n "${PI_KEY:-}" ] && [ -n "${PI_SECRET:-}" ] || {
	echo "both a key and a secret are needed -- neither alone does anything" >&2
	exit 1
}

export BBN_PI_KEY="$PI_KEY"
export BBN_PI_SECRET="$PI_SECRET"
export GUID

echo ""
echo "key ${PI_KEY:0:4}… against guid $GUID"
echo ""

exec clojure -M -e '
(require (quote [boostbox.podcastindex :as pi])
         (quote [boostbox.safefetch :as sf])
         (quote [boostbox.feed :as feed]))
(let [cfg {:pi-key (System/getenv "BBN_PI_KEY")
           :pi-secret (System/getenv "BBN_PI_SECRET")
           :pi-timeout-ms 15000}
      guid (System/getenv "GUID")]
  (if-let [{:keys [url artwork pi-id]} (pi/feed-by-guid cfg guid)]
    (do
      (println "resolved:")
      (println "  feed url :" (or url "-- none, the API knows the show but not where it lives"))
      (println "  artwork  :" (or artwork "--"))
      (println "  pi feed id:" (or pi-id "--"))
      (println)
      ;; The address is only worth having if the feed behind it actually reads.
      (if-let [{:keys [body]} (and url (sf/fetch-pinned! url {:max-bytes 5000000 :timeout-ms 15000}))]
        (let [{:keys [npubs art]} (feed/read-feed (String. ^bytes body "UTF-8") nil)]
          (println "feed read ok --" (count body) "bytes")
          (println "  cover    :" (or art "-- none in the feed; the API artwork above would be used"))
          (println "  npubs    :" (count npubs))
          (doseq [n npubs] (println "    -" (:npub n)))
          (println)
          (println "This show will get art and" (count npubs) "p tag(s) on its boost notes."))
        (do (println "feed read FAILED -- the address resolved but could not be fetched.")
            (println "The note would publish with the API artwork and no p tags.")
            (System/exit 1))))
    (do
      (println "no result.")
      (println)
      (println "Either the key is wrong, or the Podcast Index does not have this guid.")
      (println "A wrong key and an unknown guid look the same here on purpose --")
      (println "run with MULOG_ENABLED to see the status the API returned.")
      (System/exit 1))))
'
