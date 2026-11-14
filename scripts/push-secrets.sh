#!/usr/bin/env bash
# Copies signing settings from fnox.local.toml into GitHub Actions secrets for this repo.
set -euo pipefail
cd "$(dirname "$0")/.."
for k in SIGNING_KEYSTORE_B64 SIGNING_STORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD; do
  v="$(fnox get "$k")"
  [ -n "$v" ] || { echo "$k is empty; run: mise run keystore:gen" >&2; exit 1; }
  printf %s "$v" | gh secret set "$k"
  echo "set $k"
done
