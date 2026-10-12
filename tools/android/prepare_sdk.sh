#!/usr/bin/env bash
# Build the ReXGlue SDK tree used for the Android port.
#
#   v0.10.0
#   + third-party Android patches (downloaded here, pinned by commit and SHA-256, never stored in git)
#   + every patch in patches/rexglue-sdk-android/ in name order (the THP8 runtime stack rebased onto
#     that base, then this port's own fixes)
#
# Why a separate tree from the desktop one: the Android base patch changes the codegen template
# (pch_h.inja: ARM64 memory barriers, runtime physical-memory offset), so desktop and Android
# generated code must never share an SDK build.
#
# Idempotent: .android-patches/.applied lists what is in the tree; re-running applies only the
# patches that are not in it yet, so a new patch can be added to an existing tree.
#
#   tools/android/prepare_sdk.sh [SDK_DIR]        default: ../rexglue-sdk-android
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SDK="${1:-$(dirname "$REPO")/rexglue-sdk-android}"
SDK_URL=https://github.com/rexglue/rexglue-sdk.git
SDK_TAG=v0.10.0

# Third-party base: github.com/deivid22srk/hells-gate-recomp-android declares no licence, so it is
# fetched on the user's machine from this pinned commit and is not redistributed from here.
REF=0a79f938ab839c187d25bd7bf48e51e30a6150be
BASE_URL="https://raw.githubusercontent.com/deivid22srk/hells-gate-recomp-android/$REF/patches/sdk"
ANDROID_PATCH=rexglue-sdk-v0.10.0-android.patch
ANDROID_SHA=271ac5f0e4dece893fd3f18f93cfc96037987916def3545b1d6b253ab2dbf06b
PERF_PATCH=rexglue-sdk-v0.10.0-android-perf.patch
PERF_SHA=b85196c30136d1d24b1848af0bccd3b558392742cc11d391348666d76a6d0f10   # minus mmio_handler.cpp

OURS_DIR="$REPO/patches/rexglue-sdk-android"
MARK="$SDK/.android-patches/.applied"

if [ ! -f "$SDK/CMakeLists.txt" ]; then
  echo "== cloning $SDK_TAG (with submodules) into $SDK"
  git clone --branch "$SDK_TAG" --recurse-submodules --shallow-submodules --jobs 8 "$SDK_URL" "$SDK"
fi
cd "$SDK"
git config user.name  "${GIT_AUTHOR_NAME:-android-port}"
git config user.email "${GIT_AUTHOR_EMAIL:-android-port@localhost}"
mkdir -p .android-patches
touch "$MARK"
applied() { grep -qxF "$1" "$MARK"; }

if ! applied base; then
  [ "$(git describe --tags --exact-match 2>/dev/null || true)" = "$SDK_TAG" ] || { echo "SDK tree is not at $SDK_TAG"; exit 1; }
  git diff --quiet -- . ':!thirdparty' || { echo "SDK tree has local changes; refusing to patch it"; exit 1; }
  fetch() { # name sha
    local f=".android-patches/$1"
    [ -f "$f" ] || curl -fsSL -o "$f" "$BASE_URL/$1"
    echo "$2  $f" | sha256sum -c - >/dev/null || { echo "SHA-256 mismatch for $1 - refusing to apply"; exit 1; }
    tr -d '\r' < "$f" > "/tmp/$1"
  }
  fetch "$ANDROID_PATCH" "$ANDROID_SHA"
  fetch "$PERF_PATCH" "$PERF_SHA"
  echo "== applying the third-party Android base"
  git apply --whitespace=nowarn --index "/tmp/$ANDROID_PATCH"
  git apply --whitespace=nowarn --index --exclude=src/system/mmio_handler.cpp "/tmp/$PERF_PATCH"
  git commit -qm "base: $SDK_TAG + third-party Android patches ($REF)"
  echo base >> "$MARK"
fi

for patch in "$OURS_DIR"/*.patch; do
  name="$(basename "$patch")"
  applied "$name" && continue
  echo "== applying $name"
  git apply --whitespace=nowarn --unidiff-zero --index "$patch"
  git commit -qm "$name"
  echo "$name" >> "$MARK"
done
# Generated, not stored in a patch: the fused resolve shaders (patch 0021) as a C header, built from
# tools/android/resolve_shaders (one of them is derived from the SDK's own resolve copy shader).
if [ ! -f "$SDK/src/graphics/vulkan/direct_resolve_shaders.h" ] ||
   [ -n "$(find "$REPO/tools/android/resolve_shaders" -newer "$SDK/src/graphics/vulkan/direct_resolve_shaders.h")" ]; then
  SDK="$SDK" bash "$REPO/tools/android/resolve_shaders/build.sh" "$SDK/src/graphics/vulkan/direct_resolve_shaders.h"
fi
echo "[ok] SDK ready: $SDK ($(grep -c . "$MARK") entries applied)"
