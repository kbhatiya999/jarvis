#!/usr/bin/env bash
# Installs a release-signed build over the copy from Obtainium (same key, so data is kept).
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
# Pick one device (Wi-Fi debugging can list the same phone twice).
export ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
[ -n "$ANDROID_SERIAL" ] || { echo "No phone found. Connect it (mise run phone:connect, or USB), then: mise run devices" >&2; exit 1; }
echo "Installing on $ANDROID_SERIAL"
export APP_VERSION="$(git describe --tags --always)-local"
mise run build:release
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -n com.kbhatiya999.jarvis/.MainActivity >/dev/null
echo "Installed $APP_VERSION"
