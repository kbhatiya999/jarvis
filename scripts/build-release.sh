#!/usr/bin/env bash
# Step: build the signed release APK.
# Version name: $APP_VERSION if set (CI), else the version of a release in progress
# (.tilt-inputs/version), else "<git describe>-local" for a phone install.
set -euo pipefail
cd "$(dirname "$0")/.."
KS="${SIGNING_KEYSTORE_PATH:-}"
[ -n "${CI:-}" ] || KS="$(fnox get SIGNING_KEYSTORE_PATH)"
[ -n "$KS" ] || { echo "No signing key. Run: mise run tilt:keystore-gen" >&2; exit 1; }
if [ -z "${APP_VERSION:-}" ]; then
  v="$(cat .tilt-inputs/version 2>/dev/null || true)"
  if [ -n "$v" ]; then APP_VERSION="v$v"; else APP_VERSION="$(git describe --tags --always)-local"; fi
fi
export APP_VERSION
echo "Building Jarvis $APP_VERSION"
scripts/run.sh ./gradlew --no-daemon assembleRelease
cp app/build/outputs/apk/release/app-release.apk "app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
echo "APK: app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
