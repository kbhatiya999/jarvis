#!/usr/bin/env bash
# Fallback: have GitHub Actions build and publish instead of this Mac.
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:-}"
[ -n "$V" ] || V="$(scripts/next-version.sh)"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: mise run release:ci [x.y.z]" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first." >&2; exit 1; }
git push -q origin main
gh workflow run release.yml --ref main -f version="$V"
echo "Started CI release of $V. Follow it: gh run watch"
