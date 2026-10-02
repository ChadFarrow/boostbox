#!/usr/bin/env bash
# One-off: move Boostr_Bot's MSP 2.0 notes to MSP 2.0's own npub.
#
#   clojure -T:build uber                                  # once
#   scripts/msp-notes.sh export                            # read-only
#   BBN_PUBLISH_GENERATORS='MSP 2.0 - Music Side Project Studio' \
#     scripts/msp-notes.sh repost [--apply] [--interval 15]  # MSP 2.0's nsec,
#                                                          # Podcast Index key
#   scripts/msp-notes.sh delete [--apply] [--interval 15]  # Boostr_Bot's nsec
#   BBN_RELAYS=wss://relay.fountain.fm \
#     scripts/msp-notes.sh delete --per-request 1 --interval 3 --apply
#
# export reads Boostr_Bot's MSP notes from the relays into
# ~/.config/boostbox/msp/boostr-msp-notes.json; repost and delete read only that
# file. Run export after the msp-bot has its own key, so it holds every note.
# repost and delete print what they would do and send nothing without --apply.
# A delete names fifty notes per request; relay.fountain.fm deletes only the
# first note a request names, so send it one per request, on its own.
# repost selects as the bot does: BBN_PUBLISH_GENERATORS (the <generator> that
# wrote the note's feed or the song's album) AND BBN_PUBLISH_FEED_GUIDS (album
# and artist guids), each passing when empty; with both empty it refuses. Both
# an artist and a generator are found by reading each feed, and the notes carry
# no feed address, so repost asks for the Podcast Index key and secret the bot
# uses (BBN_PI_KEY / BBN_PI_SECRET). With BBN_PUBLISH_GENERATORS they are
# required; with guids alone, press Enter to skip and only album guids match.
# Keys are read with echo off, so they never land in shell history or `ps`.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${JAR:-target/boostbox.jar}"

[ -f "$JAR" ] || {
	echo "missing $JAR -- run:  clojure -T:build uber" >&2
	exit 1
}

case "${1:-}" in
export) ;;
repost | delete)
	[ -t 0 ] || {
		echo "stdin is not a terminal -- run this from a normal terminal" >&2
		exit 1
	}
	if [ "$1" = repost ]; then who="MSP 2.0's"; else who="Boostr_Bot's"; fi
	printf 'Paste %s nsec (input hidden): ' "$who"
	read -rs BBN_NOSTR_SECKEY || true
	printf '\n\n'
	[ -n "$BBN_NOSTR_SECKEY" ] || {
		echo "no key given" >&2
		exit 1
	}
	export BBN_NOSTR_SECKEY
	if [ "$1" = repost ] && [ -z "${BBN_PI_KEY:-}" ]; then
		printf 'Podcast Index key (input hidden, Enter to skip): '
		read -rs BBN_PI_KEY || true
		printf '\nPodcast Index secret (input hidden, Enter to skip): '
		read -rs BBN_PI_SECRET || true
		printf '\n\n'
		export BBN_PI_KEY BBN_PI_SECRET
	fi
	;;
*)
	echo "usage: scripts/msp-notes.sh export|repost|delete [--apply] [--interval <sec>] [--per-request <n>]" >&2
	exit 2
	;;
esac

export BBN_CLIENT_NAME="${BBN_CLIENT_NAME:-MSP 2.0}"
exec java -cp "$JAR" boostbox.mspnotes "$@"
