#!/usr/bin/env bash
# Step: copy the signing key and passwords from fnox.local.toml into this repo's GitHub
# Actions secrets, so the GitHub workflows sign with the same key as the Mac.
set -euo pipefail
cd "$(dirname "$0")/.."
for k in SIGNING_KEYSTORE_B64 SIGNING_STORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD; do
  v="$(fnox get "$k")"
  [ -n "$v" ] || { echo "$k is empty; run: mise run tilt:keystore-gen" >&2; exit 1; }
  printf %s "$v" | gh secret set "$k"
  echo "set $k"
done
