#!/usr/bin/env bash
# Read-only check that a Mastodon token can post boosts from the account you
# think it can -- and that this process can actually draw the banner it would
# attach.
#
# Run this before setting BBN_MASTODON_URL/BBN_MASTODON_TOKEN on the deployed
# bot. It answers the three things that go wrong quietly:
#
#   1. Whose account is this token for, and does the instance consider it a bot?
#      An automated account that is not flagged as one is what admins suspend.
#   2. What does this instance actually allow in a status? Instances raise the
#      stock 500, and the only way to find out you guessed low is truncated
#      posts in production.
#   3. Does the banner render here? The runtime image ships no fonts, and a
#      missing one draws a blank picture rather than throwing -- so a check that
#      only talks to the API would pass on a bot that posts empty frames.
#
#   ./scripts/mastodon-check.sh https://podcastindex.social          # prompts
#   ./scripts/mastodon-check.sh https://podcastindex.social tok.txt  # or reads
#                                                                    #   a file
#
# Nothing is posted, and the token is prompted for with echo off rather than
# taken as an argument, where `ps` would expose it to every other user on the
# machine. Only the account's public fields are printed.

set -euo pipefail

cd "$(dirname "$0")/.."

INSTANCE="${1:-}"

if [ -z "$INSTANCE" ]; then
	echo "usage: $0 <instance-url> [token-file]" >&2
	echo "" >&2
	echo "The instance URL is the one the account lives on, scheme included," >&2
	echo "e.g. https://podcastindex.social -- the same value BBN_MASTODON_URL" >&2
	echo "takes." >&2
	exit 1
fi

case "$INSTANCE" in
https://*) ;;
*)
	echo "the instance URL must start with https:// -- got: $INSTANCE" >&2
	exit 1
	;;
esac

if [ $# -ge 2 ]; then
	[ -f "$2" ] || {
		echo "no such file: $2" >&2
		exit 1
	}
	TOKEN=$(tr -d ' \r\n' <"$2")
elif [ -t 0 ]; then
	printf 'Mastodon access token (input hidden): '
	# `|| true`: read reports EOF on input with no trailing newline, which would
	# otherwise trip `set -e`.
	read -rs TOKEN || true
	printf '\n'
else
	echo "stdin is not a terminal -- run from a normal terminal window, or pass a file path" >&2
	exit 1
fi

[ -n "${TOKEN:-}" ] || {
	echo "no token given" >&2
	exit 1
}

export BBN_MASTODON_URL="$INSTANCE"
export BBN_MASTODON_TOKEN="$TOKEN"

echo ""
echo "token ${TOKEN:0:4}… against $INSTANCE"
echo ""

exec clojure -M -e '
(require (quote [boostbox.mastodon :as m])
         (quote [boostbox.boostagram :as bg])
         (quote [boostbox.banner :as banner]))
;; the bot sets this in -main for the same reason: an un-headless JVM throws on
;; the first Java2D draw
(System/setProperty "java.awt.headless" "true")
(let [cfg {:mastodon-url (System/getenv "BBN_MASTODON_URL")
           :mastodon-token (System/getenv "BBN_MASTODON_TOKEN")
           :mastodon-timeout-ms 15000}]
  (println "~~~ account ~~~")
  (if-let [{:keys [acct display-name bot?]} (m/verify-credentials cfg)]
    (do
      (println "  acct         :" acct)
      (println "  display name :" (if (clojure.string/blank? (str display-name)) "--" display-name))
      (println "  flagged bot  :" (if bot? "yes" "NO -- tick \"This is an automated account\" in Preferences -> Profile"))
      (println))
    (do
      (println "  FAILED.")
      (println)
      (println "  Either the token is wrong or revoked, or this is not a Mastodon")
      (println "  instance. A bad token and a wrong host look the same here on")
      (println "  purpose -- run with MULOG_ENABLED to see what came back.")
      (System/exit 1)))

  (println "~~~ status limit ~~~")
  (let [limit (m/instance-max-chars cfg)]
    (if limit
      (do (println "  max characters :" limit)
          (when (not= limit bg/default-mastodon-max-chars)
            (println "  NOTE: not the stock" bg/default-mastodon-max-chars "-- set BBN_MASTODON_MAX_CHARS=" limit)))
      (println "  unknown -- the bot will assume" bg/default-mastodon-max-chars)))
  (println)

  (println "~~~ banner ~~~")
  (let [b (bg/normalize {"podcast" "Podcasting 2.0"
                         "episode" "Episode 158: The Big One"
                         "sender_name" "Alice"
                         "message" "Great show!"})
        png (banner/banner-png (bg/banner-params b nil 2100000) "Boostr")]
    (if (and png (> (alength png) 5000))
      (println "  renders ok --" (alength png) "bytes")
      (do (println "  SUSPICIOUS --" (if png (alength png) 0) "bytes.")
          (println "  A banner this small is a blank picture: the bundled font did not load.")
          (println "  On the runtime image that means freetype/fontconfig are missing.")
          (System/exit 1)))
    (println)
    (println "~~~ a post would read ~~~")
    (println)
    (let [text (bg/->mastodon-content b {:received-msat 2100000
                                         :fediverse "https://example.social/@show"
                                         :max-chars (or (m/instance-max-chars cfg)
                                                        bg/default-mastodon-max-chars)})]
      (println text)
      (println)
      (println "  (" (count text) "characters, alt text:" (pr-str (bg/banner-alt-text b 2100000)) ")")))
  (println)
  (println "Nothing was posted."))
'
