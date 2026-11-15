#!/usr/bin/env bash
# Step: tag the release and publish it (with the APK) to GitHub, where Obtainium finds it.
set -euo pipefail
cd "$(dirname "$0")/.."
V="$(cat .tilt-inputs/version 2>/dev/null || true)"
[ -n "$V" ] || { echo "No release in progress; run the release pipeline." >&2; exit 1; }
APK="app/build/outputs/apk/release/jarvis-v$V.apk"
[ -f "$APK" ] || { echo "Missing $APK; the build step must run first." >&2; exit 1; }
git push -q origin main
git tag -a "v$V" -m "Jarvis $V"
git push -q origin "v$V"
gh release create "v$V" "$APK" --verify-tag --title "Jarvis $V" --generate-notes
rm -f .tilt-inputs/version
echo "Published v$V. Obtainium will offer it on its next check."
