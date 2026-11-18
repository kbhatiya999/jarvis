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
# How much the app logs by default (the user can change it in Settings > Logs). From LOG_LEVEL, else
# .tilt-inputs/log-level (tilt:release --log-level); else info for a release, debug for a dev build.
if [ -z "${LOG_LEVEL:-}" ]; then
  if [ -s .tilt-inputs/log-level ]; then LOG_LEVEL="$(cat .tilt-inputs/log-level)"
  elif [ -s .tilt-inputs/version ]; then LOG_LEVEL=info
  else LOG_LEVEL=debug; fi
fi
case "$LOG_LEVEL" in error|warn|info|debug) ;; *) echo "Bad log level '$LOG_LEVEL' (error, warn, info or debug)" >&2; exit 1 ;; esac
export LOG_LEVEL
echo "Log level: $LOG_LEVEL"
# The Jarvis bridge's address for Connect Gemini (tilt:bridge-deploy saves it); empty = users enter their own.
BRIDGE_URL="${BRIDGE_URL:-$(fnox get BRIDGE_URL 2>/dev/null || true)}"
export BRIDGE_URL
echo "Bridge: ${BRIDGE_URL:-none}"
echo "Building Jarvis $APP_VERSION"
scripts/run.sh ./gradlew --no-daemon assembleRelease
cp app/build/outputs/apk/release/app-release.apk "app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
echo "APK: app/build/outputs/apk/release/jarvis-$APP_VERSION.apk"
