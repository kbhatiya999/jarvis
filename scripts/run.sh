#!/usr/bin/env bash
# Run a command with env from fnox. In CI the env comes from workflow secrets instead
# (fnox defaults would otherwise overwrite them).
if [ -n "${CI:-}" ]; then exec "$@"; else exec fnox exec -- "$@"; fi
