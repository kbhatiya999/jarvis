#!/usr/bin/env bash
# Step: build the signed APK as a dev version and install it on the connected phone.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/tilt-input.sh version   # no release in progress: dev version name
scripts/build.sh
scripts/install-apk.sh
