#!/usr/bin/env bash
# Step: decide the release version and make sure releasing it is safe.
# Uses .tilt-inputs/version if given, else the next patch; writes the result back there
# so the following steps (build-release, release-publish) use the same version.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tilt-inputs
V="$(cat .tilt-inputs/version 2>/dev/null || true)"
[ -n "$V" ] || V="$(scripts/next-version.sh)"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Bad version '$V' (want x.y.z)" >&2; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "Release from main only." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first." >&2; git status --short >&2; exit 1; }
git fetch -q --tags origin
! git rev-parse -q --verify "refs/tags/v$V" >/dev/null || { echo "Tag v$V already exists." >&2; exit 1; }
echo "$V" > .tilt-inputs/version
echo "Release v$V: checks passed"
