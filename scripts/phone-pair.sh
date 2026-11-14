#!/usr/bin/env bash
# Step: pair with the phone over Wi-Fi using the address/code given to tilt:phone-pair.
set -euo pipefail
cd "$(dirname "$0")/.."
A="$(cat .tilt-inputs/pair-address 2>/dev/null || true)"
C="$(cat .tilt-inputs/pair-code 2>/dev/null || true)"
rm -f .tilt-inputs/pair-code
[ -n "$A" ] && [ -n "$C" ] || { echo "Usage: mise run tilt:phone-pair <ip:port> <code>" >&2; exit 1; }
"$ANDROID_HOME/platform-tools/adb" pair "$A" "$C"
