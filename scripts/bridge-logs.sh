#!/usr/bin/env bash
# Live logs of the deployed bridge. Only "tell_jarvis sent/failed" lines: it never logs text or tokens.
set -euo pipefail
cd "$(dirname "$0")/.."
export CLOUDFLARE_API_TOKEN="${CLOUDFLARE_API_TOKEN:-$(fnox get CLOUDFLARE_API_TOKEN 2>/dev/null || true)}"
export CLOUDFLARE_ACCOUNT_ID="${CLOUDFLARE_ACCOUNT_ID:-$(fnox get CLOUDFLARE_ACCOUNT_ID 2>/dev/null || true)}"
[ -n "$CLOUDFLARE_API_TOKEN" ] || { echo "Not set up. Run: mise run tilt:bridge-setup" >&2; exit 1; }
cd bridge && exec wrangler tail --format pretty
