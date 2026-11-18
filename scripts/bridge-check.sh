#!/usr/bin/env bash
# Step: checks the Cloudflare setup for the bridge (token works, account, workers.dev address). Never prints the token.
set -euo pipefail
cd "$(dirname "$0")/.."
account="${CLOUDFLARE_ACCOUNT_ID:-$(fnox get CLOUDFLARE_ACCOUNT_ID 2>/dev/null || true)}"
[ -n "$account" ] || { echo "Not set up. Press \"Set up Cloudflare\" on bridge-check in Tilt (links there make the token), or run: mise run tilt:bridge-setup" >&2; exit 1; }
scripts/cloudflare.sh GET /user/tokens/verify >/dev/null || { echo "The Cloudflare token doesn't work any more. Run: mise run tilt:bridge-setup --reset" >&2; exit 1; }
echo "Token: works"
echo "Account: $(scripts/cloudflare.sh GET "/accounts/$account" | jq -r '.result.name')"
sub="$(scripts/cloudflare.sh GET "/accounts/$account/workers/subdomain" | jq -r '.result.subdomain // empty' || true)"
[ -n "$sub" ] || { echo "No workers.dev address. Run: mise run tilt:bridge-setup --reset" >&2; exit 1; }
echo "Bridge will be at: https://jarvis-bridge.$sub.workers.dev"
url="$(fnox get BRIDGE_URL 2>/dev/null || true)"
echo "BRIDGE_URL (put in app builds): ${url:-not set yet; mise run tilt:bridge-deploy sets it}"
