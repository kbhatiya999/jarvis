#!/usr/bin/env bash
# Installs the Android SDK packages the app needs into mise's android-sdk. Idempotent.
set -euo pipefail
: "${ANDROID_HOME:?run via mise}"
PKGS=("platform-tools" "platforms;android-35" "build-tools;34.0.0")
missing=0
for p in "${PKGS[@]}"; do [ -d "$ANDROID_HOME/${p//;//}" ] || missing=1; done
[ "$missing" = 0 ] && { echo "Android SDK packages present."; exit 0; }
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "${PKGS[@]}"
