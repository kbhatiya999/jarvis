#!/usr/bin/env bash
# Step: build the signed APK. This is the only way Jarvis APKs are made; releases and phone
# installs both use it, signed with the same key, so any copy can update any other.
# Version name: the release in progress (.tilt-inputs/version) as "vX.Y.Z", otherwise a dev
# build as "vX.Y.Z-dev+<commit>" (X.Y.Z = latest release; ".dirty" for uncommitted changes).
set -euo pipefail
cd "$(dirname "$0")/.."
KS="$(fnox get SIGNING_KEYSTORE_PATH)"
[ -n "$KS" ] || { echo "No signing key. Run: mise run tilt:keystore-gen" >&2; exit 1; }
v="$(cat .tilt-inputs/version 2>/dev/null || true)"
if [ -n "$v" ]; then
  APP_VERSION="v$v"
else
  last="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || echo v0.0.0)"
  APP_VERSION="$last-dev+$(git rev-parse --short HEAD)"
  [ -z "$(git status --porcelain)" ] || APP_VERSION="$APP_VERSION.dirty"
fi
export APP_VERSION
echo "Building Jarvis $APP_VERSION"
scripts/run.sh ./gradlew --no-daemon assembleRelease
cp app/build/outputs/apk/release/app-release.apk "app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
echo "APK: app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
