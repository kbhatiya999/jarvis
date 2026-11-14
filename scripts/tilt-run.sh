#!/usr/bin/env bash
# Presses Tilt resource buttons one after another, streams each one's log here, waits for
# it to finish, and stops at the first failure. Used by every `mise run tilt:*` task and by
# the pipeline resources in the Tiltfile, so every step shows up in the Tilt dashboard.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/tilt-ensure.sh

field() { tilt get uiresource "$1" -o jsonpath="{$2}" 2>/dev/null || true; }

for r in "$@"; do
  before="$(field "$r" .status.buildHistory[0].finishTime)"
  log=".tilt-inputs/logs/$r.log"
  mkdir -p .tilt-inputs/logs
  : >"$log"
  echo "==> $r"
  tilt trigger "$r" >/dev/null
  # Follow this run's log (the resource tees into it). $! must be tail itself so killing it
  # ends the stream.
  tail -n +1 -F "$log" 2>/dev/null > >(sed -u "s/^/  /") &
  follower=$!
  while :; do
    sleep 1
    fin="$(field "$r" .status.buildHistory[0].finishTime)"
    state="$(field "$r" .status.updateStatus)"
    [ -n "$fin" ] && [ "$fin" != "$before" ] && [ "$state" != in_progress ] && [ "$state" != pending ] && break
  done
  sleep 1
  kill "$follower" 2>/dev/null || true
  wait "$follower" 2>/dev/null || true
  err="$(field "$r" .status.buildHistory[0].error)"
  if [ -n "$err" ]; then
    echo "xx $r failed: $err" >&2
    exit 1
  fi
  echo "ok $r"
done
