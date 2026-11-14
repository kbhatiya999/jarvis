#!/usr/bin/env bash
# Local release: mise run release [x.y.z]   (no version = next patch)
# Lints, builds the signed APK on this Mac, tags, and publishes the GitHub release
# that Obtainium reads. Nothing happens on GitHub's side except storing the release.
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:-}"
[ -n "$V" ] || V="$(scripts/next-version.sh)"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: mise run release [x.y.z]" >&2; exit 1; }
TAG="v$V"

[ "$(git branch --show-current)" = main ] || { echo "Release from main only." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first." >&2; exit 1; }
git fetch -q --tags origin
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && { echo "Tag $TAG already exists." >&2; exit 1; }

echo "==> Releasing Jarvis $V"
mise run lint
APP_VERSION="$TAG" mise run build:release
APK="app/build/outputs/apk/release/jarvis-$TAG.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

git push -q origin main
git tag -a "$TAG" -m "Jarvis $V"
git push -q origin "$TAG"
gh release create "$TAG" "$APK" --verify-tag --title "Jarvis $V" --generate-notes
echo "==> Published $TAG. Obtainium will offer it on its next check."
