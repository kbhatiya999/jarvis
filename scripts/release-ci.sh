#!/usr/bin/env bash
# Step (fallback): have GitHub Actions build and publish instead of this Mac.
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:-$(cat .tilt-inputs/version 2>/dev/null || true)}"
[ -n "$V" ] || V="$(scripts/next-version.sh)"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Bad version '$V' (want x.y.z)" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first." >&2; exit 1; }
git push -q origin main
gh workflow run release.yml --ref main -f version="$V"
rm -f .tilt-inputs/version
echo "Started CI release of v$V. Follow it: gh run watch"
