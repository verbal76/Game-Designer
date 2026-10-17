#!/usr/bin/env bash
# Loads (or on first use creates) the persistent signing key of the side-by-side TEST app (com.hotatticgames.gamedesigner.test) from a PRIVATE
# DRAFT release named "gd-test-signing-key". It is deliberately a different release, and a different key, from "gd-signing-keys" (production + OTA):
# the test key can never sign production and rotating it can never affect production. Draft releases are visible only to people with push access
# and do not expire, unlike an Actions cache. Material: test.keystore, test.properties (alias, password), test.sha256 (the certificate fingerprint).
# Usage: ensure-test-key.sh <outdir>   (needs GH_TOKEN with contents:write, GITHUB_REPOSITORY)
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
R="$GITHUB_REPOSITORY"
NAME="gd-test-signing-key"
# Oldest draft of that name wins, so two first-time runs racing each other still agree on one key.
oldest() { gh api "repos/$R/releases?per_page=100" --jq "[.[] | select(.draft==true and .name==\"$NAME\")] | sort_by(.id) | .[0].id // empty"; }
fingerprint() { keytool -list -v -keystore "$1" -storepass "$2" -alias gdtest 2>/dev/null | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':' | tr 'A-F' 'a-f'; }
id=$(oldest || true)
if [ -z "${id:-}" ]; then
  echo "No test signing key yet: generating one (first run)"
  pw=$(openssl rand -hex 24)
  keytool -genkeypair -keystore "$OUT/test.keystore" -storetype PKCS12 -alias gdtest -keyalg RSA -keysize 2048 -validity 36500 \
    -dname "CN=Game Designer TEST, O=Hot Attic Games" -storepass "$pw" -keypass "$pw" >/dev/null 2>&1
  printf 'alias=gdtest\npassword=%s\n' "$pw" > "$OUT/test.properties"
  fingerprint "$OUT/test.keystore" "$pw" > "$OUT/test.sha256"
  gh release create "$NAME" --draft --title "$NAME" \
    --notes "PRIVATE: signing key of the side-by-side TEST app only (never production). Draft release, visible only to repository writers. Do not publish or delete it: deleting it changes the TEST app's signing identity, and the installed TEST app can then no longer be updated in place." \
    "$OUT/test.keystore" "$OUT/test.properties" "$OUT/test.sha256" --repo "$R"
  # A just-created draft can take a moment to appear in the list.
  for _ in 1 2 3 4 5 6 7 8 9 10; do id=$(oldest || true); [ -n "${id:-}" ] && break; sleep 3; done
  [ -n "${id:-}" ] || { echo "created $NAME but cannot see it yet; run again (the key is stored)"; exit 1; }
  rm -f "$OUT"/test.*
fi
echo "Loading test signing key (release id $id)"
gh api "repos/$R/releases/$id" --jq '.assets[] | "\(.id) \(.name)"' | while read -r aid name; do
  curl -fsSL -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/octet-stream" -o "$OUT/$name" "https://api.github.com/repos/$R/releases/assets/$aid"
done
for f in test.keystore test.properties test.sha256; do test -s "$OUT/$f" || { echo "missing $f in $NAME"; exit 1; }; done
pw=$(sed -n 's/^password=//p' "$OUT/test.properties")
echo "::add-mask::$pw"
actual=$(fingerprint "$OUT/test.keystore" "$pw"); expected=$(tr -d '[:space:]' < "$OUT/test.sha256")
[ -n "$actual" ] && [ "$actual" = "$expected" ] || { echo "test keystore fingerprint ($actual) does not match its recorded fingerprint ($expected)"; exit 1; }
chmod 600 "$OUT"/test.keystore "$OUT"/test.properties
echo "TEST_CERT_SHA256=$expected"
