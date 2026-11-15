#!/usr/bin/env bash
# Step: install the last release build on the connected phone and open Jarvis.
# Same signing key as Obtainium releases, so it upgrades in place and keeps data.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "No release build yet; run the build step first." >&2; exit 1; }
# Wi-Fi debugging can list the same phone twice: pick one.
export ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
[ -n "$ANDROID_SERIAL" ] || { echo "No phone connected. Run: mise run tilt:phone-connect" >&2; exit 1; }
echo "Installing on $ANDROID_SERIAL"
"$ADB" install -r "$APK"
"$ADB" shell am start -n com.kbhatiya999.jarvis/.MainActivity >/dev/null
