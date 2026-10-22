#!/usr/bin/env bash
# Prints the runtime fingerprint exactly as app/build.gradle.kts computes it.
set -euo pipefail
cd "$(dirname "$0")/../.."
v() { sed -n "s/^$1 = \"\(.*\)\"/\1/p" gradle/libs.versions.toml | head -1; }
echo "k$(v kotlin)-c$(v composeBom)-a$(v agp)-s$(v shellApi)"
