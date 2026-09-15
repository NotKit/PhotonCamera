#!/bin/bash
# Copy the arm64 inputs the click build cannot produce itself into
# linux-port/out/click-prebuilt/. Runs on the host, before clickable: the build
# container only sees this repository, so anything from outside has to be inside
# the tree first.
#
# The source is the ATL SDK the atl-touch CI publishes per commit
# (atl-sdk-<arch>.tar.zst on release sdk-<sha>); click/atl-sdk.tag pins the one
# this port uses.
#
# Unlike the sibling Mercurygram port, atlas itself is never taken from the SDK:
# the SDK is built from atl-touch master and PhotonCamera stands on the camera2
# branch, so the framework has to be compiled from $ATLAS_DIR. What the SDK
# supplies is everything that build needs and cannot cross-compile:
#
#   art_standalone support libs   libtranslation_layer_main.so links libandroidfw
#                                 (AssetManager2, ResTable) and liblog. Building
#                                 art_standalone needs a native arm64 toolchain;
#                                 it has no cross build.
#   art_standalone boot jars      javac compiles api-impl against ART's libcore,
#                                 not the JDK's. Arch-independent.
#   dx (+ dx.jar)                 atlas's meson resolves the program at configure
#                                 time even though the port never dexes anything.
#   bionic_translation stubs      only to satisfy atlas's configure-time
#                                 cc.find_library('c_bio'/'dl_bio'); the HotSpot
#                                 launcher brings its own bionic_* symbols.
#   GLFW 3.4                      atlas needs the libdecor init hint; noble ships 3.3.
#   libskia.so + skia headers     skia's gn build is not driven from atlas's meson
#                                 and does not cross-compile from it.
#
# Usage: linux-port/click/stage-prebuilt.sh [--force]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"

ATL_SDK_REPO="${ATL_SDK_REPO:-NotKit/atl-touch}"
# Not derived from $ATLAS_DIR's HEAD the way the Mercurygram port does it: the
# camera2 branch has no SDK release of its own, and the SDK here is only the
# dependency chain. Bump the pin file, not this.
ATL_SDK_TAG="${ATL_SDK_TAG:-$(tr -d '[:space:]' <"$PORT_DIR/click/atl-sdk.tag")}"

OUT="$PORT_OUT/click-prebuilt"
force=0
[ "${1:-}" = "--force" ] && force=1

# Both halves of what is staged have to match what is here now: the SDK an
# archive away, and the atlas checkout. A dirty tree never matches, so a
# bring-up session's uncommitted framework fix always restages.
atlas_branch=$(git -C "$ATLAS_DIR" branch --show-current 2>/dev/null || echo unknown)
atlas_rev=$(git -C "$ATLAS_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
atlas_dirty=""
[ -z "$(git -C "$ATLAS_DIR" status --porcelain 2>/dev/null)" ] || atlas_dirty=" (working tree dirty)"

SRC_DESC="ATL SDK $ATL_SDK_TAG ($ATL_SDK_REPO)"
ATLAS_DESC="\`$ATLAS_DIR\`, $atlas_branch \`$atlas_rev\`$atlas_dirty"
if [ -f "$OUT/PROVENANCE.md" ] && [ "$force" = 0 ] && [ -z "$atlas_dirty" ]; then
	# Only skip when what is staged came from both the SDK and the atlas commit
	# resolved now. An SDK bump or a framework fix would otherwise leave the old
	# inputs in place and the click ship them — building alone restages nothing.
	if grep -qF "| source | \`$SRC_DESC\` |" "$OUT/PROVENANCE.md" &&
	   grep -qF "| atlas sources | $ATLAS_DESC |" "$OUT/PROVENANCE.md"; then
		echo "click-prebuilt is already staged in $OUT (--force to redo)"
		exit 0
	fi
	echo "restaging click-prebuilt: source is now $SRC_DESC, atlas $atlas_rev"
fi

# --- fetch ------------------------------------------------------------------

dl="$PORT_OUT/downloads"; mkdir -p "$dl"
# arm64 unconditionally: this stages the click's inputs, and the click is
# arm64-only. $PORT_ARCH here is the host's, which is not the same thing.
archive="$dl/atl-sdk-arm64-$ATL_SDK_TAG.tar.zst"
url="https://github.com/$ATL_SDK_REPO/releases/download/$ATL_SDK_TAG/atl-sdk-arm64.tar.zst"

if [ ! -f "$archive" ]; then
	curl -Lf --retry 3 -o "$archive.tmp" "$url" ||
		{ rm -f "$archive.tmp"; echo "cannot download the SDK: $url" >&2; exit 1; }
	mv "$archive.tmp" "$archive"
fi
# The release carries a .sha256 beside the tarball; a missing one is not fatal,
# a mismatching one is.
if curl -Lf --retry 3 -so "$archive.sha256" "$url.sha256"; then
	want=$(awk '{print $1}' "$archive.sha256")
	have=$(sha256sum "$archive" | awk '{print $1}')
	[ "$want" = "$have" ] || { echo "SDK $ATL_SDK_TAG checksum mismatch" >&2; exit 1; }
fi

SDK_DIR="$PORT_OUT/atl-sdk/$ATL_SDK_TAG"
rm -rf "$SDK_DIR"; mkdir -p "$SDK_DIR"
zstd -dc "$archive" | tar -C "$SDK_DIR" -x
SRC="$SDK_DIR/usr"

for f in lib/art/libandroidfw.so lib/java/core-all_classes.jar lib/java/dx.jar \
         lib/libglfw.so.3 lib/libskia.so include/skia/include/core/SkCanvas.h \
         include/androidfw include/GLFW bin/dx; do
	[ -e "$SRC/$f" ] || { echo "SDK $ATL_SDK_TAG has no usr/$f" >&2; exit 1; }
done

# --- stage ------------------------------------------------------------------

# Everything under lib/art that libandroidfw needs at runtime, and nothing else:
# no libart.so, which this port never loads (build.sh links an empty one).
art_libs=(libandroidfw.so libbase.so libcutils.so liblog.so libutils.so libziparchive.so)

rm -rf "$OUT"
mkdir -p "$OUT/usr/lib/art" "$OUT/usr/lib/java" "$OUT/usr/bin" "$OUT/usr/include" "$OUT/skia"

for lib in "${art_libs[@]}"; do
	cp "$SRC/lib/art/$lib" "$OUT/usr/lib/art/$lib"
done
cp -a "$SRC"/lib/libglfw.so* "$OUT/usr/lib/"
cp -a "$SRC"/lib/lib{c,dl,pthread,stdc++}_bio.so* "$OUT/usr/lib/"
cp "$SRC"/lib/java/*.jar "$OUT/usr/lib/java/"
# dx is a shell wrapper around dx.jar, so it costs nothing to carry.
cp "$SRC/bin/dx" "$OUT/usr/bin/dx"
cp -a "$SRC/include/androidfw" "$SRC/include/GLFW" "$OUT/usr/include/"

cp "$SRC/lib/libskia.so" "$OUT/skia/libskia.so"
# include/ plus modules/: atlas only includes headers under include/, but those
# reach into modules/ themselves (SkColorSpace.h -> modules/skcms/skcms.h).
cp -a "$SRC/include/skia/include" "$OUT/skia/include"
cp -a "$SRC/include/skia/modules" "$OUT/skia/modules"

# --- the atlas sources ------------------------------------------------------

# The framework is compiled in the container, and the container sees only this
# repository, so the camera2 checkout has to travel too. The working tree, not
# `git archive HEAD`: a bring-up session's uncommitted framework fixes are
# exactly what a click is built to test.
#
# Dropped on the way: .git (a worktree gitfile pointing outside is unusable
# there anyway — build-atlas.sh reads the rev below instead), the shared skia
# checkout, and any builddir.
rsync -a --delete \
	--exclude '.git' --exclude 'subprojects/skia' --exclude 'subprojects/*.wraplock' \
	--exclude 'builddir*' --exclude '*.pyc' \
	"$ATLAS_DIR/" "$OUT/atlas-src/"
printf '%s %s%s\n' "$atlas_branch" "$atlas_rev" "$atlas_dirty" >"$OUT/atlas-src/.port-atlas-rev"

# --- provenance -------------------------------------------------------------

sdk_field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$SDK_DIR/meta/sdk-manifest.json"; }
art_rev=$(sdk_field art_standalone); bionic_rev=$(sdk_field bionic_translation)

cat >"$OUT/PROVENANCE.md" <<EOF
# arm64 prebuilt inputs for the Ubuntu Touch click

Staged by \`linux-port/click/stage-prebuilt.sh\`; do not edit, do not commit.

| | |
| --- | --- |
| source | \`$SRC_DESC\` |
| art_standalone | \`${art_rev:-unknown}\` |
| bionic_translation | \`${bionic_rev:-unknown}\` |
| atlas sources | $ATLAS_DESC |
| staged | $(date -u +%Y-%m-%dT%H:%M:%SZ) |

| Here | What needs it |
| --- | --- |
| usr/lib/art/*.so | libtranslation_layer_main.so's DT_NEEDED (libandroidfw, liblog) and their own |
| usr/lib/libglfw.so* | atlas's window and input backend |
| usr/lib/java/*.jar | javac's -bootclasspath for api-impl (ART's libcore, not the JDK's) |
| usr/lib/lib*_bio.so* | atlas's configure-time \`cc.find_library\` only |
| usr/bin/dx | a program atlas's meson resolves at configure time |
| usr/include/{androidfw,GLFW} | headers for the above |
| skia/ | libskia.so for the target plus the headers atlas compiles against |
| atlas-src/ | the camera2 checkout, so the container can compile the framework |

atlas itself does **not** come from the SDK. The SDK is built from atl-touch
master and this port stands on the camera2 branch, so \`build.sh\` compiles the
framework from \`atlas-src/\` against the inputs above.

This \`libskia.so\` is the SDK's, built with \`skia_use_system_libjpeg_turbo\`
off, so it vendors its own libjpeg and links none from the device.
EOF

echo "staged $(du -sh "$OUT" | cut -f1) of arm64 prebuilt inputs in $OUT"
