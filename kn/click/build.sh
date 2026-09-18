#!/bin/bash
# Custom clickable builder for the Ubuntu Touch click of the Kotlin/Native port.
#
# THERE ARE TWO WAYS TO GET THE BINARY, and the log says which one happened:
#
#   source   the default from a fresh clone.  scripts/fetch-deps.sh brings in
#            aurora-maven and the arm64 sysroot, then mgwl, the extra arm64
#            libraries, the app's C (under -PwithApp) and the Gradle link all
#            run HERE, in the amd64 cross container.
#   staged   kn/out/click-prebuilt, written by the host's stage-prebuilt.sh.
#            The fast local loop -- a 40-minute link is not something to pay for
#            a packaging change -- and the escape hatch when the source build
#            breaks.  Used automatically when it is there and newer than the
#            sources.
#
# PC_CLICK_BUILD=source forces a rebuild, =staged refuses to do one.
#
# WHY A CROSS CONTAINER CAN COMPILE THIS AT ALL, since the old comment here said
# it could not: Kotlin/Native ships no linux-aarch64 HOST compiler (the konan
# bundle is kotlin-native-prebuilt-linux-x86_64-*, and the name is the host, not
# the target), so an arm64 container would have no konanc -- permanently.  But
# this is the amd64 cross container, where konan runs and cross-compiles by
# design, exactly as the JVM port compiles its whole world in the same place.
# What really stood in the way was that aurora-maven and the sysroot were
# hardcoded to paths outside the project root, and clickable mounts the project
# root and nothing else; kn/settings.gradle.kts and scripts/fetch-deps.sh are
# where that was fixed.
#
# The payload is one executable, its two resource trees and exactly one library.
# Step 4 is what makes that claim checkable rather than hopeful.
set -euo pipefail

ROOT="${ROOT:?not run through clickable}"
BUILD_DIR="${BUILD_DIR:?}"
INSTALL_DIR="${INSTALL_DIR:?}"
ARCH="${ARCH:-arm64}"

PKG="photoncamera.thekit"
HOOK="photoncamera"
TRIPLE="aarch64-linux-gnu"
# The packaging revision, bumped when the click changes but the app does not:
# OpenStore refuses an upload whose version it has already seen.
CLICK_REV=1

[ "$ARCH" = "arm64" ] ||
	{ echo "this package is arm64 only (ARCH=$ARCH): the payload is an arm64 ELF" >&2; exit 1; }

CLICK_DIR="$ROOT/kn/click"
KN="$ROOT/kn"
PREBUILT="$KN/out/click-prebuilt"
LINKED="$KN/build/bin/linuxArm64/releaseExecutable"
ASSETS="$ROOT/app/src/main/assets"

log() { echo -e "\033[1;34m[click]\033[0m $*"; }
die() { echo "build.sh: $*" >&2; exit 1; }

# --- 1. the binary: staged, or built here ------------------------------------

# "Current" is the only question worth asking of a staged tree: it was pinned by
# sha when it was staged, so what can go stale is the SOURCE under it.  One file
# newer than the binary is enough -- packaging yesterday's link is the failure
# this catches, and it looks like a fixed bug coming back.
staged_is_current() {
	[ -f "$PREBUILT/PROVENANCE.md" ] && [ -x "$PREBUILT/photoncam-kn" ] || return 1
	local newer
	newer=$(find "$KN/src" "$KN/host" "$KN/cinterop" "$KN/gen" "$KN/gen-atlas" \
		"$KN/build.gradle.kts" "$ROOT/ui-compose/src" \
		-newer "$PREBUILT/photoncam-kn" -type f -print -quit 2>/dev/null || true)
	[ -z "$newer" ]
}

# j2k needs four packages that no apt package provides, so it gets a venv of its
# own under kn/out.  Pinned to what the host's toolchain venv carries: tree-sitter
# and tree-sitter-java move their API together and a mismatched pair parses every
# file into an error node, which reads as a corpus problem rather than a version one.
j2k_python() {
	local venv="$KN/out/j2k-venv"
	if [ ! -x "$venv/bin/python" ]; then
		python3 -m venv "$venv" >&2
		"$venv/bin/pip" -q install \
			javalang==0.13.0 tree-sitter==0.26.0 tree-sitter-java==0.23.5 >&2
	fi
	echo "$venv/bin/python"
}

build_from_source() {
	log "building from source in this container"

	# Gradle and konan want a JDK, and so does the natives lane (jni.h).
	# dependencies_host has openjdk-17-jdk-headless; this finds wherever apt put it.
	if [ -z "${JAVA_HOME:-}" ]; then
		local javac; javac="$(command -v javac || true)"
		[ -n "$javac" ] || die "no javac in this container -- dependencies_host needs a JDK"
		JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$javac")")")"
		export JAVA_HOME
	fi
	log "  JAVA_HOME=$JAVA_HOME"

	# pkg-config is asked for glib's and zstd's include paths by
	# host/atlcamera/build.sh and by build.gradle.kts, and both of them compile
	# for arm64: without this it answers with the amd64 glibconfig.h, whose
	# GLIB_SIZEOF_* are simply wrong for the target.
	if [ -d "/usr/lib/$TRIPLE/pkgconfig" ]; then
		export PKG_CONFIG_LIBDIR="/usr/lib/$TRIPLE/pkgconfig:/usr/share/pkgconfig"
	fi

	# aurora-maven (922 MB, git-lfs) and the arm64 sysroot.  A no-op on a machine
	# that already has them -- see scripts/fetch-deps.sh.
	"$KN/scripts/fetch-deps.sh"
	# CMP cannot read an Android <vector>; this is composeResources/*.svg.
	"$KN/scripts/convert-drawables.sh"
	# libmgwl-arm64.a (our Wayland/EGL window) and the two link-line stubs.
	"$KN/scripts/build-mgwl.sh" arm64
	# libxkbcommon, which the sysroot does not carry.
	"$KN/scripts/fetch-arm64-extra.sh"

	local gradle_args=(linkReleaseExecutableLinuxArm64)
	# -PwithApp needs kn/gen and kn/gen-atlas: the CONVERTED app, which the j2k
	# pipeline generates and .gitignore keeps out of the repository.  A fresh
	# clone has neither, so generate them rather than quietly shipping a UI-only
	# click -- both converters read tracked sources plus atl-touch, which
	# fetch-deps.sh has already cloned by this point.
	if [ ! -d "$KN/gen" ] || [ ! -d "$KN/gen-atlas" ]; then
		log "  no kn/gen: running the j2k conversion"
		PYTHON="$(j2k_python)" "$KN/convert.sh"
		PYTHON="$(j2k_python)" "$KN/convert-atlas.sh"
	fi
	if [ -d "$KN/gen" ] && [ -d "$KN/gen-atlas" ]; then
		log "  kn/gen is here: building the app (-PwithApp)"
		# The app's own C++ behind a plain C ABI, cross-compiled.  The compiler
		# is left at that script's default, clang with --target: it is what the
		# host build used, and swapping in the cross gcc changes the answer to
		# its own jpeglib.h/png.h probe -- which would put -ljpeg -lpng on the
		# link line for a sysroot that has neither.
		PORT_ARCH=arm64 "$KN/host/natives/build.sh"
		[ -f "$KN/out/ncnn-arm64/lib/libncnn.a" ] ||
			log "  NOTE: no arm64 ncnn under kn/out -- FlowNet and KernelNet will report unavailable
        (host/natives/build_ncnn.sh builds one; it is not part of this build)"
		# atlas's camera2 backend.
		CC="$TRIPLE-gcc" "$KN/host/atlcamera/build.sh" arm64
		gradle_args+=(-PwithApp)
	else
		log "  the conversion produced no kn/gen: building the UI-only binary.
        This click will show the UI and take no pictures."
	fi

	# No wrapper lives in kn/, so: an explicit gradle, then one on PATH, then the
	# repository's own wrapper (which is the Android build's, and a version
	# behind the 8.13 this lane uses on the host).
	local gradle="${PC_GRADLE:-}"
	if [ -z "$gradle" ]; then
		if command -v gradle >/dev/null 2>&1; then gradle="$(command -v gradle)"
		elif [ -x "$ROOT/gradlew" ]; then gradle="$ROOT/gradlew"
		else die "no gradle and no $ROOT/gradlew -- set PC_GRADLE"; fi
	fi
	log "  gradle: $gradle"
	"$gradle" -p "$KN" --no-daemon "${gradle_args[@]}"

	[ -x "$LINKED/photoncam-kn.kexe" ] || die "the link produced no $LINKED/photoncam-kn.kexe"
}

MODE="${PC_CLICK_BUILD:-auto}"
case "$MODE" in
staged)
	# The escape hatch says "package what is there", so a stale tree is a warning
	# and not a refusal -- it is used precisely when the source build is broken.
	[ -x "$PREBUILT/photoncam-kn" ] ||
		die "PC_CLICK_BUILD=staged, but there is no binary in $PREBUILT
  run kn/click/stage-prebuilt.sh on the host (kn/click/make-click.sh does)"
	staged_is_current || log "WARNING: $PREBUILT is older than a source file"
	;;
source) build_from_source ;;
auto)
	if staged_is_current; then
		log "using the staged binary in $PREBUILT (newer than every source)"
		MODE=staged
	else
		[ -f "$PREBUILT/PROVENANCE.md" ] &&
			log "the staged binary in $PREBUILT is older than a source file -- rebuilding"
		build_from_source
		MODE=source
	fi
	;;
*) die "PC_CLICK_BUILD must be auto, source or staged (got '$MODE')" ;;
esac

# Where the two paths converge.  lib/ is the staged fallback copy of
# libmaliit-glib and exists only on that path; step 3 has the other sources.
if [ "$MODE" = staged ]; then
	BIN="$PREBUILT/photoncam-kn"; RES="$PREBUILT/resources"; EXTRA_LIBS="$PREBUILT/lib"
else
	BIN="$LINKED/photoncam-kn.kexe"; RES="$LINKED/resources"; EXTRA_LIBS=""
fi

case "$(file -b "$BIN")" in
	*"ARM aarch64"*) ;;
	*) die "$BIN is not aarch64: $(file -b "$BIN" | cut -c1-60)" ;;
esac
# A missing resource is a MissingResourceError on the first frame that draws an
# icon, which reads like a Compose bug rather than a packaging one.
NRES=$(ls "$RES" 2>/dev/null | wc -l)
[ "$NRES" -ge 70 ] || die "only $NRES resources in $RES
  the link stages them (build.gradle.kts, stageResourcesArm64)"

for d in shaders models; do
	[ -d "$ASSETS/$d" ] || die "no $ASSETS/$d -- the GL pipeline and the ML nodes read it at runtime"
done

# --- 2. stage ----------------------------------------------------------------

mkdir -p "$INSTALL_DIR/lib"
install -m 0755 "$BIN" "$INSTALL_DIR/photoncam-kn"
# resources/ has to sit at $ATL_APPDIR/resources: host/appdir_stub.c answers
# Aurora's appdir_get_path(PACKAGE_FILES) with the package root and CMP's
# LinuxResourceReader appends the name.
rm -rf "$INSTALL_DIR/resources"; cp -a "$RES" "$INSTALL_DIR/resources"
# The pipeline's shaders, LUTs and ncnn models. Without them the first GL program
# compile dies loading "shaders/merge/merge00.glsl".
rm -rf "$INSTALL_DIR/assets"; cp -a "$ASSETS" "$INSTALL_DIR/assets"

install -m 0755 "$CLICK_DIR/run.sh" "$INSTALL_DIR/run.sh"
install -m 0644 "$CLICK_DIR/$HOOK.desktop" "$CLICK_DIR/$HOOK.apparmor" "$INSTALL_DIR/"
# PhotonCamera's launcher icon is an adaptive one -- three Android vector
# drawables no click tool can read -- so this is the same artwork already
# rasterised in the repository.
install -m 0644 "$ROOT/fastlane/metadata/android/en-US/images/icon.png" "$INSTALL_DIR/$HOOK.png"

# clickable fills in @CLICK_ARCH@ and @CLICK_FRAMEWORK@; the version is the app's
# own, so a click is identifiable as that build.
app_version=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\([^']*\)'.*/\1/p" \
	"$ROOT/app/build.gradle" | head -1)
[ -n "$app_version" ] || die "no versionName in $ROOT/app/build.gradle"
sed "s|@CLICK_VERSION@|$app_version.$CLICK_REV|" "$CLICK_DIR/manifest.json" \
	>"$INSTALL_DIR/manifest.json"
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$INSTALL_DIR/manifest.json" ||
	die "the substituted manifest is not valid JSON"

# --- 3. bundle what the device does not have ---------------------------------
#
# device-libs.txt is the device's own `ldconfig -p`. Anything the click needs
# that is not in it has to travel with it, transitively -- a bundled library
# brings its own DT_NEEDED. This is the check that turns a load failure on the
# phone, which says only "error while loading shared libraries", into a build
# failure here that names the library.
#
# In practice there is exactly one: libmaliit-glib. compose-ui's klib manifest
# names ak-keyboard-maliit, so it is a hard DT_NEEDED whether or not a keyboard
# is ever shown, and Ubuntu Touch has libmaliit-plugins but no glib bindings --
# only a phone that has had a Qt keyboard app installed happens to carry one.
#
# WHERE IT COMES FROM, and the container is not the answer: noble's
# libmaliit-glib-dev:arm64 is maliit 2.3.0, whose soname is libmaliit-glib.so.2,
# and the binary needs .so.0 -- Aurora builds 0.99.1 and links against that.  So
# the container's own package is searched first out of principle and never
# matches; what actually supplies it is aurora-maven's aarch64 build, or the
# staged copy of the same file.
device_libs="$CLICK_DIR/device-libs.txt"
[ -f "$device_libs" ] || die "missing $device_libs"
# The aurora-maven candidates are settings.gradle.kts's order, once more: the
# last one matters for --container-mode, which runs this on the host, where the
# checkout is aurora-probe's and there is no kn/deps at all.
search_dirs=("/usr/lib/$TRIPLE" "/lib/$TRIPLE")
for d in "${PC_AURORA_MAVEN:-}" "$KN/deps/aurora-maven" \
	"$HOME/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven"; do
	[ -n "$d" ] && [ -d "$d/3rd_party/maliit-glib/aarch64" ] &&
		search_dirs+=("$d/3rd_party/maliit-glib/aarch64")
done
[ -n "$EXTRA_LIBS" ] && [ -d "$EXTRA_LIBS" ] && search_dirs+=("$EXTRA_LIBS")

unavailable=""
while :; do
	needed=$(for f in "$INSTALL_DIR/photoncam-kn" "$INSTALL_DIR"/lib/*; do
			[ -f "$f" ] || continue
			readelf -d "$f" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'
		done | sort -u)
	have=$( { ls "$INSTALL_DIR/lib"; echo "$unavailable"; grep -v '^#' "$device_libs"; } | sort -u)
	missing=$(comm -23 <(echo "$needed") <(echo "$have"))
	[ -n "$missing" ] || break
	for lib in $missing; do
		found=""
		for d in "${search_dirs[@]}"; do
			# -a, not -L: the loader follows libFOO.so.N to libFOO.so.N.M, and
			# flattening the chain would pack the same object several times.
			[ -e "$d/$lib" ] && { cp -a "$d/$lib"* "$INSTALL_DIR/lib/" 2>/dev/null ||
				cp -L "$d/$lib" "$INSTALL_DIR/lib/$lib"; found="$d"; break; }
		done
		if [ -n "$found" ]; then
			log "  bundling $lib from $found (not on the device)"
		else
			echo "  WARNING: $lib is neither on the device nor in this container" >&2
			unavailable="$unavailable$lib"$'\n'
		fi
	done
done
[ -z "$unavailable" ] || die "unresolvable libraries -- the app would not start:
$unavailable"

# The libraries the camera path loads through libhybris' android_dlopen
# (libcamera2ndk, libmediandk, libbinder_ndk, libcamera_metadata) are the
# DEVICE'S OWN Android ones and are deliberately absent: they are bionic objects
# that only hybris can load, and a glibc copy of them would be useless.

# --- 4. verify what is about to be packaged ----------------------------------

log "verifying the staged tree"
while read -r f; do
	case "$(file -b "$f")" in
	*"ARM aarch64"*) ;;
	*) die "not aarch64: $f  ($(file -b "$f" | cut -c1-60))" ;;
	esac
done < <(find "$INSTALL_DIR" -type f \( -name '*.so' -o -name '*.so.*' -o -perm -u+x \) \
	-not -name '*.sh' -not -name '*.png' | sort)

# The .desktop basename IS the app id fallback, and run.sh's PC_APP_ID leans on
# the hook name matching it: with the two out of step Lomiri never associates the
# surface with the app it launched and SIGSTOPs the process as a background one.
grep -q "\"$HOOK\"" "$INSTALL_DIR/manifest.json" ||
	die "the manifest hook is not $HOOK, but $HOOK.desktop is what run.sh assumes"

log "click tree staged from the $MODE binary: $(du -sh "$INSTALL_DIR" | cut -f1) in $INSTALL_DIR"
