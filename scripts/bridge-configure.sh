#!/usr/bin/env bash
# Saves the bridge's Cloudflare setup in fnox.local.toml, without asking anything, so both the Tilt
# "Set up" button (bridge-check) and `mise run tilt:bridge-setup` (terminal, hidden input) can use it.
# Inputs (environment; the token never goes on a command line):
#   BRIDGE_SETUP_TOKEN      Cloudflare API token ("Edit Cloudflare Workers" template); empty = keep the saved one
#   BRIDGE_SETUP_ACCOUNT    account id or name, only needed if the token sees several accounts
#   BRIDGE_SETUP_SUBDOMAIN  workers.dev name to register, only needed if the account has none yet
#   BRIDGE_SETUP_GITHUB     "true" to also copy the token and account to GitHub secrets
# When something is missing it says exactly what to fill in and exits 1. Never prints the token.
set -euo pipefail
cd "$(dirname "$0")/.."
cf() { scripts/cloudflare.sh "$@"; }

token="${BRIDGE_SETUP_TOKEN:-}"
token="$(printf %s "$token" | tr -d '[:space:]')"
if [ -z "$token" ]; then
  token="$(fnox get CLOUDFLARE_API_TOKEN 2>/dev/null || true)"
  [ -n "$token" ] || {
    echo "Paste your Cloudflare API token, then run setup again."
    echo "  Make one: https://dash.cloudflare.com/profile/api-tokens › Create Token › \"Edit Cloudflare Workers\" › your account › Create."
    echo "  No account yet: https://dash.cloudflare.com/sign-up (free)"
    exit 1
  }
fi
export CLOUDFLARE_API_TOKEN="$token"

cf GET /user/tokens/verify >/dev/null 2>&1 || {
  echo "Cloudflare doesn't accept that token. Check you copied all of it and that it's active." >&2; exit 1; }
echo "Token: works"

accounts="$(cf GET '/accounts?per_page=50' 2>/dev/null || true)"
n="$(jq -r '.result | length' <<<"$accounts" 2>/dev/null || echo 0)"
want="${BRIDGE_SETUP_ACCOUNT:-}"
if [ "$n" = 0 ]; then
  echo "The token can't see any account. Make it again with Account Resources: your account." >&2; exit 1
elif [ -n "$want" ]; then
  account="$(jq -r --arg w "$want" '.result[] | select(.id == $w or (.name | ascii_downcase) == ($w | ascii_downcase)) | .id' <<<"$accounts" | head -1)"
  [ -n "$account" ] || { echo "No account \"$want\". The token sees: $(jq -r '[.result[].name] | join(", ")' <<<"$accounts")" >&2; exit 1; }
elif [ "$n" = 1 ]; then
  account="$(jq -r '.result[0].id' <<<"$accounts")"
else
  echo "The token sees $n accounts: $(jq -r '[.result[].name] | join(", ")' <<<"$accounts")."
  echo "Run setup again with Account set to one of these names."
  exit 1
fi
echo "Account: $(jq -r --arg a "$account" '.result[] | select(.id == $a) | .name' <<<"$accounts")"

sub="$(cf GET "/accounts/$account/workers/subdomain" 2>/dev/null | jq -r '.result.subdomain // empty' || true)"
if [ -z "$sub" ]; then
  name="$(printf %s "${BRIDGE_SETUP_SUBDOMAIN:-}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
  if [ -z "$name" ]; then
    echo "This account has no workers.dev address yet; the bridge would be at jarvis-bridge.<name>.workers.dev."
    echo "Run setup again with Subdomain set to a name, e.g. jarvis-$(openssl rand -hex 3)."
    exit 1
  fi
  cf PUT "/accounts/$account/workers/subdomain" "{\"subdomain\":\"$name\"}" >/dev/null 2>&1 \
    || { echo "Cloudflare refused \"$name\" (taken, or not letters, digits and dashes). Pick another." >&2; exit 1; }
  sub="$name"
  echo "Registered: $sub.workers.dev"
fi
echo "Bridge will be at: https://jarvis-bridge.$sub.workers.dev"

printf %s "$token" | scripts/fnox-local-set.sh CLOUDFLARE_API_TOKEN
printf %s "$account" | scripts/fnox-local-set.sh CLOUDFLARE_ACCOUNT_ID
echo "Saved CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID in fnox.local.toml."

if [ "${BRIDGE_SETUP_GITHUB:-}" = true ]; then
  printf %s "$token" | gh secret set CLOUDFLARE_API_TOKEN
  printf %s "$account" | gh secret set CLOUDFLARE_ACCOUNT_ID
  echo "Copied to GitHub secrets (for tilt:bridge-deploy-ci)."
fi
echo "Next: mise run tilt:bridge-deploy (or the bridge-deploy button)."
