#!/usr/bin/env bash
# Step: shows Jarvis's own log from the connected phone (dev builds from tilt:install keep it where adb can read
# it; releases keep it private, users share it from Settings > Logs). Optional filter: a tag or word.
#   scripts/phone-logs.sh [filter] [lines]
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
f=/sdcard/Android/data/com.kbhatiya999.jarvis/files/logs/jarvis.log
# Several phones may be connected: use the one that has a Jarvis dev log (or ANDROID_SERIAL if set).
if [ -z "${ANDROID_SERIAL:-}" ]; then
  for d in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
    if "$ADB" -s "$d" shell "test -s $f" 2>/dev/null; then ANDROID_SERIAL="$d"; break; fi
  done
fi
[ -n "${ANDROID_SERIAL:-}" ] || { echo "No connected phone has a Jarvis dev log (install with tilt:install, open Jarvis once)." >&2; exit 1; }
export ANDROID_SERIAL
echo "Phone: $("$ADB" shell getprop ro.product.model | tr -d '\r') ($ANDROID_SERIAL)"
filter="${1:-}"; lines="${2:-200}"
out="$("$ADB" shell "cat $f 2>/dev/null")" || true
[ -n "$out" ] || { echo "No log on $ANDROID_SERIAL yet (a dev build from tilt:install keeps it there; open Jarvis once)." >&2; exit 1; }
if [ -n "$filter" ]; then grep -i -- "$filter" <<<"$out" | tail -n "$lines"; else tail -n "$lines" <<<"$out"; fi
