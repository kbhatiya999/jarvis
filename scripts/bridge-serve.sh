#!/usr/bin/env bash
# Starts the local bridge (and for "tunnel" a public https address for it) as Tilt services, and waits.
#   scripts/bridge-serve.sh dev|tunnel
set -euo pipefail
cd "$(dirname "$0")/.."
p="${BRIDGE_PORT:-$(fnox get BRIDGE_PORT 2>/dev/null || echo 8787)}"
scripts/tilt-serve.sh bridge-dev "curl -fsS http://localhost:$p/health >/dev/null && echo 'Bridge running: http://localhost:$p'"
if [ "$1" = tunnel ]; then
  scripts/tilt-serve.sh bridge-tunnel "grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' .tilt-inputs/logs/bridge-tunnel.log | tail -1 | sed 's/^/Public address: /'"
  echo "Put the public address in Jarvis › Connect Gemini › Bridge address, then copy the link from there into Gemini."
fi
echo "Both keep running in Tilt (http://localhost:10350); stop them there or with: mise run tilt:down"
