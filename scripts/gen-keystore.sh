#!/usr/bin/env bash
# Creates the release keystore ONCE and records everything in fnox.local.toml (plain text).
# Losing this key means installed users can never update: back up .secrets/ and fnox.local.toml.
set -euo pipefail
cd "$(dirname "$0")/.."

KS=".secrets/release.jks"
[ -e "$KS" ] && { echo "$KS already exists; refusing to overwrite." >&2; exit 1; }
[ -e fnox.local.toml ] && grep -q SIGNING_STORE_PASSWORD fnox.local.toml && {
  echo "fnox.local.toml already has signing settings; refusing to overwrite." >&2; exit 1; }

mkdir -p .secrets
PASS="$(openssl rand -hex 16)"
ALIAS="jarvis"
keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Jarvis, O=kbhatiya999" >/dev/null

B64="$(base64 < "$KS" | tr -d '\n')"
{
  [ -s fnox.local.toml ] || echo "[secrets]"
  echo "SIGNING_KEYSTORE_PATH = { default = \"$PWD/$KS\" }"
  echo "SIGNING_STORE_PASSWORD = { default = \"$PASS\" }"
  echo "SIGNING_KEY_ALIAS = { default = \"$ALIAS\" }"
  echo "SIGNING_KEY_PASSWORD = { default = \"$PASS\" }"
  echo "SIGNING_KEYSTORE_B64 = { default = \"$B64\" }"
} >> fnox.local.toml
echo "Keystore created at $KS; settings written to fnox.local.toml (gitignored)."
