#!/usr/bin/env bash
# Prints the next patch version after the latest vX.Y.Z tag (0.4.0 -> 0.4.1).
set -euo pipefail
cd "$(dirname "$0")/.."
last="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -1)"
[ -n "$last" ] || { echo 0.1.0; exit; }
IFS=. read -r ma mi pa <<<"${last#v}"
echo "$ma.$mi.$((pa + 1))"
