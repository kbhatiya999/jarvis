#!/usr/bin/env bash
# Small Cloudflare API helper for the bridge scripts. Never prints the token.
#   scripts/cloudflare.sh GET /user/tokens/verify
#   scripts/cloudflare.sh PUT /accounts/<id>/workers/subdomain '{"subdomain":"x"}'
# Token: $CLOUDFLARE_API_TOKEN, else fnox. Prints the JSON reply; exits 1 if Cloudflare says success=false.
set -euo pipefail
cd "$(dirname "$0")/.."
method="$1"; path="$2"; body="${3:-}"
token="${CLOUDFLARE_API_TOKEN:-$(fnox get CLOUDFLARE_API_TOKEN 2>/dev/null || true)}"
[ -n "$token" ] || { echo "No Cloudflare token. Run: mise run tilt:bridge-setup" >&2; exit 1; }
args=(-sS -X "$method" "https://api.cloudflare.com/client/v4$path" -H "Content-Type: application/json" -K -)
[ -n "$body" ] && args+=(--data "$body")
# The token goes to curl on stdin (-K -), not on the command line.
out="$(printf 'header = "Authorization: Bearer %s"\n' "$token" | curl "${args[@]}")"
printf '%s\n' "$out"
[ "$(jq -r '.success' <<<"$out" 2>/dev/null)" = true ]
