#!/usr/bin/env bash
# Prints the version name for a build, shared by the Android and iOS builds:
# the release in progress (.tilt-inputs/version) as "vX.Y.Z", otherwise a dev build as
# "vX.Y.Z-dev+<commit>" (X.Y.Z = latest release; ".dirty" for uncommitted changes).
set -euo pipefail
cd "$(dirname "$0")/.."
v="$(cat .tilt-inputs/version 2>/dev/null || true)"
if [ -n "$v" ]; then echo "v$v"; exit 0; fi
last="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || echo v0.0.0)"
dev="$last-dev+$(git rev-parse --short HEAD)"
[ -z "$(git status --porcelain)" ] || dev="$dev.dirty"
echo "$dev"
