#!/bin/bash
# atl-touch's JNI-free camera2 backend, built as one static library for the
# Kotlin/Native cinterop in kn/cinterop/atlcamera.def.  The sources under src/
# are copies of ~/UT/atlas-camera2/src/api-impl-jni/camera (never edited there);
# missing.c stands in for the GStreamer/Camera1 backends and Skia's encoders,
# which this port does not have.
#
#   host/atlcamera/build.sh [x86_64|arm64]
#
# The backend is picked at run time like atl's: ATL_UGLY_ENABLE_CAMERA=1 plus
# ATL_CAMERA_BACKEND=replay (an .atlcam recording, desktop) or camera2ndk (the
# device's libcamera2ndk through libhybris).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ARCH="${1:-x86_64}"
OUT="$HERE/build-$ARCH"
mkdir -p "$OUT"

SRC="camera_backend.c camera2_metadata.c camera_frame.c camera_streams.c \
     camera_record.c camera_recording.c camera_replay.c preview_texture.c"
CFLAGS="-O2 -g -fPIC -std=gnu11 -Wno-unused-result -I$HERE/src -I$HERE -I$HERE/third_party/.."
CFLAGS="$CFLAGS $(pkg-config --cflags glib-2.0 libzstd)"

case "$ARCH" in
x86_64)	CC="${CC:-cc}" ;;
arm64)	CC="${CC:-aarch64-linux-gnu-gcc}"
	# only the device has libcamera2ndk to forward to
	SRC="$SRC camera_backend_camera2ndk.c"
	CFLAGS="$CFLAGS -DATLCAMERA_CAMERA2NDK -I$HERE/third_party/android-headers -I$HERE/include" ;;
*)	echo "unknown arch $ARCH" >&2; exit 1 ;;
esac

objs=()
for f in $SRC missing.c; do
	d="$HERE/src/$f"; [ -f "$d" ] || d="$HERE/$f"
	o="$OUT/$(basename "$f" .c).o"
	$CC $CFLAGS -c "$d" -o "$o"
	objs+=("$o")
done
ar rcs "$OUT/libatlcamera.a" "${objs[@]}"
echo "built $OUT/libatlcamera.a"

# The Kotlin bindings, for the host target only: typecheck.sh puts every klib
# under kn/out/klib on its library path, and a linux_arm64 one there would make
# the x86_64 type-check unresolvable.  The arm64 interop is the Gradle build's.
[ "$ARCH" = x86_64 ] || exit 0
KN="${KN:-$HOME/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin}"
KLIB="$(cd "$HERE/../.." && pwd)/out/klib"
mkdir -p "$KLIB"
case "$ARCH" in x86_64) TARGET=linux_x64 ;; arm64) TARGET=linux_arm64 ;; esac
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}" "$KN/cinterop" -target "$TARGET" \
	-def "$HERE/../../cinterop/atlcamera.def" \
	-compiler-option -I"$HERE/src" \
	$(for i in $(pkg-config --cflags-only-I glib-2.0); do echo -n " -compiler-option $i"; done) \
	-libraryPath "$OUT" -o "$KLIB/atlcamera" >/dev/null
echo "built $KLIB/atlcamera.klib"
