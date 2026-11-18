#!/usr/bin/env bash
# Saves one value in fnox.local.toml (gitignored, plain text, as the rest of it), replacing any old one.
#   printf %s "$value" | scripts/fnox-local-set.sh NAME
# The value comes on stdin so it never appears in the process list or shell history.
set -euo pipefail
cd "$(dirname "$0")/.."
name="$1"
value="$(cat)"
[[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "bad name '$name'" >&2; exit 1; }
[[ "$value" != *'"'* && "$value" != *'\'* && "$value" != *$'\n'* ]] || { echo "$name: value has quotes, backslashes or newlines" >&2; exit 1; }
touch fnox.local.toml
chmod 600 fnox.local.toml
grep -q '^\[secrets\]' fnox.local.toml || printf '[secrets]\n' >> fnox.local.toml
tmp="$(mktemp)"
grep -v "^$name = " fnox.local.toml > "$tmp" || true
printf '%s = { default = "%s" }\n' "$name" "$value" >> "$tmp"
cat "$tmp" > fnox.local.toml
rm -f "$tmp"
