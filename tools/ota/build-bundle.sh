#!/usr/bin/env bash
# Builds the OTA application-layer bundle and its signed manifest. Usage:
#   build-bundle.sh <version> <label> <channel> <outdir> <private-key-file> [source-sha]
set -euo pipefail
cd "$(dirname "$0")/../.."
VER="$1"; LABEL="$2"; CHANNEL="$3"; OUT="$4"; KEY="$5"; SHA="${6:-$(git rev-parse HEAD)}"
FP="$(tools/ota/fingerprint.sh)"
API="$(sed -n 's/^shellApi = "\(.*\)"/\1/p' gradle/libs.versions.toml | head -1)"
MINSHELL="${OTA_MIN_SHELL:-1}"
./gradlew -q :otabundle:assembleDebug -PlayerVersion="$VER" -PlayerLabel="$LABEL" -PsourceSha="$SHA"
APK=otabundle/build/outputs/apk/debug/otabundle-debug.apk
test -f "$APK"
W="$(mktemp -d)"
unzip -q -o "$APK" 'classes*.dex' -d "$W"
( cd "$W" && zip -X -q bundle.zip classes*.dex )
# Package sanity: the entry class must be present in the bundle.
grep -a -q 'Lcom/hotattic/gamedesigner/applayer/AppLayerEntry;' "$W"/classes*.dex || { echo "entry class missing from bundle"; exit 1; }
SIZE=$(stat -c %s "$W/bundle.zip"); echo "bundle size: $SIZE bytes"
test "$SIZE" -lt $((40*1024*1024))
mkdir -p "$OUT"
./gradlew -q :otakit:run --args="make-manifest --bundle $W/bundle.zip --out $OUT --channel $CHANNEL --version $VER --name $LABEL --source-sha $SHA --api $API --fingerprint $FP --min-shell $MINSHELL --entry com.hotattic.gamedesigner.applayer.AppLayerEntry --private-key $KEY"
