#!/usr/bin/env bash
# Linux host shader compiler; extraction is local and does not install a system package.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/artifacts/build/vulkan-tools"
ARCHIVE="$DEST/shaderc.deb"
URL='https://packages.lunarg.com/vulkan/1.4.309/pool/main/s/shaderc/shaderc_2025.1~rc1-1lunarg22.04-1_amd64.deb'
SHA='84a746486714289376be05c77657993a9636b4ef1f4d91de4f0bb0cfd9d521e3'
mkdir -p "$DEST"
if ! printf '%s  %s\n' "$SHA" "$ARCHIVE" | sha256sum -c --status 2>/dev/null; then
    curl -fLsS --retry 3 "$URL" -o "$ARCHIVE.part"
    printf '%s  %s\n' "$SHA" "$ARCHIVE.part" | sha256sum -c --status
    mv "$ARCHIVE.part" "$ARCHIVE"
fi
dpkg-deb -x "$ARCHIVE" "$DEST"
"$DEST/usr/bin/glslc" --version
printf '\nUse VULKAN_SDK=%q for the Android build.\n' "$DEST/usr"
