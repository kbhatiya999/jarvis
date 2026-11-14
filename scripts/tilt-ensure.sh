#!/usr/bin/env bash
# Makes sure Tilt is running for this project; starts it in the background if not.
set -euo pipefail
cd "$(dirname "$0")/.."
if tilt get uiresource '(Tiltfile)' >/dev/null 2>&1; then
  # Leftover `tilt args` (inputs now go through .tilt-inputs/) break the Tiltfile: clear them.
  if tilt get uiresource '(Tiltfile)' -o jsonpath='{.status.buildHistory[0].error}' | grep -q 'could not be found'; then
    tilt args --clear
    sleep 3
  fi
  exit 0
fi
mkdir -p .tilt-inputs
echo "Starting Tilt in the background (dashboard: http://localhost:10350, log: .tilt-inputs/tilt.log)"
nohup tilt up --stream=true >.tilt-inputs/tilt.log 2>&1 &
for _ in $(seq 1 60); do
  sleep 1
  fin="$(tilt get uiresource '(Tiltfile)' -o jsonpath='{.status.buildHistory[0].finishTime}' 2>/dev/null || true)"
  [ -n "$fin" ] && exit 0
done
echo "Tilt did not come up; see .tilt-inputs/tilt.log" >&2
exit 1
