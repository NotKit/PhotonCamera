#!/bin/bash
# cinterop over host/natives/photoncam_native.h -> out/klib/natives.klib.
#
# The def file carries no paths: they are this machine's, and cinterop takes
# them on the command line just as well.  libphotoncam_native.a and libncnn.a
# are named as static libraries so a linuxX64 link of the app pulls them in
# without any further wiring.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KN="$(cd "$HERE/../.." && pwd)"
ARCH="${PORT_ARCH:-x86_64}"
NAT="$KN/out/natives/$ARCH"
NCNN_DIR="${NCNN_DIR:-$KN/out/ncnn}"
CINTEROP="${CINTEROP:-$HOME/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin/cinterop}"

[ -f "$NAT/libphotoncam_native.a" ] || { echo "run host/natives/build.sh first" >&2; exit 1; }
mkdir -p "$KN/out/klib"

args=(-def "$KN/cinterop/natives.def" -o "$KN/out/klib/natives.klib"
      -target linux_x64 -compiler-option "-I$HERE"
      -libraryPath "$NAT" -staticLibrary libphotoncam_native.a)
[ ! -f "$NCNN_DIR/lib/libncnn.a" ] ||
	args+=(-libraryPath "$NCNN_DIR/lib" -staticLibrary libncnn.a)

# The codec's system libraries, when build.sh found them: they are per-arch
# (x86_64 links libjpeg/libpng, arm64 uses the vendored stb and links nothing),
# so they come from that build's link line rather than from the .def.
for lib in $(grep -oE '^-l[a-z0-9]+' "$NAT/link-flags.txt" 2>/dev/null); do
	case "$lib" in -ljpeg|-lpng) args+=(-linker-option "$lib") ;; esac
done

JAVA_OPTS="${JAVA_OPTS:--Xmx4g}" "$CINTEROP" "${args[@]}"
echo "ok: $KN/out/klib/natives.klib ($(du -h "$KN/out/klib/natives.klib" | cut -f1))"
