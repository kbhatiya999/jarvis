#!/usr/bin/env bash
# Step: build for the iOS simulator, then install and launch Jarvis on a booted (or newly booted) iPhone simulator.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/tilt-input.sh version   # dev build
scripts/ios-build.sh sim
dev="$(xcrun simctl list devices booted | grep -oE '\(([0-9A-F-]{36})\)' | head -1 | tr -d '()' || true)"
if [ -z "$dev" ]; then
  dev="$(xcrun simctl list devices available | grep -E '^\s+iPhone' | grep -oE '[0-9A-F-]{36}' | tail -1)"
  [ -n "$dev" ] || { echo "No iPhone simulator. Add one in Xcode › Settings › Components." >&2; exit 1; }
  xcrun simctl boot "$dev"
fi
open -a Simulator
xcrun simctl install "$dev" ios/build/derived/Build/Products/Debug-iphonesimulator/Jarvis.app
xcrun simctl launch "$dev" com.kbhatiya999.jarvis
echo "Running on simulator $dev"
