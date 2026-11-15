#!/usr/bin/env bash
# Step: build the iOS app from ios/project.yml (XcodeGen) and the shared Kotlin module.
#   scripts/ios-build.sh ipa   unsigned .ipa for AltStore/SideStore (or Xcode), in ios/build/
#   scripts/ios-build.sh sim   simulator build (scripts/ios-run.sh launches it)
# Needs Xcode. With IOS_OPTIONAL=1 (the release pipeline) a Mac without Xcode skips iOS
# with a warning instead of failing; GitHub's macOS runners always build it.
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-ipa}"
if ! xcodebuild -version >/dev/null 2>&1; then
  msg="Xcode is needed for iOS builds. Install it from the App Store, open it once, then run: sudo xcode-select -s /Applications/Xcode.app"
  if [ -n "${IOS_OPTIONAL:-}" ]; then echo "SKIPPED iOS: $msg"; exit 0; fi
  echo "$msg" >&2; exit 1
fi
APP_VERSION="$(scripts/version.sh)"
short="$(sed -E 's/^v([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"$APP_VERSION")"   # CFBundleShortVersionString: x.y.z only
code=$(( $(date +%s) / 60 - 26297280 ))                                       # minutes since 2020, as on Android
echo "Building Jarvis for iOS $APP_VERSION ($mode)"
xcodegen generate --spec ios/project.yml --quiet
common=(-project ios/Jarvis.xcodeproj -scheme Jarvis -derivedDataPath ios/build/derived
        MARKETING_VERSION="$short" CURRENT_PROJECT_VERSION="$code")
if [ "$mode" = sim ]; then
  # Simulator builds are always signed: unsigned ones get a fresh Keychain on every install (API key and
  # Jarvis address lost). With your free Apple ID team (IOS_TEAM_ID) iOS also runs the Jarvis check-in
  # from Shortcuts, which it refuses for apps without a team.
  team="${IOS_TEAM_ID:-$(fnox get IOS_TEAM_ID 2>/dev/null || true)}"
  if [ -n "$team" ]; then sign=(CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$team")
  else sign=(CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=-); fi
  xcodebuild "${common[@]}" "${sign[@]}" -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build -quiet   # shared/ has no Intel simulator target
  app=ios/build/derived/Build/Products/Debug-iphonesimulator/Jarvis.app
  # Xcode always signs simulator apps without a team; iOS only runs Shortcuts actions (the Jarvis
  # check-in) of apps whose signature names a team, so re-sign with your free Apple ID certificate.
  if [ -n "$team" ]; then codesign -f -s "Apple Development" "$app"; fi
  echo "APP: $app"
else
  xcodebuild "${common[@]}" CODE_SIGNING_ALLOWED=NO -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' build -quiet
  rm -rf ios/build/ipa && mkdir -p ios/build/ipa/Payload
  cp -R ios/build/derived/Build/Products/Release-iphoneos/Jarvis.app ios/build/ipa/Payload/
  (cd ios/build/ipa && zip -qry "../jarvis-$APP_VERSION.ipa" Payload)
  echo "IPA: ios/build/jarvis-$APP_VERSION.ipa"
fi
