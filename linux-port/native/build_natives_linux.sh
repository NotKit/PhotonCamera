#!/bin/bash
# Build the app's JNI libraries for glibc from linux-port/native/CMakeLists.txt,
# an overlay over app/src/main/cpp (which is never edited or built by the NDK
# here). Products land in $PORT_LIB_OUT, the launcher's java.library.path:
#
#   libdngCreator.so    DNG writer (tinydng) + the libarchive zip path
#   liballocator.so     the pipeline's off-heap buffers
#   libflacRecorder.so  audio note recording
#   libcamera2native.so native-engine.cpp
#   libarchive-jni.so   stub pulling in the system libarchive (see the overlay)
#
# Usage: linux-port/native/build_natives_linux.sh [--clean]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"

build_dir="$PORT_NATIVE_OUT/app-jni/build"
[ "${1:-}" != "--clean" ] || rm -rf "$build_dir"
mkdir -p "$build_dir" "$PORT_LIB_OUT"

cmake_args=(
	-G Ninja
	-DCMAKE_BUILD_TYPE=Release
	-DCMAKE_C_COMPILER="$PORT_CC"
	-DCMAKE_CXX_COMPILER="$PORT_CXX"
	-DAPP_CPP_DIR="$REPO_DIR/app/src/main/cpp"
	-DPORT_DEPS_DIR="$PORT_NATIVE_OUT/app-jni/deps"
	-DPORT_NCNN_DIR="$PORT_NCNN_DIR"
	-DPORT_ANDROID_LIB="$ATLAS_OUT/libandroid.so.0"
	-DJAVA_HOME="$PORT_TARGET_JAVA_HOME"
	# The libarchive probe has to answer for the target. CMake would otherwise
	# find the host's pkg-config, and a cross build would link an x86_64
	# libarchive into libarchive-jni.so.
	-DPKG_CONFIG_EXECUTABLE="$(command -v "$PORT_PKG_CONFIG" || echo "$PORT_PKG_CONFIG")"
)
# find_package(JNI) reads JAVA_HOME from the environment, and on a cross build
# that has to be the target JDK, not the one running the build.
export JAVA_HOME="$PORT_TARGET_JAVA_HOME"
[ "$PORT_CROSS" = 0 ] ||
	cmake_args+=(-DCMAKE_TOOLCHAIN_FILE="$PORT_DIR/native/toolchain-cross.cmake")

cmake -S "$PORT_DIR/native" -B "$build_dir" "${cmake_args[@]}"
cmake --build "$build_dir" --parallel "$PORT_JOBS"

for so in "$build_dir"/*.so; do
	cp "$so" "$PORT_LIB_OUT/"
done

# --- verification -----------------------------------------------------------

# Every library System.loadLibrary asks for, under the name it asks for.
for name in dngCreator allocator flacRecorder camera2native; do
	[ -f "$PORT_LIB_OUT/lib$name.so" ] || { echo "missing $PORT_LIB_OUT/lib$name.so" >&2; exit 1; }
done

# ncnnMl is the fifth: the real library once native/build_ncnn_linux.sh has run,
# the stub before that (it loads, and FlowNet and KernelNet answer "not
# available"). Say which one this build produced.
[ -f "$PORT_LIB_OUT/libncnnMl.so" ] || { echo "missing $PORT_LIB_OUT/libncnnMl.so" >&2; exit 1; }
if [ -f "$PORT_NCNN_DIR/lib/libncnn.a" ]; then
	ncnn_syms=$("$PORT_NM" -D --defined-only "$PORT_LIB_OUT/libncnnMl.so" |
		grep -c "Java_com_particlesdevs_photoncamera_processing_ml" || true)
	[ "$ncnn_syms" -ge 6 ] ||
		{ echo "libncnnMl.so exports only $ncnn_syms ML entry points" >&2; exit 1; }
	echo "ok: libncnnMl.so is the real ncnn build ($ncnn_syms ML entry points)"
else
	echo "note: libncnnMl.so is the stub; run native/build_ncnn_linux.sh for the real one"
fi

if [ "$PORT_CROSS" = 0 ]; then
	for so in "$PORT_LIB_OUT"/*.so; do
		if ldd "$so" | grep -q "not found"; then
			echo "unresolved dependencies of $so:" >&2
			ldd "$so" | grep "not found" >&2
			exit 1
		fi
	done
	# The dlopen dngCreator does at runtime, done here: a stub that lost its
	# DT_NEEDED links fine and then resolves no symbol at all.
	if [ -f "$PORT_LIB_OUT/libarchive-jni.so" ]; then
		"$JAVA_HOME/bin/java" --version >/dev/null # keep the JDK check honest
		cat >"$build_dir/dlopen_check.c" <<'CHECK'
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char **argv) {
	void *h = dlopen(argv[1], RTLD_NOW);
	if (!h) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
	if (!dlsym(h, "archive_write_new")) {
		fprintf(stderr, "no archive_write_new in %s\n", argv[1]);
		return 1;
	}
	printf("ok: %s resolves archive_write_new\n", argv[1]);
	return 0;
}
CHECK
		"$PORT_CC" -o "$build_dir/dlopen_check" "$build_dir/dlopen_check.c" -ldl
		"$build_dir/dlopen_check" "$PORT_LIB_OUT/libarchive-jni.so"
	fi
fi

# JNI entry points: a library that links but registers nothing looks fine until
# the first call from Java.
for pair in "dngCreator:Java_com_particlesdevs_photoncamera_processing_DngCreator" \
            "camera2native:Java_com_particlesdevs_photoncamera_api_NativeEngine"; do
	name="${pair%%:*}"; prefix="${pair#*:}"
	count=$("$PORT_NM" -D --defined-only "$PORT_LIB_OUT/lib$name.so" | grep -c " T $prefix" || true)
	[ "$count" -gt 0 ] || { echo "lib$name.so exports no $prefix* entry points" >&2; exit 1; }
	echo "ok: lib$name.so exports $count $prefix* entry points"
done

echo "app JNI libraries in $PORT_LIB_OUT"
