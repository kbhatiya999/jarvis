#!/usr/bin/env bash
# Step: install the last release build on the connected phone and open Jarvis.
# Same signing key as Obtainium releases, so it upgrades in place and keeps data.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "No release build yet; run the build step first." >&2; exit 1; }
# Several phones may be connected (and Wi-Fi debugging can list one twice): prefer one that already has Jarvis,
# else the first. ANDROID_SERIAL picks one explicitly.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  for d in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
    if "$ADB" -s "$d" shell pm path com.kbhatiya999.jarvis >/dev/null 2>&1; then ANDROID_SERIAL="$d"; break; fi
  done
  ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
fi
export ANDROID_SERIAL
[ -n "$ANDROID_SERIAL" ] || { echo "No phone connected. Run: mise run tilt:phone-connect" >&2; exit 1; }
echo "Installing on $ANDROID_SERIAL"
"$ADB" install -r "$APK"
"$ADB" shell am start -n com.kbhatiya999.jarvis/.MainActivity >/dev/null
