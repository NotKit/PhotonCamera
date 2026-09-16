#!/bin/bash
# Builds cinterop/gles.def into out/klib/gles.klib, which typecheck.sh (and the
# Gradle build) put on the library path.  out/ is not in git, so run this once
# after a fresh clone.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KN="${KN_HOME:-$HOME/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10}"
TARGET="${1:-linux_x64}"
mkdir -p "$HERE/../out/klib"
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}" "$KN/bin/cinterop" \
    -def "$HERE/gles.def" -target "$TARGET" -o "$HERE/../out/klib/gles.klib"
