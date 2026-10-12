#!/usr/bin/env bash
# Fetch the Mesa Turnip Vulkan drivers the app bundles: assets/drivers/turnip/vulkan.ad07xx.so
# (Adreno 6xx / 7xx) and assets/drivers/turnip8/libvulkan_freedreno.so (Adreno 8xx, experimental).
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

# Adreno 8xx: github.com/StevenMXZ/freedreno_turnip-CI release v37 "Turnip_Gen8_V37" (Mesa main + the
# turnip/gen8 branch of github.com/whitebelyash/mesa-unified, Vulkan 1.4.359; Mesa code is MIT).
DEST8="$REPO/android/app/src/main/assets/drivers/turnip8"
URL8=https://github.com/StevenMXZ/freedreno_turnip-CI/releases/download/v37/Turnip_Gen8_V37.zip
SHA8=d0335542ec996fe33e83dfbec0d86aed301da95d22b671a88b40a4722d115b48
curl -sL --fail -o "$TMP/turnip8.zip" "$URL8"
echo "$SHA8  $TMP/turnip8.zip" | sha256sum -c -
mkdir -p "$DEST8"
unzip -o -q "$TMP/turnip8.zip" libvulkan_freedreno.so -d "$DEST8"
ls -la "$DEST8"
