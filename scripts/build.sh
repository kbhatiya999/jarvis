#!/usr/bin/env bash
# Step: build the signed APK. This is the only way Jarvis APKs are made: releases (from the
# Mac or GitHub Actions) and phone installs all use it, signed with the same key, so any copy can update any other.
# Version name: see scripts/version.sh (also used by the iOS build).
set -euo pipefail
cd "$(dirname "$0")/.."
KS="${SIGNING_KEYSTORE_PATH:-$(fnox get SIGNING_KEYSTORE_PATH)}"  # GitHub Actions sets it
[ -n "$KS" ] || { echo "No signing key. Run: mise run tilt:keystore-gen" >&2; exit 1; }
APP_VERSION="$(scripts/version.sh)"
export APP_VERSION
echo "Building Jarvis $APP_VERSION"
scripts/run.sh ./gradlew --no-daemon assembleRelease
cp app/build/outputs/apk/release/app-release.apk "app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
echo "APK: app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
