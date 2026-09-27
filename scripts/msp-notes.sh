#!/usr/bin/env bash
# One-off: move Boostr_Bot's MSP 2.0 notes to MSP 2.0's own npub.
#
#   clojure -T:build uber                                  # once
#   scripts/msp-notes.sh export                            # read-only
#   BBN_PUBLISH_FEED_GUIDS=<guid>,<guid> \
#     scripts/msp-notes.sh repost [--apply] [--interval 15]  # MSP 2.0's nsec
#   scripts/msp-notes.sh delete [--apply]                  # Boostr_Bot's nsec
#
# export reads Boostr_Bot's MSP notes from the relays into
# ~/.config/boostbox/msp/boostr-msp-notes.json; repost and delete read only that
# file. Run export after the msp-bot has its own key, so it holds every note.
# repost and delete print what they would do and send nothing without --apply.
# The key is read with echo off, so it never lands in shell history or `ps`.
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
	;;
*)
	echo "usage: scripts/msp-notes.sh export|repost|delete [--apply] [--interval <sec>]" >&2
	exit 2
	;;
esac

export BBN_CLIENT_NAME="${BBN_CLIENT_NAME:-MSP 2.0}"
exec java -cp "$JAR" boostbox.mspnotes "$@"
