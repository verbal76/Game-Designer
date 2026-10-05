#!/usr/bin/env bash
# Publishes a built channel directory as the GitHub prerelease "ota-<channel>" (never "latest").
# Usage: publish.sh <channel> <dir-with-manifest.json,manifest.sig,bundle.zip>
set -euo pipefail
CH="$1"; DIR="$2"; R="$GITHUB_REPOSITORY"; TAG="ota-$CH"
VER=$(python3 -c "import json;print(json.load(open('$DIR/manifest.json'))['bundleVersion'])")
NAME=$(python3 -c "import json;print(json.load(open('$DIR/manifest.json'))['versionName'])")
NOTES="OTA $CH channel. Latest bundle: v$VER ($NAME). Consumed by the Game Designer app; not a downloadable app. Replaced in place on each publish."
if gh release view "$TAG" --repo "$R" >/dev/null 2>&1; then
  gh release upload "$TAG" "$DIR/manifest.json" "$DIR/manifest.sig" "$DIR/bundle.zip" --clobber --repo "$R"
  gh release edit "$TAG" --notes "$NOTES" --prerelease --latest=false --repo "$R"
else
  gh release create "$TAG" "$DIR/manifest.json" "$DIR/manifest.sig" "$DIR/bundle.zip" --repo "$R" --target "${GITHUB_SHA}" \
    --title "OTA $CH channel" --notes "$NOTES" --prerelease --latest=false
fi
echo "published $TAG v$VER ($NAME)"
