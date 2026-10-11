#!/usr/bin/env bash
# Downloads the public domain NASA photos used by the sample event into data/sample-photos.
# Usage: scripts/fetch-sample-photos.sh [target-dir]
set -euo pipefail

cd "$(dirname "$0")/.."
target="${1:-data/sample-photos}"
mkdir -p "$target"

grep -v '^#' scripts/sample-photos.txt | while read -r id; do
  [ -z "$id" ] && continue
  file="$target/$id.jpg"
  if [ ! -s "$file" ]; then
    curl -fsSL --retry 3 -o "$file" "https://images-assets.nasa.gov/image/$id/$id~medium.jpg"
  fi
done

echo "$(ls "$target" | wc -l) sample photos in $target"
