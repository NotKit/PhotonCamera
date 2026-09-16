#!/bin/bash
# ncnn from source for the Kotlin/Native lane, CPU-only, static.
#
# Installs to kn/out/ncnn.  What matters is the pinned master commit (the
# FlowNet custom layers index a 4D ncnn::Mat that no release tag has) and
# -D__ANDROID_API__=9, which is what compiles
# Net::load_param(AAssetManager*, path) -- the overload
# ncnnMl.cpp calls.  The AAsset_* implementation is ours (stubs/asset_dir.c),
# reading a plain directory instead of an apk.
#
#   kn/host/natives/build_ncnn.sh [--clean]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KN="$(cd "$HERE/../.." && pwd)"

ref="${NCNN_REF:-6a1bf000f363714839a36793addc8c879d3d899e}"
url="${NCNN_URL:-https://github.com/Tencent/ncnn.git}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
JOBS="${PORT_JOBS:-4}"
ARCH="${PORT_ARCH:-x86_64}"

# One clone, one prefix per arch: the sources are shared, the objects are not.
case "$ARCH" in
x86_64) export PORT_CC="${PORT_CC:-clang}"; export PORT_CXX="${PORT_CXX:-clang++}"
	prefix="$KN/out/ncnn"; cross=() ;;
arm64)  export PORT_CC="${PORT_CC:-clang}"; export PORT_CXX="${PORT_CXX:-clang++}"
	export PORT_AR="${PORT_AR:-$(command -v aarch64-linux-gnu-ar || echo ar)}"
	prefix="${NCNN_DIR:-$KN/out/ncnn-arm64}"
	cross=(-DCMAKE_TOOLCHAIN_FILE="$HERE/toolchain-arm64.cmake") ;;
*) echo "build_ncnn.sh: unsupported PORT_ARCH '$ARCH'" >&2; exit 2 ;;
esac
CC="$PORT_CC"; CXX="$PORT_CXX"
src="$KN/out/ncnn/src"; build="$prefix/build"
[ "${1:-}" != "--clean" ] || rm -rf "$build" "$prefix/lib" "$prefix/include"

stamp="$src/.kn-ref"
if [ ! -f "$src/CMakeLists.txt" ] || [ "$(cat "$stamp" 2>/dev/null || true)" != "$ref" ]; then
	mkdir -p "$src"
	# $src/.git explicitly: this directory sits inside the PhotonCamera
	# checkout, so a plain fetch would ask PhotonCamera's origin for an ncnn commit.
	[ -d "$src/.git" ] || git -C "$src" init -q
	git -C "$src" remote set-url origin "$url" 2>/dev/null || git -C "$src" remote add origin "$url"
	git -C "$src" fetch --depth 1 origin "$ref"
	git -C "$src" checkout -q --force FETCH_HEAD
	echo "$ref" >"$stamp"
	rm -rf "$build"
fi

openmp=ON
echo 'int main(){return 0;}' | "$CC" -fopenmp -x c - -o /dev/null 2>/dev/null || openmp=OFF
[ "$openmp" = ON ] || echo "note: no OpenMP for $CC; ncnn single-threaded"

flags="-D__ANDROID_API__=9 -I$HERE/include -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
if [ "$ARCH" = arm64 ]; then
	# clang finds neither the target's libstdc++ headers nor its libc headers
	# on its own in a multiarch root; name both (same list as build.sh).
	gxx="/usr/aarch64-linux-gnu/include/c++/$(ls /usr/aarch64-linux-gnu/include/c++ 2>/dev/null | sort -V | tail -1)"
	flags="$flags -isystem $gxx -isystem $gxx/aarch64-linux-gnu -isystem /usr/aarch64-linux-gnu/include"
fi
cmake -S "$src" -B "$build" -G Ninja "${cross[@]}" \
	-DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" \
	-DCMAKE_C_FLAGS="$flags" -DCMAKE_CXX_FLAGS="$flags" \
	-DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
	-DNCNN_SHARED_LIB=OFF -DNCNN_OPENMP="$openmp" -DNCNN_BUILD_TOOLS=OFF \
	-DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_BUILD_TESTS=OFF \
	-DNCNN_PYTHON=OFF -DNCNN_VULKAN=OFF >/dev/null
cmake --build "$build" --parallel "$JOBS"
cmake --install "$build" >/dev/null

# Without __ANDROID_API__ the asset overloads are not compiled at all and
# ncnnMl.cpp then fails to link with no explanation.
# Captured, not piped: `nm | grep -q` dies of SIGPIPE under `set -o pipefail`.
syms=$(nm --defined-only "$prefix/lib/libncnn.a" 2>/dev/null || true)
grep -q "_ZN4ncnn3Net10load_paramEP13AAssetManagerPKc" <<<"$syms" || {
	echo "libncnn.a has no Net::load_param(AAssetManager*, const char*)" >&2; exit 1; }
echo "ncnn ($ARCH) $(sed -n 's/^#define NCNN_VERSION_STRING "\(.*\)"/\1/p' "$prefix/include/ncnn/platform.h") ready in $prefix (openmp=$openmp)"
