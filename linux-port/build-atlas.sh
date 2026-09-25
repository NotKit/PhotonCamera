#!/bin/bash
# Build the atlas framework from the checkout $ATLAS_DIR points at and install
# what the port runs against into out/atlas/.
#
# atlas is a source dependency, not a place to copy prebuilt files from: fix a
# framework bug there, re-run this, and the new out/atlas/api-impl.jar is what
# the launcher boots. Framework fixes are commits on the atlas branch that go
# upstream to atl-touch — never patches carried in this repository.
#
# Which artifact is which, from atlas meson.build (do not guess these names):
#
#   src/api-impl/hax.jar    javac product of the framework sources
#                           -- THIS is the jar the port puts on the classpath
#   hax-stripped.jar        the above minus three compile-only stubs, dropped
#                           because ART rejects an app's oat file when a class is
#                           defined twice. A JVM class path has no such check and
#                           nothing else provides them, so the port keeps them.
#   api-impl.jar            hax-stripped.jar run through `dx --dex` -- a
#                           classes.dex, useless here
#
# So out/atlas/api-impl.jar is a copy of hax.jar, not of atlas's identically
# named dexed jar. The verification below asserts that.
#
# Usage: linux-port/build-atlas.sh [--clean] [--reconfigure]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

reconfigure=0
for arg in "$@"; do
	case "$arg" in
	--clean) rm -rf "$ATLAS_BUILDDIR" "$ATLAS_OUT" ;;
	--reconfigure) reconfigure=1 ;;
	*) echo "usage: $0 [--clean] [--reconfigure]" >&2; exit 2 ;;
	esac
done

[ -f "$ATLAS_DIR/meson.build" ] ||
	{ echo "no atlas checkout at $ATLAS_DIR (see linux-port/README.md)" >&2; exit 1; }

# $PORT_SKIA_PREBUILT points at a directory holding a libskia.so built for the
# target and skia's include/ tree. With it, atlas links that instead of building
# the skia subproject — the only way to get a cross build (linux-port/click/).
skia_prebuilt="${PORT_SKIA_PREBUILT:-}"

tools="meson ninja"
[ -n "$skia_prebuilt" ] || tools="$tools gn clang"
for tool in $tools; do
	command -v "$tool" >/dev/null || { echo "missing build tool: $tool" >&2; exit 1; }
done

# The skia subproject is the shared ~8 GB checkout; never download it again.
if [ -n "$skia_prebuilt" ]; then
	for f in libskia.so include/core/SkCanvas.h; do
		[ -e "$skia_prebuilt/$f" ] ||
			{ echo "PORT_SKIA_PREBUILT=$skia_prebuilt has no $f" >&2; exit 1; }
	done
elif [ ! -e "$ATLAS_DIR/subprojects/skia" ]; then
	[ -d "$ATLAS_SKIA_DIR" ] ||
		{ echo "no skia sources: set ATLAS_SKIA_DIR (tried $ATLAS_SKIA_DIR)" >&2; exit 1; }
	ln -s "$ATLAS_SKIA_DIR" "$ATLAS_DIR/subprojects/skia"
	echo "linked subprojects/skia -> $ATLAS_SKIA_DIR"
fi

# meson copies subprojects/packagefiles/skia/ into the checkout once, when it
# extracts the wrap, and the shared checkout was extracted long ago. Copy them
# again when atlas changes them, or a skia build option committed on the atlas
# branch would never reach the build.
if [ -z "$skia_prebuilt" ] && [ -d "$ATLAS_DIR/subprojects/packagefiles/skia" ]; then
	for f in "$ATLAS_DIR"/subprojects/packagefiles/skia/*; do
		dst="$ATLAS_DIR/subprojects/skia/$(basename "$f")"
		cmp -s "$f" "$dst" || { cp "$f" "$dst"; echo "updated subprojects/skia/$(basename "$f")"; }
	done
fi

# builddir-relative source | destination name | ninja target | required
artifacts=(
	"src/api-impl/hax.jar|api-impl.jar|src/api-impl/hax.jar|yes"
	"libtranslation_layer_main.so|libtranslation_layer_main.so|libtranslation_layer_main.so|yes"
	"libandroid.so.0|libandroid.so.0|libandroid.so.0|yes"
	"res/framework-res/framework-res.apk|framework-res.apk|res/framework-res/framework-res.apk|yes"
	"android-translation-layer-hotspot|android-translation-layer-hotspot|android-translation-layer-hotspot|yes"
)

# The same launcher creating its VM from a GraalVM native-image .so instead of
# libjvm.so (src/main-executable-hotspot/vm_image.c). Only atlas revisions that
# carry the meson option can build it, and a port standing on an older framework
# still has to build -- so this is asked for when the option exists and not
# otherwise. build-image.sh is what needs it; the HotSpot vehicle does not.
image_launcher=0
if grep -q "^option('image-launcher'" "$ATLAS_DIR/meson.options" 2>/dev/null; then
	image_launcher=1
	artifacts+=("android-translation-layer-image|android-translation-layer-image|android-translation-layer-image|yes")
fi
[ -n "$skia_prebuilt" ] ||
	artifacts+=("subprojects/skia/libskia.so|libskia.so|subprojects/skia/libskia.so|yes")

# --- configure --------------------------------------------------------------

# meson picks the JDK for its `jni` dependency and for javac from JAVA_HOME/PATH
# at setup time, so a builddir remembers the JDK it was configured with. The
# framework must be compiled by the same JDK the port runs on (21), and the
# system default here is 25 -- hence the stamp and the forced reconfigure.
setup_args=(-Dhotspot-launcher=enabled)
[ "$image_launcher" = 0 ] || setup_args+=(-Dimage-launcher=enabled)
[ -z "$skia_prebuilt" ] || setup_args+=("-Dskia-prebuilt=$skia_prebuilt")
if [ "$PORT_CROSS" = 1 ]; then
	cross_file="$PORT_OUT/meson-cross.ini"
	"$PORT_DIR/native/write-meson-cross.sh" "$cross_file"
	setup_args+=(--cross-file "$cross_file")
fi

jdk_stamp="$ATLAS_BUILDDIR/.port-jdk"

# A builddir remembers its source tree, and ninja would keep building that one
# after $ATLAS_DIR moves. Start ours again; refuse to touch a borrowed one.
builddir_source=""
[ ! -f "$ATLAS_BUILDDIR/meson-info/meson-info.json" ] ||
	builddir_source=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["directories"]["source"])' \
		"$ATLAS_BUILDDIR/meson-info/meson-info.json")
if [ -n "$builddir_source" ] && [ "$(realpath "$builddir_source")" != "$(realpath "$ATLAS_DIR")" ]; then
	if [ -f "$jdk_stamp" ]; then
		echo "builddir was set up for $builddir_source, starting it again for $ATLAS_DIR"
		rm -rf "$ATLAS_BUILDDIR"
	else
		echo "$ATLAS_BUILDDIR builds $builddir_source, not $ATLAS_DIR" >&2
		exit 1
	fi
fi
stamp_value="$JAVA_HOME $PORT_TRIPLE ${setup_args[*]}"
if [ -f "$ATLAS_BUILDDIR/build.ninja" ] && [ -f "$jdk_stamp" ] &&
   [ "$(cat "$jdk_stamp")" != "$stamp_value" ]; then
	echo "builddir was configured differently ($stamp_value), reconfiguring"
	reconfigure=1
elif [ -f "$ATLAS_BUILDDIR/build.ninja" ] && [ ! -f "$jdk_stamp" ]; then
	# A builddir this script did not configure -- $ATLAS_BUILDDIR pointed at an
	# existing atlas build. Reuse it rather than wiping somebody's tree; the
	# class-file version check below catches a builddir set up with another JDK.
	echo "note: reusing $ATLAS_BUILDDIR, configured by something else" \
		"(--reconfigure to set it up for the port)"
	echo "$stamp_value" >"$jdk_stamp"
fi

if [ ! -f "$ATLAS_BUILDDIR/build.ninja" ]; then
	meson setup "${setup_args[@]}" "$ATLAS_BUILDDIR" "$ATLAS_DIR"
	echo "$stamp_value" >"$jdk_stamp"
elif [ "$reconfigure" = 1 ]; then
	# --wipe, not --reconfigure: the java compiler is cached in coredata, so a JDK
	# change only takes effect on a from-scratch configure. It costs a full rebuild
	# including skia, so the stamp above exists to make this happen at most once.
	meson setup --wipe "${setup_args[@]}" "$ATLAS_BUILDDIR" "$ATLAS_DIR"
	echo "$stamp_value" >"$jdk_stamp"
fi

# --- build ------------------------------------------------------------------

# $ATLAS_DIR/.git has to be there in its own right -- a file for a worktree, a
# directory for a clone. `rev-parse --git-dir` alone would answer with an
# ancestor's repository, and for the click's source export (under out/, inside
# this checkout) that ancestor is PhotonCamera: the build would then report the
# app's branch as atlas's and run the working-tree check against the wrong tree.
if [ -e "$ATLAS_DIR/.git" ] && git -C "$ATLAS_DIR" rev-parse --git-dir >/dev/null 2>&1; then
	atlas_git=1
else
	atlas_git=0
	echo "note: no usable git for $ATLAS_DIR, skipping the working-tree check"
fi

# javac writes generated JNI headers back into the atlas source tree. Those
# belong on the atlas branch, so the build is allowed to touch them -- nothing else.
tree_before=""
[ "$atlas_git" = 0 ] || tree_before=$(git -C "$ATLAS_DIR" status --porcelain)

targets=()
for entry in "${artifacts[@]}"; do
	IFS='|' read -r _src _dst target required <<<"$entry"
	[ "$required" = yes ] && targets+=("$target")
done
ninja -C "$ATLAS_BUILDDIR" -j "$PORT_JOBS" "${targets[@]}"

tree_after=""
[ "$atlas_git" = 0 ] || tree_after=$(git -C "$ATLAS_DIR" status --porcelain)
if [ "$tree_before" != "$tree_after" ]; then
	unexpected=$(comm -13 <(echo "$tree_before" | sed 's/^...//' | sort) \
	                      <(echo "$tree_after" | sed 's/^...//' | sort) |
		grep -v '^src/api-impl-jni/generated_headers/' || true)
	if [ -n "$unexpected" ]; then
		echo "unexpected changes in $ATLAS_DIR (not generated headers):" >&2
		echo "$unexpected" >&2
		exit 1
	fi
	echo "note: commit the regenerated headers on the atlas branch"
fi

# --- install ----------------------------------------------------------------

mkdir -p "$ATLAS_OUT"

for entry in "${artifacts[@]}"; do
	IFS='|' read -r src dst _target required <<<"$entry"
	if [ ! -f "$ATLAS_BUILDDIR/$src" ]; then
		[ "$required" = yes ] && { echo "missing $ATLAS_BUILDDIR/$src" >&2; exit 1; }
		echo "skipping optional $src (not built)"
		continue
	fi
	cp "$ATLAS_BUILDDIR/$src" "$ATLAS_OUT/$dst"
done

[ -z "$skia_prebuilt" ] || cp "$skia_prebuilt/libskia.so" "$ATLAS_OUT/libskia.so"

# DT_NEEDED of libtranslation_layer_main.so is the soname; the RUNPATH's leading
# $ORIGIN/ is why libskia.so and libandroid.so.0 work from this one flat dir.
ln -sfn libandroid.so.0 "$ATLAS_OUT/libandroid.so"

# The bundled Roboto faces the framework reads: TypefaceCollection.cpp finds them
# at system/fonts next to its own library, so the flat natives dir carries this
# subdirectory too. Without them the generic families come from fontconfig, with
# metrics that are not Android's.
mkdir -p "$ATLAS_OUT/system/etc" "$ATLAS_OUT/system/fonts"
cp "$ATLAS_DIR/res/fonts.xml" "$ATLAS_OUT/system/etc/fonts.xml"
cp "$ATLAS_DIR"/res/fonts/*.ttf "$ATLAS_OUT/system/fonts/"

if [ "$atlas_git" = 1 ]; then
	atlas_branch=$(git -C "$ATLAS_DIR" branch --show-current)
	atlas_rev=$(git -C "$ATLAS_DIR" rev-parse --short HEAD)
	atlas_dirty=""
	[ -z "$(git -C "$ATLAS_DIR" status --porcelain)" ] || atlas_dirty=" (working tree dirty)"
elif [ -f "$ATLAS_DIR/.port-atlas-rev" ]; then
	# A source export rather than a checkout — click/stage-prebuilt.sh makes one
	# so the build container, which sees only this repository, can compile the
	# framework. It leaves the rev behind because the git dir does not travel.
	read -r atlas_branch atlas_rev atlas_dirty <<<"$(cat "$ATLAS_DIR/.port-atlas-rev")"
	atlas_dirty="${atlas_dirty:+ $atlas_dirty}"
else
	atlas_branch="unknown"; atlas_rev="unknown"; atlas_dirty=" (no git here)"
fi
jdk_version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)

# --- verification -----------------------------------------------------------

jar_listing=$(unzip -l "$ATLAS_OUT/api-impl.jar")
for entry in android/view/View.class android/os/Looper.class android/content/Context.class; do
	grep -q " $entry\$" <<<"$jar_listing" || {
		echo "$ATLAS_OUT/api-impl.jar has no $entry" >&2; exit 1; }
done
if grep -q "classes.dex" <<<"$jar_listing"; then
	echo "$ATLAS_OUT/api-impl.jar is dexed: install hax.jar, not api-impl.jar" >&2
	exit 1
fi

# The camera2 classes are the reason this port exists: a framework jar without
# them still boots the UI, and the failure looks like the app finding no camera.
for entry in android/hardware/camera2/CameraManager.class \
             android/hardware/camera2/CameraCharacteristics.class \
             android/hardware/camera2/CaptureRequest.class \
             android/media/ImageReader.class \
             android/opengl/GLES30.class; do
	grep -q " $entry\$" <<<"$jar_listing" || {
		echo "$ATLAS_OUT/api-impl.jar has no $entry (wrong atlas branch?)" >&2; exit 1; }
done

# The stubs hax-stripped.jar drops for ART's sake: nothing on the app class path
# defines them, so on a JVM they have to come from here.
for entry in android/view/OnReceiveContentListener.class android/window/OnBackInvokedCallback.class; do
	grep -q " $entry\$" <<<"$jar_listing" || {
		echo "$ATLAS_OUT/api-impl.jar has no $entry: this is hax-stripped.jar, not hax.jar" >&2
		exit 1; }
done
class_count=$(grep -cE "\.class\$" <<<"$jar_listing")
[ "$class_count" -ge 1000 ] || { echo "only $class_count classes in the jar" >&2; exit 1; }

# PhotonCamera overrides what the HAL reported by reflecting on AOSP's private
# camera2 members. Nothing here references them by name, so a rename in atlas
# breaks the app with a swallowed NoSuchFieldException.
"$JAVA_HOME/bin/javac" -nowarn -d "$PORT_OUT/tools" -cp "$ATLAS_OUT/api-impl.jar" \
	"$PORT_DIR/tools/CameraReflectionCheck.java"
"$JAVA_HOME/bin/java" -cp "$ATLAS_OUT/api-impl.jar:$PORT_OUT/tools" CameraReflectionCheck

# Proves the JDK parses these class files. atlas compiles with -source/-target 1.8
# (major 52); anything above 65 would be unloadable on 21.
major=$("$JAVA_HOME/bin/javap" -verbose -cp "$ATLAS_OUT/api-impl.jar" android.os.Looper |
	sed -n 's/.*major version: *//p')
[ -n "$major" ] && [ "$major" -ge 45 ] && [ "$major" -le 65 ] || {
	echo "unexpected class file major version '$major'" >&2; exit 1; }

# ldd from the destination dir: an unresolved library here is a missing artifact.
# Native builds only — the host loader cannot read a foreign-arch object.
if [ "$PORT_CROSS" = 0 ]; then
	tl_ldd=$(cd "$ATLAS_OUT" && ldd libtranslation_layer_main.so)
	if grep -q "not found" <<<"$tl_ldd"; then
		echo "unresolved dependencies of the installed libtranslation_layer_main.so:" >&2
		grep "not found" <<<"$tl_ldd" >&2
		exit 1
	fi
fi

# The symbols the boot sequence and the app's JNI call into.
tl_syms=$("$PORT_NM" -D --defined-only "$ATLAS_OUT/libtranslation_layer_main.so")
for sym in Java_android_os_MessageQueue_nativeInit Java_android_content_Context_native_1get_1apk_1path; do
	grep -q " T $sym\$" <<<"$tl_syms" || {
		echo "libtranslation_layer_main.so does not export $sym" >&2; exit 1; }
done
android_syms=$("$PORT_NM" -D --defined-only "$ATLAS_OUT/libandroid.so.0")
for sym in AndroidBitmap_lockPixels AAssetManager_open; do
	grep -q " T $sym\$" <<<"$android_syms" || {
		echo "libandroid.so.0 does not export $sym" >&2; exit 1; }
done

# The launcher is the other half of libtranslation_layer_main.so's link graph:
# the library imports apk_path, atl_window, get_app_data_dir and the bionic_*
# calls from whatever executable loads it, so a missing one is not a link error,
# it is a crash at startup.
#
# The list comes out of the library, not out of this script: a hardcoded set
# stops covering an import the framework adds later and still reads as complete.
# That is exactly how `apk_split_paths` got through (BRINGUP_NOTES.md).
# `U` only: a weak undefined symbol (`w`, e.g. atl_im_backend_maliit) is an
# optional backend the loader is allowed to leave unresolved.
tl_undef=$("$PORT_NM" -D --undefined-only "$ATLAS_OUT/libtranslation_layer_main.so" |
	awk '$1 == "U" { print $2 }')
launcher_imports=$(grep -E '^(apk_|atl_|get_app_data_dir|bionic_)' <<<"$tl_undef" | sort -u || true)
launcher_syms=$("$PORT_NM" -D --defined-only "$ATLAS_OUT/android-translation-layer-hotspot")
# Both launchers link the same libtranslation_layer_main.so and export the same
# set from main.c, so an import satisfied by one is satisfied by the other; the
# image one is checked all the same, because it is a separate link.
[ "$image_launcher" = 0 ] ||
	launcher_syms="$launcher_syms"$'\n'"$("$PORT_NM" -D --defined-only "$ATLAS_OUT/android-translation-layer-image")"
missing_imports=""
for sym in $launcher_imports; do
	grep -q " [TBDR] $sym\$" <<<"$launcher_syms$android_syms" || missing_imports="$missing_imports $sym"
done
if [ -n "$missing_imports" ]; then
	echo "libtranslation_layer_main.so imports symbols neither" \
		"android-translation-layer-hotspot nor libandroid.so.0 provides:$missing_imports" >&2
	echo "That is a startup crash, not a link error. Define them in" \
		"\$ATLAS_DIR/src/main-executable-hotspot/main.c, the way" \
		"src/main-executable/main.c does, and commit it on the atlas branch." >&2
	exit 1
fi

# --- provenance -------------------------------------------------------------

# Empty when this atlas cannot build it, so build-image.sh reports a framework
# that is too old rather than a missing file.
image_launcher_path=""
[ "$image_launcher" = 0 ] ||
	image_launcher_path="$ATLAS_OUT/android-translation-layer-image"

jar_sha=$(sha256sum "$ATLAS_OUT/api-impl.jar" | cut -c1-16)

cat >"$ATLAS_OUT/artifacts.env" <<EOF
# Generated by linux-port/build-atlas.sh
# atlas $atlas_branch $atlas_rev$atlas_dirty, built with $jdk_version
ATLAS_ARTIFACTS_DIR="$ATLAS_OUT"
ATLAS_API_IMPL_JAR="$ATLAS_OUT/api-impl.jar"
ATLAS_LAUNCHER="$ATLAS_OUT/android-translation-layer-hotspot"
ATLAS_IMAGE_LAUNCHER="$image_launcher_path"
ATLAS_NATIVES_DIR="$ATLAS_OUT"
ATLAS_FRAMEWORK_RES="$ATLAS_OUT/framework-res.apk"
ATLAS_FONTS_DIR="$ATLAS_OUT/system/fonts"
ATLAS_BRANCH="$atlas_branch"
ATLAS_REV="$atlas_rev"
ATLAS_API_IMPL_SHA="$jar_sha"
EOF

cat >"$ATLAS_OUT/ARTIFACTS.md" <<EOF
# atlas artifacts for the PhotonCamera OpenJDK port

Generated by \`linux-port/build-atlas.sh\`; do not edit, do not commit.

| | |
| --- | --- |
| atlas sources | \`$ATLAS_DIR\` |
| atlas branch | \`$atlas_branch\` at \`$atlas_rev\`$atlas_dirty |
| build dir | \`$ATLAS_BUILDDIR\` |
| JDK | $jdk_version (\`$JAVA_HOME\`) |
| api-impl.jar | sha256 \`$jar_sha…\`, $class_count classes, major version $major |

| File here | atlas source | meson target |
| --- | --- | --- |
| api-impl.jar | \`src/api-impl/hax.jar\` | \`hax.jar\` — javac output, the framework classes (camera2 included) |
| libtranslation_layer_main.so | same | the framework natives: Skia, GLFW, libandroidfw, the camera backends |
| libandroid.so.0, libandroid.so | same | NDK API surface: \`AndroidBitmap_*\`, \`AAsset*\`, \`ANativeWindow_*\` |
| libskia.so | \`subprojects/skia/libskia.so\` | skia subproject — DT_NEEDED of the above, found via its \`\$ORIGIN/\` RUNPATH |
| android-translation-layer-hotspot | \`src/main-executable-hotspot/\` | the launcher: \`main.c\` + \`vm_hotspot.c\`, links \`libjvm.so\` |
| android-translation-layer-image | \`src/main-executable-hotspot/\` | the same launcher with \`vm_image.c\`: dlopens a native-image \`.so\` instead |
| framework-res.apk | \`res/framework-res/framework-res.apk\` | framework resource table |
| system/fonts/Roboto-*.ttf | \`\$ATLAS_DIR/res/fonts/\` | the sans-serif family, read by the framework |

atlas's own \`api-impl.jar\` is \`hax-stripped.jar\` after \`dx --dex\` and holds
nothing but a \`classes.dex\`; HotSpot cannot read it.
EOF

echo "atlas ready in $ATLAS_OUT (atlas $atlas_branch $atlas_rev$atlas_dirty," \
	"$class_count framework classes, api-impl.jar sha $jar_sha)"
