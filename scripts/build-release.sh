#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KS="${SIGNING_KEYSTORE_PATH:-}"
[ -n "${CI:-}" ] || KS="$(fnox get SIGNING_KEYSTORE_PATH)"
[ -n "$KS" ] || { echo "No keystore; run: mise run keystore:gen" >&2; exit 1; }
scripts/run.sh ./gradlew --no-daemon assembleRelease
ls -l app/build/outputs/apk/release/*.apk
