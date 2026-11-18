#!/usr/bin/env bash
# Step: tests the bridge, deploys it to Cloudflare Workers, checks it answers, and (on this Mac) saves its
# address as BRIDGE_URL in fnox.local.toml so app builds put it in "Connect Gemini". Same script on GitHub.
set -euo pipefail
cd "$(dirname "$0")/.."
export CLOUDFLARE_API_TOKEN="${CLOUDFLARE_API_TOKEN:-$(fnox get CLOUDFLARE_API_TOKEN 2>/dev/null || true)}"
export CLOUDFLARE_ACCOUNT_ID="${CLOUDFLARE_ACCOUNT_ID:-$(fnox get CLOUDFLARE_ACCOUNT_ID 2>/dev/null || true)}"
[ -n "$CLOUDFLARE_API_TOKEN" ] && [ -n "$CLOUDFLARE_ACCOUNT_ID" ] || { echo "Not set up. Run: mise run tilt:bridge-setup" >&2; exit 1; }
(cd bridge && npm test --silent)
out="$(cd bridge && wrangler deploy 2>&1)" || { printf '%s\n' "$out" >&2; exit 1; }
printf '%s\n' "$out" | grep -v -i token
url="$(grep -oE 'https://[a-z0-9.-]+\.workers\.dev' <<<"$out" | head -1)"
[ -n "$url" ] || { echo "Deployed, but couldn't find the address in wrangler's output." >&2; exit 1; }
# A new workers.dev address can take a few seconds to answer.
for _ in $(seq 1 20); do curl -fsS "$url/health" >/dev/null 2>&1 && break; sleep 3; done
curl -fsS "$url/health" || { echo "The bridge doesn't answer at $url yet; try again in a minute." >&2; exit 1; }
if [ -z "${CI:-}" ]; then
  printf %s "$url" | scripts/fnox-local-set.sh BRIDGE_URL
  echo "Saved BRIDGE_URL=$url in fnox.local.toml; the next app build uses it."
fi
echo "Bridge: $url"
