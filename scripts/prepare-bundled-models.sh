#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
assets=app/src/main/assets/models
while read -r digest name url; do
  target="$assets/$name"
  if printf '%s  %s\n' "$digest" "$target" | sha256sum -c --status; then
    continue
  fi
  mkdir -p "$(dirname "$target")"
  temporary=$(mktemp)
  trap 'rm -f "$temporary"' EXIT
  curl -fLsS --retry 2 "$url" -o "$temporary"
  printf '%s  %s\n' "$digest" "$temporary" | sha256sum -c -
  mv "$temporary" "$target"
  trap - EXIT
done < scripts/bundled-models.txt
