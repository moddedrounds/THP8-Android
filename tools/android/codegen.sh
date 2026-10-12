#!/usr/bin/env bash
# Generate the game's C++ for Android from YOUR default.xex.
#
# Uses the host `rexglue` built from the ANDROID SDK tree (its codegen template differs from desktop:
# ARM64 memory barriers and a runtime physical-memory offset), so Android output must never be mixed
# with the desktop `generated/` directory. The manifest is derived from the desktop one so the
# addresses are not duplicated.
#
#   tools/android/codegen.sh <dir containing default.xex> [SDK_DIR]
#
# Output (git-ignored, never commit): android/codegen/generated-android/default
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
GAME_ROOT="$(cd "${1:?usage: codegen.sh <dir containing default.xex> [SDK_DIR]}" && pwd)"
SDK="${2:-$(dirname "$REPO")/rexglue-sdk-android}"
CLI="$SDK/out/linux-amd64/Release/rexglue"
OUT="$REPO/android/codegen"

[ -f "$GAME_ROOT/default.xex" ] || { echo "no default.xex in $GAME_ROOT"; exit 1; }
[ -x "$CLI" ] || { echo "build the Android-tree CLI first (tools/android/build_host_cli.sh)"; exit 1; }

mkdir -p "$OUT"
MANIFEST="$OUT/thps_p8_manifest.local.toml"
sed -e "s#REPLACE_ME/sources/install-media/derived/disc#$GAME_ROOT#g" \
    -e 's#^out_directory_path = ".*"#out_directory_path = "generated-android/default"#' \
    "$REPO/config/thps_p8_manifest.toml" > "$MANIFEST"
grep -v '^[[:space:]]*#' "$MANIFEST" | grep -q REPLACE_ME && { echo "manifest still has REPLACE_ME"; exit 1; }

cd "$OUT"
LD_LIBRARY_PATH="$SDK/out/linux-amd64/Release/lib:${LD_LIBRARY_PATH:-}" "$CLI" codegen thps_p8_manifest.local.toml
echo "generated: $(ls generated-android/default/*.cpp | wc -l) .cpp files in $OUT/generated-android/default"
