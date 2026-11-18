#!/usr/bin/env bash
# Once: the bridge's Cloudflare token and account, into fnox.local.toml. The terminal way, with the token
# typed hidden; the Tilt way is the "Set up" button on bridge-check (its input box shows what you paste).
# Both end in scripts/bridge-configure.sh.
#   scripts/bridge-setup.sh [--reset]
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" != --reset ] && [ -n "$(fnox get CLOUDFLARE_API_TOKEN 2>/dev/null || true)" ] \
   && scripts/cloudflare.sh GET /user/tokens/verify >/dev/null 2>&1; then
  echo "Already set up: the Cloudflare token in fnox.local.toml works. (--reset to replace it.)"
  exec scripts/tilt-run.sh bridge-check
fi

cat <<'TXT'
Jarvis bridge setup: a Cloudflare API token, once.

  1. No Cloudflare account yet? Sign up (free):  https://dash.cloudflare.com/sign-up
  2. Make a token:  https://dash.cloudflare.com/profile/api-tokens
     Create Token › "Edit Cloudflare Workers" (Use template) › Account Resources: your account
     › Continue to summary › Create Token › copy it.

TXT
if command -v open >/dev/null; then
  read -r -p "Open the token page in your browser? [Y/n] " yn
  [[ "$yn" =~ ^[Nn] ]] || open "https://dash.cloudflare.com/profile/api-tokens"
fi
read -r -s -p "Paste the token (hidden), then Enter: " BRIDGE_SETUP_TOKEN; echo
[ -n "$BRIDGE_SETUP_TOKEN" ] || { echo "No token given." >&2; exit 1; }
export BRIDGE_SETUP_TOKEN

# Ask only for what Cloudflare says is missing (several accounts, no workers.dev name), then save.
while ! out="$(scripts/bridge-configure.sh 2>&1)"; do
  printf '%s\n' "$out"
  if grep -q "Account set to" <<<"$out"; then read -r -p "Account name: " BRIDGE_SETUP_ACCOUNT; export BRIDGE_SETUP_ACCOUNT
  elif grep -q "Subdomain set to" <<<"$out"; then
    suggestion="$(grep -oE 'jarvis-[0-9a-f]{6}' <<<"$out" | head -1)"
    read -r -p "workers.dev name [$suggestion]: " BRIDGE_SETUP_SUBDOMAIN
    BRIDGE_SETUP_SUBDOMAIN="${BRIDGE_SETUP_SUBDOMAIN:-$suggestion}"; export BRIDGE_SETUP_SUBDOMAIN
  elif grep -q "refused" <<<"$out"; then read -r -p "Another workers.dev name: " BRIDGE_SETUP_SUBDOMAIN; export BRIDGE_SETUP_SUBDOMAIN
  else exit 1
  fi
done
printf '%s\n' "$out"

read -r -p "Also copy the token and account to GitHub secrets, for tilt:bridge-deploy-ci? [y/N] " yn
if [[ "$yn" =~ ^[Yy] ]]; then
  printf %s "$BRIDGE_SETUP_TOKEN" | gh secret set CLOUDFLARE_API_TOKEN
  printf %s "$(fnox get CLOUDFLARE_ACCOUNT_ID)" | gh secret set CLOUDFLARE_ACCOUNT_ID
  echo "Copied to GitHub secrets."
fi
unset BRIDGE_SETUP_TOKEN
exec scripts/tilt-run.sh bridge-check
