#!/usr/bin/env bash
# Step: tag the release and publish it to GitHub: the APK (Obtainium) and, when the iOS build ran,
# the .ipa plus the AltStore/SideStore feed (altstore-source.json).
set -euo pipefail
cd "$(dirname "$0")/.."
V="$(cat .tilt-inputs/version 2>/dev/null || true)"
[ -n "$V" ] || { echo "No release in progress; run the release pipeline." >&2; exit 1; }
APK="app/build/outputs/apk/release/jarvis-v$V.apk"
[ -f "$APK" ] || { echo "Missing $APK; the build step must run first." >&2; exit 1; }
assets=("$APK")
IPA="ios/build/jarvis-v$V.ipa"
if [ -f "$IPA" ]; then
  scripts/altstore-source.sh "$V" "$IPA" > ios/build/altstore-source.json
  assets+=("$IPA" ios/build/altstore-source.json)
else
  echo "No iOS build for v$V (needs Xcode); publishing Android only."
fi
git push -q origin main
git tag -a "v$V" -m "Jarvis $V"
git push -q origin "v$V"
gh release create "v$V" "${assets[@]}" --verify-tag --title "Jarvis $V" --generate-notes
rm -f .tilt-inputs/version
echo "Published v$V. Obtainium will offer it on its next check."
