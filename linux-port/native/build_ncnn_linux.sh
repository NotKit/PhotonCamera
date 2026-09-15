#!/bin/bash
# Build ncnn from source for the port.
#
# app/src/main/cpp/ncnn/ carries ncnn as an Android per-ABI static library only
# (arm64-v8a with Vulkan, armeabi-v7a CPU-only), so there is nothing to link on
# a desktop. This clones Tencent/ncnn at a pinned tag and installs a prefix:
#
#   out/native/ncnn/include/ncnn/*.h
#   out/native/ncnn/lib/libncnn.a
#
# native/build_natives_linux.sh picks that up through $PORT_NCNN_DIR and builds
# the real libncnnMl.so instead of the stub.
#
# CPU only by default, which is all the app needs to start: KernelNet asks for
# Vulkan only under KN_GPU=1, and FlowNet turns it off when ncnn reports no GPU.
# --vulkan also builds the compute backend (needs glslang sources, which the
# clone brings, and the Vulkan headers).
#
# Usage: linux-port/native/build_ncnn_linux.sh [--clean] [--vulkan]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"

# A master commit, not a release tag: the FlowNet custom layers index a 4D
# ncnn::Mat (`Mat::n`, `Mat::nstep`), which no tag up to 20260526 has and which
# the vendored Android build (a master build, 1.0.20260817) does. Pinned so a
# rebuild is reproducible; NCNN_REF=master follows upstream, NCNN_REF=<tag>
# builds a release and fails on those two fields.
ref="${NCNN_REF:-6a1bf000f363714839a36793addc8c879d3d899e}"
url="${NCNN_URL:-https://github.com/Tencent/ncnn.git}"

clean=0
vulkan=0
for arg in "$@"; do
	case "$arg" in
	--clean) clean=1 ;;
	--vulkan) vulkan=1 ;;
	*) echo "usage: $0 [--clean] [--vulkan]" >&2; exit 2 ;;
	esac
done

src="$PORT_NATIVE_OUT/ncnn/src"
build="$PORT_NATIVE_OUT/ncnn/build"
prefix="$PORT_NCNN_DIR"

[ "$clean" = 0 ] || rm -rf "$build" "$prefix/lib" "$prefix/include"

# --- sources ----------------------------------------------------------------

# fetch, not clone: one shallow fetch takes a tag, a branch or a bare commit,
# which is what pinning a master commit needs. Submodules matter only for the
# Vulkan backend (glslang); a CPU build has no third-party dependency at all.
stamp="$src/.port-ref"
if [ ! -f "$src/CMakeLists.txt" ] || [ "$(cat "$stamp" 2>/dev/null || true)" != "$ref" ]; then
	echo "fetching ncnn $ref"
	mkdir -p "$src"
	# $src/.git, not `rev-parse --git-dir`: this directory is inside the
	# PhotonCamera checkout, so rev-parse answers with the app's repository and
	# the fetch below then asks PhotonCamera's origin for an ncnn commit --
	# "upload-pack: not our ref", with nothing to say ncnn was never involved.
	[ -d "$src/.git" ] || git -C "$src" init -q
	git -C "$src" remote set-url origin "$url" 2>/dev/null ||
		git -C "$src" remote add origin "$url"
	git -C "$src" fetch --depth 1 origin "$ref"
	git -C "$src" checkout -q --force FETCH_HEAD
	echo "$ref" >"$stamp"
	# The source tree changed under it, so the builddir's cached compile lines
	# and its object files describe another ncnn.
	rm -rf "$build"
fi

if [ "$vulkan" = 1 ]; then
	# glslang is a submodule; ncnn compiles its own copy unless told otherwise.
	git -C "$src" submodule update --init --depth 1 glslang
fi

# --- configure and build ----------------------------------------------------

# OpenMP is what makes ncnn multi-threaded. clang needs libomp (Debian:
# libomp-dev); without it the build is single-threaded but correct, and
# ncnnMl.cpp's own omp calls compile out behind #ifdef _OPENMP.
openmp=ON
if ! echo 'int main(){return 0;}' | "$PORT_CC" -fopenmp -x c - -o /dev/null 2>/dev/null; then
	openmp=OFF
	echo "note: $PORT_CC has no OpenMP runtime (install libomp-dev for clang);" \
		"building ncnn single-threaded"
fi

# ncnn's Android half is gated on __ANDROID_API__, and it is the half the app
# uses: `Net::load_param(AAssetManager*, path)` reads the models straight out of
# the apk. Defining it at 9 turns on exactly that (asset overloads,
# DataReaderFromAndroidAsset and NCNN_LOGE through liblog) and nothing else --
# AHardwareBuffer needs 26, and __ANDROID__ itself stays undefined, so the
# allocator and the cpu probing keep their POSIX paths. The declarations come
# from native/include/android/, the implementations from atlas's libandroid.so.0
# at ncnnMl's link (BRINGUP_NOTES.md).
# jni.h too: ncnn's mat.h includes it beside <android/bitmap.h> for the
# from_android_bitmap helpers.
android_flags="-D__ANDROID_API__=9 -I$PORT_DIR/native/include"
android_flags="$android_flags -I$PORT_TARGET_JAVA_HOME/include"
android_flags="$android_flags -I$PORT_TARGET_JAVA_HOME/include/linux"

cmake_args=(
	-G Ninja
	-DCMAKE_BUILD_TYPE=Release
	-DCMAKE_C_COMPILER="$PORT_CC"
	-DCMAKE_CXX_COMPILER="$PORT_CXX"
	-DCMAKE_C_FLAGS="$android_flags"
	-DCMAKE_CXX_FLAGS="$android_flags"
	-DCMAKE_INSTALL_PREFIX="$prefix"
	-DCMAKE_POSITION_INDEPENDENT_CODE=ON
	-DNCNN_SHARED_LIB=OFF
	-DNCNN_OPENMP="$openmp"
	-DNCNN_BUILD_TOOLS=OFF
	-DNCNN_BUILD_EXAMPLES=OFF
	-DNCNN_BUILD_BENCHMARK=OFF
	-DNCNN_BUILD_TESTS=OFF
	-DNCNN_PYTHON=OFF
	-DNCNN_VULKAN="$([ "$vulkan" = 1 ] && echo ON || echo OFF)"
)
[ "$PORT_CROSS" = 0 ] ||
	cmake_args+=(-DCMAKE_TOOLCHAIN_FILE="$PORT_DIR/native/toolchain-cross.cmake")

cmake -S "$src" -B "$build" "${cmake_args[@]}"
cmake --build "$build" --parallel "$PORT_JOBS"
cmake --install "$build"

# --- verification -----------------------------------------------------------

for f in lib/libncnn.a include/ncnn/net.h include/ncnn/platform.h; do
	[ -e "$prefix/$f" ] || { echo "ncnn install has no $f" >&2; exit 1; }
done

# The entry points ncnnMl.cpp calls. An archive that built but exports nothing
# useful (a stripped install, the wrong ABI) fails here rather than at link time.
ncnn_syms=$("$PORT_NM" --defined-only "$prefix/lib/libncnn.a" 2>/dev/null || true)
for sym in "_ZN4ncnn3Net10load_paramE" "_ZN4ncnn3Net21register_custom_layerE"; do
	grep -q "$sym" <<<"$ncnn_syms" ||
		{ echo "libncnn.a does not define $sym" >&2; exit 1; }
done

# The asset-manager overloads specifically: without __ANDROID_API__ they are not
# compiled at all, and ncnnMl.cpp then fails to link with no explanation.
grep -q "_ZN4ncnn3Net10load_paramEP13AAssetManagerPKc" <<<"$ncnn_syms" || {
	echo "libncnn.a has no Net::load_param(AAssetManager*, const char*):" \
		"the build did not see __ANDROID_API__" >&2
	exit 1
}

version=$(sed -n 's/^#define NCNN_VERSION_STRING "\(.*\)"/\1/p' "$prefix/include/ncnn/platform.h")
vulkan_built=$(grep -c '^#define NCNN_VULKAN 1' "$prefix/include/ncnn/platform.h" || true)
echo "ncnn $version ($(git -C "$src" rev-parse --short HEAD)) ready in $prefix"
echo "  vulkan=$vulkan_built openmp=$openmp"
echo "next: linux-port/native/build_natives_linux.sh   # builds the real libncnnMl.so"
