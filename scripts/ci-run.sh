#!/usr/bin/env bash
# Step: run a GitHub workflow (the same scripts as on this Mac) and wait for it.
#   scripts/ci-run.sh build     dev APK; downloaded to app/build/outputs/apk/ci/
#   scripts/ci-run.sh release   release (.tilt-inputs/version, or empty = next patch)
# GitHub builds what is on origin/main, so local commits on main are pushed first.
set -euo pipefail
cd "$(dirname "$0")/.."
kind="$1"
[ "$(git branch --show-current)" = main ] || { echo "Switch to main first; GitHub builds main." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes; commit first (GitHub only sees commits)." >&2; git status --short >&2; exit 1; }
git fetch -q origin main
if [ -n "$(git rev-list origin/main..main)" ]; then echo "Pushing main"; git push -q origin main; fi

args=()
if [ "$kind" = release ]; then
  V="$(cat .tilt-inputs/version 2>/dev/null || true)"
  args=(-f "version=$V")
  rm -f .tilt-inputs/version
  echo "Release ${V:-<next patch>} on GitHub"
fi
out="$(gh workflow run "$kind.yml" --ref main ${args[@]+"${args[@]}"} 2>&1)"
id="$(grep -oE 'runs/[0-9]+' <<<"$out" | head -1 | cut -d/ -f2 || true)"
if [ -z "$id" ]; then   # older gh doesn't print the run: take the newest one
  sleep 5
  id="$(gh run list --workflow "$kind.yml" -L 1 --json databaseId -q '.[0].databaseId')"
fi
echo "Run: $(gh run view "$id" --json url -q .url)"
gh run watch "$id" --exit-status --interval 15 || { gh run view "$id" --log-failed | tail -40; exit 1; }

if [ "$kind" = build ]; then
  dir=app/build/outputs/apk/ci
  rm -rf "$dir"
  gh run download "$id" -n jarvis-apk -D "$dir"
  echo "APK: $(ls "$dir"/*.apk)"
fi
