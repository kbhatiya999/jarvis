#!/usr/bin/env bash
# Run a command with env from fnox (fnox.toml + fnox.local.toml). Variables already set in
# the environment win over fnox (fnox alone would overwrite them).
keep=()
for k in $(grep -oE '^[A-Z][A-Z0-9_]* *=' "$(dirname "$0")/../fnox.toml" | tr -d ' ='); do
  [ -n "${!k:-}" ] && keep+=("$k=${!k}")
done
exec fnox exec -- env ${keep[@]+"${keep[@]}"} "$@"
