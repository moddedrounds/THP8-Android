#!/usr/bin/env bash
# Build the host (Linux x86_64) `rexglue` recompiler from the Android-prepared SDK tree. codegen.sh
# runs it to translate YOUR default.xex into C++. Run tools/android/prepare_sdk.sh first.
#
#   tools/android/build_host_cli.sh [SDK_DIR]        default: ../rexglue-sdk-android
#
# Needs clang/clang++ (the SDK preset uses them), cmake >= 3.25 and ninja.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SDK="${1:-$(dirname "$REPO")/rexglue-sdk-android}"
[ -f "$SDK/.android-patches/.applied" ] || { echo "SDK tree $SDK is not prepared: run tools/android/prepare_sdk.sh"; exit 1; }
cd "$SDK"
cmake --preset linux-amd64
cmake --build --preset linux-amd64-release --target rexglue
echo "[ok] $SDK/out/linux-amd64/Release/rexglue"
