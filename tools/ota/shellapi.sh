#!/usr/bin/env bash
# Prints the shell API level exactly as gradle/libs.versions.toml defines it.
set -euo pipefail
cd "$(dirname "$0")/../.."
sed -n 's/^shellApi = "\(.*\)"/\1/p' gradle/libs.versions.toml | head -1
