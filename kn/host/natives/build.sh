#!/bin/bash
# Build libphotoncam_native.a - the app's own native code behind the plain C ABI
# of photoncam_native.h, for Kotlin/Native to call through cinterop.
#
# The sources under app/src/main/cpp are compiled verbatim (this lane never
# edits them); everything this port needs is added from outside -
# port_compat.hpp force-included for the NDK's transitive headers, a liblog that
# writes to stderr, an AAssetManager that reads a directory, and jnilite's
# pointer-shaped JNIEnv so the Java_* bodies can be called without a VM.
#
#   kn/host/natives/build.sh [--clean]
#   PORT_ARCH=arm64 kn/host/natives/build.sh      # cross, see the bottom
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KN="$(cd "$HERE/../.." && pwd)"
ROOT="$(cd "$KN/.." && pwd)"
APP="$ROOT/app/src/main/cpp"

ARCH="${PORT_ARCH:-x86_64}"
CROSS=""
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
# One ncnn prefix per arch (host/natives/build_ncnn.sh installs them there).
case "${PORT_ARCH:-x86_64}" in
arm64) NCNN_DIR="${NCNN_DIR:-$KN/out/ncnn-arm64}" ;;
*)     NCNN_DIR="${NCNN_DIR:-$KN/out/ncnn}" ;;
esac
OUT="$KN/out/natives/$ARCH"
OBJ="$OUT/obj"

case "$ARCH" in
x86_64)
	CC="${PORT_CC:-clang}"; CXX="${PORT_CXX:-clang++}"; AR="${PORT_AR:-ar}"
	SYSLIBARCHIVE="${SYSLIBARCHIVE:-/usr/lib/x86_64-linux-gnu/libarchive.so.13}" ;;
arm64)
	# A multiarch root, so no --sysroot - the target's headers and libraries
	# sit beside the host's.  clang with a --target is the cross compiler
	# here because g++-aarch64-linux-gnu is not installed and clang needs
	# no second install;
	# PORT_CXX=aarch64-linux-gnu-g++ switches to the gcc cross where it is.
	CC="${PORT_CC:-clang}"; CXX="${PORT_CXX:-clang++}"
	# GNU ar is format-agnostic over ELF; the prefixed one is used when present.
	AR="${PORT_AR:-$(command -v aarch64-linux-gnu-ar || command -v llvm-ar || echo ar)}"
	case "$CC" in clang*)
		# clang finds neither the target's libstdc++ headers nor its libc
		# headers on its own in a multiarch root; name both.
		gxx="/usr/aarch64-linux-gnu/include/c++/$(ls /usr/aarch64-linux-gnu/include/c++ 2>/dev/null | sort -V | tail -1)"
		CROSS="--target=aarch64-linux-gnu -isystem $gxx -isystem $gxx/aarch64-linux-gnu"
		CROSS="$CROSS -isystem /usr/aarch64-linux-gnu/include" ;;
	esac
	# An arm64 ncnn (NCNN_DIR=... PORT_ARCH=arm64 build_ncnn.sh) and an arm64
	# libarchive.so.13 come from the target rootfs, not this machine.
	SYSLIBARCHIVE="${SYSLIBARCHIVE:-/usr/lib/aarch64-linux-gnu/libarchive.so.13}" ;;
*) echo "build.sh: unsupported PORT_ARCH '$ARCH'" >&2; exit 2 ;;
esac

[ "${1:-}" != "--clean" ] || rm -rf "$OUT"
mkdir -p "$OBJ"

# The three headers app/src/main/cpp/CMakeLists.txt downloads into the app tree.
# An Android build has already put them there; fetch into our own deps/ if not,
# so nothing is ever written into app/.
DEPS="$APP/deps"
if [ ! -f "$DEPS/tiny_dng_writer.h" ]; then
	DEPS="$OUT/deps"; mkdir -p "$DEPS"
	for dep in \
		"tiny_dng_writer.h|https://raw.githubusercontent.com/ParticlesDevs/tinydng/refs/heads/master/tiny_dng_writer.h" \
		"technicallyflac.h|https://raw.githubusercontent.com/ParticlesDevs/technicallyflac/main/technicallyflac.h" \
		"archive.h|https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive.h" \
		"archive_entry.h|https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive_entry.h"; do
		name="${dep%%|*}"; url="${dep#*|}"
		[ -f "$DEPS/$name" ] || curl -fsSL "$url" -o "$DEPS/$name"
	done
	# The sources include "deps/<header>" relative to themselves.
	mkdir -p "$OUT/include-deps"; ln -sfn "$DEPS" "$OUT/include-deps/deps"
	DEPS_INC="-I$DEPS -I$OUT/include-deps"
else
	DEPS_INC="-I$DEPS -I$APP"
fi

# The version stamp the Android build compiles into dngCreator.
ver="$ROOT/app/version.properties"
VERSION_BUILD=$(sed -n 's/^VERSION_BUILD=\([0-9]*\).*/\1/p' "$ver")
VERSION_NAME=$(sed -n 's/^VERSION_NAME=\([0-9.,]*\).*/\1/p' "$ver")

JNI_INC="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "no jni.h under JAVA_HOME=$JAVA_HOME" >&2; exit 1; }

COMMON="$CROSS -O2 -fPIC -fvisibility=hidden -Wall -Wno-unused-parameter -I$HERE/include $JNI_INC"
# port_compat.hpp: the NDK's transitive includes and Android's JNIEnv** flavour
# of AttachCurrentThread, neither of which these sources spell out themselves.
CXXCOMMON="$COMMON -std=c++14 -include $HERE/include/port_compat.hpp $DEPS_INC -I$APP"

objs=()
cc() { local src="$1" out="$2"; shift 2
	echo "  cc  $(basename "$src")"; "$CC" $COMMON -std=c11 "$@" -c "$src" -o "$out"; objs+=("$out"); }
cxx() { local src="$1" out="$2"; shift 2
	echo "  c++ $(basename "$src")"; "$CXX" $CXXCOMMON "$@" -c "$src" -o "$out"; objs+=("$out"); }

echo "photoncam_native ($ARCH)"

# --- the app's native code, unmodified ---------------------------------------
cxx "$APP/dngCreator.cpp" "$OBJ/dngCreator.o" \
	-DVERSION_BUILD="\"$VERSION_BUILD\"" -DVERSION_NAME="\"$VERSION_NAME\""
cxx "$APP/allocator.cpp" "$OBJ/allocator.o"
cxx "$APP/rawF16.cpp" "$OBJ/rawF16.o"
cxx "$APP/flacRecorder.cpp" "$OBJ/flacRecorder.o"

# MediaCinemaRAW, the .mcraw raw-video encoder: plain C++ with a log call.
# -O3 as in the app's CMakeLists, which keeps it optimised in debug builds too.
for src in "$APP"/mediacinemaraw/*.cpp "$APP"/mediacinemaraw/src/*.cpp; do
	cxx "$src" "$OBJ/mcraw_$(basename "${src%.cpp}").o" -std=c++17 -O3 \
		-I"$APP/mediacinemaraw/include"
done

# The Halide aligner: prebuilt arm64 kernels and runtime.  They are Android
# objects, but nothing in them is bionic, so they link against glibc (the JVM
# port does the same).  Everywhere else ESD4D falls back to the GL pyramid.
WITH_HALIDE=0
HALIDE_DIR="$APP/halide/arm64-v8a"
if [ "$ARCH" = arm64 ] && [ -f "$HALIDE_DIR/libhalide_runtime_android.a" ]; then
	WITH_HALIDE=1
	cxx "$APP/halide/align_jni.cpp" "$OBJ/align_jni.o" -std=c++17 \
		-I"$APP/halide/include" -I"$HALIDE_DIR"
else
	echo "  note: no Halide kernels for $ARCH; ESD4D will use the GL aligner"
fi

# ncnnMl: the real thing when an ncnn prefix is there, and otherwise nothing at
# all - photoncam_native.cpp then compiles its "not available" half, exactly as
# the Android CMakeLists substitutes a stub for an ABI with no prebuilt.
WITH_NCNN=0
if [ -f "$NCNN_DIR/lib/libncnn.a" ]; then
	WITH_NCNN=1
	FLOWNET="$APP/flownet"
	# The Android build's flags for this target.  __ANDROID_API__ has to match
	# the ncnn build's or net.h hides the AAssetManager overloads ncnnMl calls.
	# The Vulkan layers are left out: this ncnn is CPU-only, and
	# flownet_register.h picks the CPU creators when NCNN_VULKAN is 0.
	MLFLAGS=(-std=c++17 -fno-rtti -fno-exceptions -D__ANDROID_API__=9
		-I"$NCNN_DIR/include/ncnn" -I"$FLOWNET" -I"$FLOWNET/layer")
	cxx "$APP/ncnnMl.cpp" "$OBJ/ncnnMl.o" "${MLFLAGS[@]}"
	for l in flownetupsample flownetcorrlookup flownetcorrfused; do
		cxx "$FLOWNET/layer/$l.cpp" "$OBJ/$l.o" "${MLFLAGS[@]}"
	done
else
	echo "  note: no ncnn at $NCNN_DIR (host/natives/build_ncnn.sh); FlowNet/KernelNet report unavailable"
fi

# --- image codec -------------------------------------------------------------
# PNG and JPEG are Skia's job on Android and nothing in app/src/main/cpp does
# them, so this is the one piece of the lane with no app C++ under it.  libpng
# when its header is here (Ubuntu Touch ships it); a vendored stb otherwise, so
# a target without it still builds.
#
# THERE IS NO -ljpeg AND NO jpeglib.h PROBE.  skiko.klib carries libjpeg.a and
# Kotlin/Native links it in, Skia pulls its members, and those jpeg_* symbols
# are the ones this port gets whatever else is on the link line -- so the
# contract to compile against is that archive's, not the build machine's.
# third_party/libjpeg holds libjpeg-turbo 3.1.0's public headers and a jconfig.h
# matching it; image_codec.c includes them by path.  The old probe had no
# --sysroot either, so a cross build was reading the HOST's jpeglib.h.
CODEC_SYSTEM=0
png_h=$("$CC" $CROSS -E -include png.h -x c /dev/null -o /dev/null 2>/dev/null && echo 1 || echo 0)
if [ "$png_h" = 1 ]; then
	CODEC_SYSTEM=1
	CODEC_LINK="-lpng"
else
	echo "  note: no png.h for $ARCH; vendoring stb (JPEG quality is lower)"
	mkdir -p "$HERE/third_party"
	for dep in \
		"stb_image.h|https://raw.githubusercontent.com/nothings/stb/master/stb_image.h" \
		"stb_image_write.h|https://raw.githubusercontent.com/nothings/stb/master/stb_image_write.h" \
		"LICENSE.stb|https://raw.githubusercontent.com/nothings/stb/master/LICENSE"; do
		name="${dep%%|*}"; url="${dep#*|}"
		[ -f "$HERE/third_party/$name" ] || curl -fsSL "$url" -o "$HERE/third_party/$name"
	done
	CODEC_LINK=""
fi
cc "$HERE/image_codec.c" "$OBJ/image_codec.o" -DPHOTONCAM_CODEC_SYSTEM=$CODEC_SYSTEM

# --- ours --------------------------------------------------------------------
cxx "$HERE/jnilite.cpp" "$OBJ/jnilite.o"
cxx "$HERE/photoncam_native.cpp" "$OBJ/photoncam_native.o" -DPHOTONCAM_WITH_NCNN=$WITH_NCNN \
	-DPHOTONCAM_WITH_HALIDE=$WITH_HALIDE
cc "$HERE/stubs/android_log.c" "$OBJ/android_log.o"
cc "$HERE/stubs/android_bitmap.c" "$OBJ/android_bitmap.o"
cc "$HERE/stubs/asset_dir.c" "$OBJ/asset_dir.o"

rm -f "$OUT/libphotoncam_native.a"
"$AR" rcs "$OUT/libphotoncam_native.a" "${objs[@]}"

# --- libarchive-jni.so -------------------------------------------------------
# dngCreator.cpp dlopens me.zhanghai.android.libarchive's .so and dlsyms the
# archive_* entry points for the zipped-DNG path.  dlsym searches a handle's
# dependencies, so an empty library with libarchive as DT_NEEDED answers them
# all.  --no-as-needed: nothing here references libarchive, and the link would
# otherwise drop the DT_NEEDED that makes the whole stub work.
if [ -e "$SYSLIBARCHIVE" ]; then
	"$CC" -shared -fPIC -o "$OUT/libarchive-jni.so" "$HERE/stubs/libarchive_jni.c" \
		-Wl,--no-as-needed "$SYSLIBARCHIVE"
else
	echo "  note: no $SYSLIBARCHIVE; DngCreator's archive path will not resolve at run time"
fi

# --- what a link of this archive needs ---------------------------------------
{
	echo "-L$OUT -lphotoncam_native"
	[ "$WITH_NCNN" = 1 ] && echo "$NCNN_DIR/lib/libncnn.a"
	# The kernels first, the runtime after: each archive carries a weak copy
	# of the runtime, and the link keeps the first definition it sees.
	[ "$WITH_HALIDE" = 1 ] && echo "$HALIDE_DIR/alignburst_base_f16.a $HALIDE_DIR/alignburst_f16.a $HALIDE_DIR/libhalide_runtime_android.a"
	[ -z "$CODEC_LINK" ] || echo "$CODEC_LINK"
	echo "-lstdc++ -lm -ldl -lpthread"
} >"$OUT/link-flags.txt"

echo "ok: $OUT/libphotoncam_native.a ($(du -h "$OUT/libphotoncam_native.a" | cut -f1), ncnn=$WITH_NCNN, halide=$WITH_HALIDE, codec=$([ "$CODEC_SYSTEM" = 1 ] && echo "libpng+skia-libjpeg" || echo stb))"
echo "    link flags in $OUT/link-flags.txt"
