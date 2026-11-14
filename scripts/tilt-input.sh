#!/usr/bin/env bash
# Stores a value typed into a tilt:* task or Tilt button for the steps to read:
#   scripts/tilt-input.sh <name> [value]   (empty value clears it)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tilt-inputs
if [ -n "${2:-}" ]; then printf '%s\n' "$2" > ".tilt-inputs/$1"; else rm -f ".tilt-inputs/$1"; fi
