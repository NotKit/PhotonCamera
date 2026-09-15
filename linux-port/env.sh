#!/bin/bash
# Shared environment for the desktop OpenJDK port. Source it, don't run it:
#   source linux-port/env.sh
# Every variable can be overridden from the caller's environment.

# Absolute path of linux-port/, valid whether sourced from bash or a script.
PORT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PORT_DIR
export REPO_DIR="$(cd "$PORT_DIR/.." && pwd)"

# atlas (atl-touch) sources the port builds itself: a checkout of the branch that
# carries the camera2 bring-up, its own git repo, gitignored here. See README.md.
# On this machine that branch is already checked out as a worktree next door, so
# the default follows it when linux-port/atlas does not exist.
if [ -z "${ATLAS_DIR:-}" ]; then
	if [ -f "$PORT_DIR/atlas/meson.build" ]; then
		ATLAS_DIR="$PORT_DIR/atlas"
	elif [ -f "$REPO_DIR/../atlas-camera2/meson.build" ]; then
		ATLAS_DIR="$(cd "$REPO_DIR/../atlas-camera2" && pwd)"
	else
		ATLAS_DIR="$PORT_DIR/atlas"
	fi
fi
export ATLAS_DIR
export ATLAS_BRANCH="${ATLAS_BRANCH:-camera2-gcam}"
export ATLAS_URL="${ATLAS_URL:-git@github.com:NotKit/atl-touch.git}"

# The ~8 GB skia checkout is shared between atlas checkouts through a symlink at
# $ATLAS_DIR/subprojects/skia; build-atlas.sh creates it from here if missing.
export ATLAS_SKIA_DIR="${ATLAS_SKIA_DIR:-/home/nekit/UT/atlas/subprojects/skia}"

# OpenJDK 21. Keep an already-correct JAVA_HOME, otherwise pick a distro path —
# the Debian one the click container has, then the Arch one this host uses.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
	export JAVA_HOME="${JAVA_21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
	if [ ! -x "$JAVA_HOME/bin/java" ] && [ -z "${JAVA_21_HOME:-}" ]; then
		for _jdk in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21; do
			[ -x "$_jdk/bin/java" ] && { export JAVA_HOME="$_jdk"; break; }
		done
	fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

# The Android SDK AGP compiles the app against. Only javac and aapt2 run here:
# the port never invokes the NDK (-PlinuxPort turns externalNativeBuild off).
export ANDROID_HOME="${ANDROID_HOME:-/home/nekit/android-sdk}"
# Fall back to Android Studio's default location when that path does not exist.
[ -d "$ANDROID_HOME/platforms" ] || [ ! -d "$HOME/Android/Sdk/platforms" ] ||
	export ANDROID_HOME="$HOME/Android/Sdk"

# All build products land here; never committed.
export PORT_OUT="${PORT_OUT:-$PORT_DIR/out}"
export PORT_NATIVE_OUT="$PORT_OUT/native"
# java.library.path for the launcher: the app's five JNI libraries, built from
# app/src/main/cpp against glibc, under the names System.loadLibrary asks for.
export PORT_LIB_OUT="${PORT_LIB_OUT:-$PORT_OUT/lib}"

# ncnn built from source (native/build_ncnn_linux.sh): a prefix with lib/ and
# include/ncnn. The overlay builds the real libncnnMl.so when it is there and a
# stub when it is not, mirroring app/src/main/cpp/CMakeLists.txt.
export PORT_NCNN_DIR="${PORT_NCNN_DIR:-$PORT_NATIVE_OUT/ncnn}"

# atlas builds into out/, so `--clean` never touches the sources.
export ATLAS_BUILDDIR="${ATLAS_BUILDDIR:-$PORT_OUT/atlas-build}"
export ATLAS_OUT="${ATLAS_OUT:-$PORT_OUT/atlas}"
export PORT_LAUNCHER_BIN="$ATLAS_OUT/android-translation-layer-hotspot"

# The activity run.sh starts. SplashActivity is the launcher activity in
# AndroidManifest.xml; CameraActivity is the one worth starting directly once
# the splash's permission flow is understood.
export PORT_ACTIVITY="${PORT_ACTIVITY:-com.particlesdevs.photoncamera.ui.SplashActivity}"
export PORT_APP_ID="${PORT_APP_ID:-com.particlesdevs.photoncamera}"

# The Gradle variant the port builds. PhotonCamera has no product flavors, and
# `debug` is the only build type that does not run R8 — release renames the
# classes the launcher and the checks name.
export PORT_VARIANT="${PORT_VARIANT:-debug}"

# Camera backend for a run. On the desktop only `gst` exists (a synthetic
# camera2 device over a GStreamer source); `camera2ndk` is the device's own
# camera2 stack through libhybris and the only one that gives real sensor data.
export ATL_CAMERA_BACKEND="${ATL_CAMERA_BACKEND:-gst}"
export ATL_CAMERA_GST_SRC="${ATL_CAMERA_GST_SRC:-videotestsrc is-live=true}"

# Target architecture of every native product: x86_64 is the desktop port, arm64
# the Ubuntu Touch click (linux-port/click/), which cross-compiles.
export PORT_ARCH="${PORT_ARCH:-x86_64}"
case "$PORT_ARCH" in
x86_64) export PORT_TRIPLE="${PORT_TRIPLE:-x86_64-linux-gnu}" ;;
arm64)  export PORT_TRIPLE="${PORT_TRIPLE:-aarch64-linux-gnu}" ;;
*) echo "env.sh: unsupported PORT_ARCH '$PORT_ARCH' (x86_64 or arm64)" >&2 ;;
esac

# Cross builds prefix their tools and cannot run what they produce, so every
# script that verifies a product has to skip (or defer) that check.
if [ "$PORT_TRIPLE" = "$(uname -m)-linux-gnu" ]; then
	export PORT_CROSS=0
	export PORT_TOOL_PREFIX=""
	# clang is what the Android build uses for app/src/main/cpp.
	export PORT_CC="${PORT_CC:-clang}"
	export PORT_CXX="${PORT_CXX:-clang++}"
else
	export PORT_CROSS=1
	export PORT_TOOL_PREFIX="${PORT_TRIPLE}-"
	export PORT_CC="${PORT_CC:-${PORT_TOOL_PREFIX}gcc}"
	export PORT_CXX="${PORT_CXX:-${PORT_TOOL_PREFIX}g++}"
fi
# How many compile jobs every step may run. Default is one per core; lower it on
# a machine short of memory, where the atlas build is the one that hurts — each
# api-impl target is a javac of its own, and a JVM is half a gigabyte.
# clickable passes its own count in as $NUM_PROCS.
export PORT_JOBS="${PORT_JOBS:-${NUM_PROCS:-$(nproc)}}"

export PORT_AR="${PORT_AR:-${PORT_TOOL_PREFIX}ar}"
export PORT_RANLIB="${PORT_RANLIB:-${PORT_TOOL_PREFIX}ranlib}"
export PORT_STRIP="${PORT_STRIP:-${PORT_TOOL_PREFIX}strip}"
export PORT_NM="${PORT_NM:-${PORT_TOOL_PREFIX}nm}"
export PORT_PKG_CONFIG="${PORT_PKG_CONFIG:-${PORT_TOOL_PREFIX}pkg-config}"

# The JDK the target runs on. Same version as JAVA_HOME, other architecture:
# jni.h for the JNI libraries, lib/server/libjvm.so for the launcher. Equal to
# JAVA_HOME on a native build.
export PORT_TARGET_JAVA_HOME="${PORT_TARGET_JAVA_HOME:-$JAVA_HOME}"

mkdir -p "$PORT_OUT" "$PORT_NATIVE_OUT" "$PORT_LIB_OUT"
