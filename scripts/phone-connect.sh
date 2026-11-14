#!/usr/bin/env bash
# Step: connect to the paired phone over Wi-Fi (found automatically, or .tilt-inputs/connect-address).
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$ANDROID_HOME/platform-tools/adb"
addr="$(cat .tilt-inputs/connect-address 2>/dev/null || true)"
rm -f .tilt-inputs/connect-address
[ -n "$addr" ] || addr="$("$ADB" mdns services | awk '/_adb-tls-connect/ {print $NF; exit}')"
[ -n "$addr" ] || { echo "No paired phone found on Wi-Fi. Is Wireless debugging on? Else: mise run tilt:phone-connect <ip:port>" >&2; exit 1; }
"$ADB" connect "$addr"
"$ADB" devices -l
