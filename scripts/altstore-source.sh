#!/usr/bin/env bash
# Prints the AltStore/SideStore source (app feed) for a release: scripts/altstore-source.sh <x.y.z> <ipa>
# Published as a release asset, so the feed URL below always points at the latest release:
#   https://github.com/<owner>/<repo>/releases/latest/download/altstore-source.json
set -euo pipefail
V="$1"; IPA="$2"
repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
size=$(stat -f%z "$IPA" 2>/dev/null || stat -c%s "$IPA")
cat <<JSON
{
  "name": "Jarvis",
  "identifier": "com.kbhatiya999.jarvis.source",
  "subtitle": "Your J.A.R.V.I.S. voice assistant",
  "website": "https://github.com/$repo",
  "apps": [
    {
      "name": "Jarvis",
      "bundleIdentifier": "com.kbhatiya999.jarvis",
      "developerName": "${repo%%/*}",
      "subtitle": "Voice assistant on Gemini Live",
      "localizedDescription": "Talk to J.A.R.V.I.S. (Gemini Live), keep personal reminders, and send messages to friends' Jarvises.",
      "iconURL": "https://raw.githubusercontent.com/$repo/main/ios/icon.png",
      "tintColor": "4FC3F7",
      "category": "utilities",
      "versions": [
        {
          "version": "$V",
          "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
          "downloadURL": "https://github.com/$repo/releases/download/v$V/jarvis-v$V.ipa",
          "size": $size,
          "minOSVersion": "17.0"
        }
      ],
      "appPermissions": {
        "entitlements": [],
        "privacy": { "NSMicrophoneUsageDescription": "Jarvis listens when you tap Talk, so you can speak to it." }
      }
    }
  ],
  "news": []
}
JSON
