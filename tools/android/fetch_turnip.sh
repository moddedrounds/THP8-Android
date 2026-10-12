#!/usr/bin/env bash
# Fetch the Mesa Turnip Vulkan driver the app bundles (assets/drivers/turnip/vulkan.ad07xx.so).
# Source: github.com/K11MCH1/AdrenoToolsDrivers release v26.0.0-rc08 (Mesa 26.0.0-devel, MIT), pinned by
# SHA-256. The binary is not committed; run this before tools/android/build_apk.sh. Without it the
# app simply runs on the system driver.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEST="$REPO/android/app/src/main/assets/drivers/turnip"
URL=https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v26.0.0-rc08/Turnip_v26.0.0_R8.zip
SHA=e634db0f929e2205e95511c769071817d0390180ec72c8e690bc76375e813715
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -sL --fail -o "$TMP/turnip.zip" "$URL"
echo "$SHA  $TMP/turnip.zip" | sha256sum -c -
mkdir -p "$DEST"
unzip -o -q "$TMP/turnip.zip" -d "$DEST"
ls -la "$DEST"
