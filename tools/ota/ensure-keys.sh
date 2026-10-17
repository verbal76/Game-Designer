#!/usr/bin/env bash
# Loads (or on first use creates) the persistent signing material from a PRIVATE DRAFT release named "gd-signing-keys".
# Draft releases are visible only to people with push access, so this is as private as repository secrets, and it does not
# depend on branches or cache eviction. Material: OTA signing key pair, Android release keystore + password.
# Usage: ensure-keys.sh <outdir>   (needs GH_TOKEN with contents:write, GITHUB_REPOSITORY)
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
R="$GITHUB_REPOSITORY"
id=$(gh api "repos/$R/releases?per_page=100" --jq '.[] | select(.draft==true and .name=="gd-signing-keys") | .id' | head -1 || true)
if [ -n "${id:-}" ]; then
  echo "Loading existing signing material (release id $id)"
  gh api "repos/$R/releases/$id" --jq '.assets[] | "\(.id) \(.name)"' | while read -r aid name; do
    curl -sSL -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/octet-stream" -o "$OUT/$name" "https://api.github.com/repos/$R/releases/assets/$aid"
  done
  for f in ota-public.b64 ota-private.b64 release.keystore release.properties; do test -s "$OUT/$f" || { echo "missing $f in key store release"; exit 1; }; done
else
  echo "No signing material yet: generating a new set (first run)"
  ./gradlew -q :otakit:run --args="genkey $OUT" >/dev/null
  pw=$(openssl rand -hex 24)
  keytool -genkeypair -keystore "$OUT/release.keystore" -storetype PKCS12 -alias gamedesigner -keyalg RSA -keysize 2048 -validity 36500 \
    -dname "CN=Game Designer, O=Hot Attic Games" -storepass "$pw" -keypass "$pw" >/dev/null 2>&1
  printf 'alias=gamedesigner\npassword=%s\n' "$pw" > "$OUT/release.properties"
  gh release create gd-signing-keys --draft --title gd-signing-keys \
    --notes "PRIVATE: signing material for Game Designer builds and OTA bundles. This is a draft release, visible only to repository writers. Do not publish or delete it: deleting it changes the APK signing identity and the OTA trust key, which forces a reinstall." \
    "$OUT/ota-public.b64" "$OUT/ota-private.b64" "$OUT/release.keystore" "$OUT/release.properties" --repo "$R"
fi
chmod 600 "$OUT"/ota-private.b64 "$OUT"/release.keystore "$OUT"/release.properties
# keep secrets out of logs
echo "::add-mask::$(sed -n 's/^password=//p' "$OUT/release.properties")"
