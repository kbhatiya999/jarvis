#!/usr/bin/env bash
# mise run release 0.2.0  -> tags v0.2.0 and pushes; GitHub Actions builds and publishes the APK.
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:-${usage_version:-}}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: mise run release <x.y.z>" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first." >&2; exit 1; }
git rev-parse "v$V" >/dev/null 2>&1 && { echo "Tag v$V already exists." >&2; exit 1; }
git push origin HEAD
git tag -a "v$V" -m "Jarvis $V"
git push origin "v$V"
echo "Pushed v$V. Watch it: gh run watch"
