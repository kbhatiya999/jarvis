#!/usr/bin/env bash
# Starts a long-running Tilt service (bridge-dev, bridge-tunnel) and waits until it is ready.
#   scripts/tilt-serve.sh <resource> <ready command>
# The service keeps running in Tilt (dashboard: http://localhost:10350); stop it there or with tilt:down.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/tilt-ensure.sh
r="$1"; ready="$2"
tilt enable "$r" >/dev/null 2>&1 || true
tilt trigger "$r" >/dev/null
for _ in $(seq 1 300); do
  if out="$(bash -c "$ready" 2>/dev/null)" && [ -n "$out" ]; then printf '%s\n' "$out"; exit 0; fi
  sleep 1
done
echo "$r didn't become ready in 60 s; see: mise run tilt:logs $r" >&2
exit 1
