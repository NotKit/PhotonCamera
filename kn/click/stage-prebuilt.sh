#!/bin/bash
# Pin the host's arm64 build into kn/out/click-prebuilt/ so the click can be
# packaged around it.
#
#   kn/click/stage-prebuilt.sh [--force]
#
# THIS IS THE FAST PATH, NOT THE ONLY ONE. kn/click/build.sh compiles the binary
# in the cross container when there is no staged tree, or when the staged one is
# older than a source file — konan cross-compiles by design and the container is
# the amd64 one, so nothing about Kotlin/Native stands in the way (what used to
# was aurora-maven and the sysroot living outside the project root, which
# kn/settings.gradle.kts and scripts/fetch-deps.sh fixed).
#
# What staging buys is time and the escape hatch: a full link is ~40 minutes and
# a packaging change is not worth one, and when the container build breaks this
# still produces a click. So the host builds —
#
#   ARCH=arm64 kn/scripts/build-kn.sh -PwithApp
#
# — and this pins the result.
#
# What is staged, and why each one is copied rather than read where it lies:
#
#   photoncam-kn   kn/build/ is Gradle's, and five lanes share this box behind
#                  one flock (scripts/build-kn.sh). A concurrent link would
#                  rewrite the binary while the container packs it; a snapshot
#                  makes the binary and the resources below a matched pair.
#   resources/     staged beside the .kexe by the link itself
#                  (build.gradle.kts, stageResourcesArm64), so it moves with it.
#   lib/           libmaliit-glib, from whichever aurora-maven checkout this box
#                  resolved. The container prefers its own libmaliit-glib-dev,
#                  then kn/deps/aurora-maven; this copy is the last fallback.
#
# app/src/main/assets is NOT staged: it is in the repository already, nothing
# generates it, and it is 46 MB that would only be duplicated.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"          # kn/
OUT="$HERE/out/click-prebuilt"

BIN="$HERE/build/bin/linuxArm64/releaseExecutable"
KEXE="$BIN/photoncam-kn.kexe"
# It lives inside aurora-maven, so it is found the same way build.gradle.kts
# finds that: an explicit answer, then the in-project clone, then this box's
# aurora-probe. scripts/run-phone.sh pushes the same .so to the phone.
MALIIT="${PC_MALIIT_DIR:-}"
if [ -z "$MALIIT" ]; then
	for d in "${PC_AURORA_MAVEN:-}" "$HERE/deps/aurora-maven" \
		"$HOME/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven"; do
		[ -n "$d" ] && [ -d "$d/3rd_party/maliit-glib/aarch64" ] &&
			{ MALIIT="$d/3rd_party/maliit-glib/aarch64"; break; }
	done
fi

force=0
[ "${1:-}" = "--force" ] && force=1

die() { echo "stage-prebuilt: $*" >&2; exit 1; }

[ -x "$KEXE" ] || die "no arm64 binary at $KEXE
  build it on the host first:  ARCH=arm64 scripts/build-kn.sh -PwithApp"
case "$(file -b "$KEXE")" in
	*"ARM aarch64"*) ;;
	*) die "$KEXE is not aarch64: $(file -b "$KEXE" | cut -c1-60)" ;;
esac

# Same rule as run-kn.sh and run-phone.sh: a missing resource is a
# MissingResourceError on the first frame that draws an icon, and that reads like
# a Compose bug rather than a packaging one.
NRES=$(ls "$BIN/resources" 2>/dev/null | wc -l)
[ "$NRES" -ge 70 ] || die "only $NRES staged resources in $BIN/resources
  the link stages them (build.gradle.kts, stageResourcesArm64) -- re-run scripts/build-kn.sh"

[ -e "$MALIIT/libmaliit-glib.so.0" ] || die "no aarch64 libmaliit-glib at $MALIIT
  set PC_MALIIT_DIR to a checkout that has the aarch64 build"

# What is staged has to match the binary that is here NOW: the sha is the whole
# of the check, because a rebuild is the only thing that changes it and a
# rebuild is exactly when the old inputs must not be shipped.
KEXE_SHA=$(sha256sum "$KEXE" | cut -d' ' -f1)
if [ -f "$OUT/PROVENANCE.md" ] && [ "$force" = 0 ] &&
	grep -qF "| photoncam-kn | \`$KEXE_SHA\` |" "$OUT/PROVENANCE.md"; then
	echo "click-prebuilt is already staged in $OUT (--force to redo)"
	exit 0
fi

rm -rf "$OUT"
mkdir -p "$OUT/lib"
install -m 0755 "$KEXE" "$OUT/photoncam-kn"
cp -a "$BIN/resources" "$OUT/resources"
# -a and not -L: the three libmaliit-glib.so.N symlinks are what the loader
# follows, and flattening them would stage four copies of the same 130 KB.
cp -a "$MALIIT/." "$OUT/lib/"

cat >"$OUT/PROVENANCE.md" <<EOF
# arm64 prebuilt inputs for the Ubuntu Touch click

Staged by \`kn/click/stage-prebuilt.sh\`; do not edit, do not commit.

| | |
| --- | --- |
| photoncam-kn | \`$KEXE_SHA\` |
| built from | \`$KEXE\` |
| maliit-glib | \`$MALIIT\` |
| resources | $NRES files |
| staged | $(date -u +%Y-%m-%dT%H:%M:%SZ) |

| Here | Why it is pinned rather than read in place |
| --- | --- |
| photoncam-kn | kn/build/ is Gradle's and shared behind one flock; a concurrent link would rewrite it mid-pack |
| resources/ | staged beside the binary by that same link, so it moves with it |
| lib/ | libmaliit-glib, for a container that has neither \`libmaliit-glib-dev\` nor kn/deps/aurora-maven |

The container builds all three itself when this tree is absent or stale
(\`kn/click/build.sh\`); staging is the fast path and the escape hatch.
EOF

echo "staged $(du -sh "$OUT" | cut -f1) of arm64 prebuilt inputs in $OUT"
