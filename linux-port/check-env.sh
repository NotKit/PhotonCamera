#!/bin/bash
# Smoke-checks the toolchain the port needs. Exits 0 only if everything is usable.
set -euo pipefail

source "$(dirname "$0")/env.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

# --- JDK 21 -----------------------------------------------------------------
[ -x "$JAVA_HOME/bin/java" ] || fail "no java at \$JAVA_HOME/bin/java ($JAVA_HOME)"
java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
case "$java_version" in
	*'version "21.'*) ok "$java_version" ;;
	*) fail "need OpenJDK 21, got: $java_version (set JAVA_21_HOME or JAVA_HOME)" ;;
esac
[ -x "$JAVA_HOME/bin/javac" ] || fail "no javac in $JAVA_HOME/bin (JRE-only install?)"
ok "javac: $("$JAVA_HOME/bin/javac" -version 2>&1)"

# libjvm is what the hotspot launcher links against.
[ -f "$JAVA_HOME/lib/server/libjvm.so" ] || fail "missing $JAVA_HOME/lib/server/libjvm.so"
ok "libjvm: $JAVA_HOME/lib/server/libjvm.so"

# --- Android SDK ------------------------------------------------------------
# AGP still compiles the app: javac against android.jar, aapt2 for the resources.
[ -d "$ANDROID_HOME/platforms" ] || fail "no Android SDK at ANDROID_HOME=$ANDROID_HOME"
compile_sdk=$(sed -n 's/^ *compileSdk *//p' "$REPO_DIR/app/build.gradle" | head -1)
[ -d "$ANDROID_HOME/platforms/android-$compile_sdk" ] ||
	fail "app/build.gradle wants compileSdk $compile_sdk, not installed in $ANDROID_HOME/platforms"
ok "android sdk: $ANDROID_HOME (platform $compile_sdk)"

# --- atlas checkout ---------------------------------------------------------
[ -f "$ATLAS_DIR/meson.build" ] || fail "ATLAS_DIR=$ATLAS_DIR is not an atlas checkout (see README.md)"
atlas_branch="$(git -C "$ATLAS_DIR" branch --show-current 2>/dev/null || true)"
[ -n "$atlas_branch" ] || fail "$ATLAS_DIR is not a git checkout"
ok "atlas: $ATLAS_DIR on $atlas_branch ($(git -C "$ATLAS_DIR" rev-parse --short HEAD))"

# The camera2 half of the framework is what this port is built on; a checkout
# without it boots the UI and reports zero cameras, which reads like an app bug.
[ -f "$ATLAS_DIR/src/api-impl/android/hardware/camera2/CameraManager.java" ] ||
	fail "$ATLAS_DIR has no android.hardware.camera2: the port needs the camera2 branch ($ATLAS_BRANCH)"
ok "atlas camera2: $(ls "$ATLAS_DIR/src/api-impl-jni/camera" | grep -c '^camera_backend_') backends"
[ -f "$ATLAS_DIR/src/main-executable-hotspot/vm_hotspot.c" ] ||
	fail "$ATLAS_DIR has no src/main-executable-hotspot: this atlas cannot boot a JVM"

# skia is shared between checkouts; building it from scratch is the one long step.
if [ -f "$ATLAS_DIR/subprojects/skia/BUILD.gn" ]; then
	ok "atlas skia: $(readlink -f "$ATLAS_DIR/subprojects/skia")"
else
	echo "note: no skia sources at $ATLAS_DIR/subprojects/skia; meson will download" \
		"the wrap (~8 GB) unless ATLAS_SKIA_DIR=$ATLAS_SKIA_DIR is symlinked there"
fi

if [ -f "$ATLAS_BUILDDIR/build.ninja" ]; then
	ok "atlas builddir: $ATLAS_BUILDDIR"
else
	echo "note: $ATLAS_BUILDDIR not configured yet (linux-port/build-atlas.sh does it)"
fi

# --- host build tools -------------------------------------------------------
# meson/ninja build atlas; gn + clang build its skia subproject.
for tool in cc c++ cmake make pkg-config unzip meson ninja gn clang clang++; do
	command -v "$tool" >/dev/null || fail "missing host tool: $tool"
done
ok "host tools: cc c++ cmake make pkg-config unzip meson ninja gn clang clang++"

# libarchive: dngCreator dlopens it for the zipped-DNG path (BRINGUP_NOTES.md).
"$PORT_PKG_CONFIG" --exists libarchive ||
	echo "note: no libarchive development files; native/ will skip libarchive-jni.so" \
		"and DngCreator's archive path will fail at runtime"

# --- workspace layout -------------------------------------------------------
for d in native launcher shim tools gradle; do
	[ -d "$PORT_DIR/$d" ] || fail "missing linux-port/$d"
done
[ -d "$PORT_OUT" ] || fail "missing $PORT_OUT"
ok "layout: native/ launcher/ shim/ tools/ gradle/ out/"

echo "environment OK"
