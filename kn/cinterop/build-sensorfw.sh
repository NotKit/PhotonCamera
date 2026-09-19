#!/bin/bash
# Builds cinterop/sensorfw.def into out/klib/sensorfw.klib, which typecheck.sh
# puts on the library path.  Same shape as build-gles.sh, and for the same
# reason: out/ is not in git, so run it once after a fresh clone.  The Gradle
# build makes its own copy and does not read this one.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KN="${KN_HOME:-$HOME/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10}"
TARGET="${1:-linux_x64}"
mkdir -p "$HERE/../out/klib"
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}" "$KN/bin/cinterop" \
    -def "$HERE/sensorfw.def" -target "$TARGET" \
    -compiler-option "-I$HERE/../host" \
    -o "$HERE/../out/klib/sensorfw.klib"
