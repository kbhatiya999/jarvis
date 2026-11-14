#!/usr/bin/env bash
# Installs a release-signed build over the copy from Obtainium (same key, so data is kept).
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
"$ADB" get-state >/dev/null 2>&1 || { echo "No phone found. Plug it in with USB debugging on, then: mise run devices" >&2; exit 1; }
export APP_VERSION="$(git describe --tags --always)-local"
mise run build:release
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -n com.kbhatiya999.jarvis/.MainActivity >/dev/null
echo "Installed $APP_VERSION"
