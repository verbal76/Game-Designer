#!/usr/bin/env bash
# Derives Android resources from the authoritative Hot Attic Games logo. The source file is never modified.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png
RES=app/src/main/res
# In-app splash (wide, full logo)
convert "$SRC" -resize 1280x -quality 90 -define webp:lossless=false "$RES/drawable-nodpi/hot_attic_logo.webp"
# Android 12+ system splash icon: square canvas, logo inside the circular safe zone
convert "$SRC" -resize 600x -background none -gravity center -extent 1152x1152 -quality 92 "$RES/drawable-nodpi/splash_icon_raster.png"
