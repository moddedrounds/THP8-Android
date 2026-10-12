#!/usr/bin/env bash
# Fetch libadrenotools (BSD-2-Clause, github.com/bylaws/libadrenotools) at a pinned commit into
# ../third_party/libadrenotools (next to the repo; not committed). Needed only for the optional
# custom Vulkan driver support (--android_vulkan_driver).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEST="$(dirname "$REPO")/third_party/libadrenotools"
COMMIT=8fae8ce254dfc1344527e05301e43f37dea2df80
mkdir -p "$(dirname "$DEST")"
[ -d "$DEST/.git" ] || git clone --recursive https://github.com/bylaws/libadrenotools.git "$DEST"
git -C "$DEST" fetch -q origin
git -C "$DEST" checkout -q "$COMMIT"
git -C "$DEST" submodule update --init --recursive -q
echo "libadrenotools at $(git -C "$DEST" rev-parse --short HEAD) in $DEST"
