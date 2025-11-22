#!/usr/bin/env bash

# TODO: assumes jq, curl

set -euo pipefail

LOCK_FILE="${1:-.}/deps-lock.json"
M2_DIR="${2:-.m2}"
M2_REPO="$M2_DIR/repository"

mkdir -p "$M2_REPO"

echo "Downloading $(jq '.["mvn-deps"] | length' "$LOCK_FILE") dependencies..."

jq -r '.["mvn-deps"][] | [.["mvn-repo"], .["mvn-path"]] | @tsv' "$LOCK_FILE" | while read -r repo path; do
	target_file="$M2_REPO/$path"
	target_dir=$(dirname "$target_file")
	mkdir -p "$target_dir"

	url="${repo%/}/$path"
	curl -sf -o "$target_file" "$url" && echo "✓ $path" || echo "✗ $path"
done

echo "Done! Repo: $M2_REPO"
