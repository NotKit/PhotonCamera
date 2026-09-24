#!/bin/bash
# Builds the SailfishOS RPM from the arm64 binary the host already linked
# (ARCH=arm64 scripts/build-kn.sh -PwithApp).  It is the same binary as the
# Ubuntu Touch click; only the packaging differs.
#
#   kn/sailfish/build.sh        -> kn/out/sailfish/RPMS/aarch64/photoncamera-*.rpm
#
# The RPM is built with the Sailfish Platform SDK's sb2 target, so it gets the
# phone's own rpm macros.  SFOS_SDK and SFOS_TARGET pick another SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"            # kn/sailfish
KN="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$KN/.." && pwd)"
BIN_DIR="${PC_SFOS_BIN_DIR:-$KN/build/bin/linuxArm64/releaseExecutable}"
SFOS_SDK="${SFOS_SDK:-/srv/sailfishos/sdks/sfossdk/mer-sdk-chroot}"
SFOS_TARGET="${SFOS_TARGET:-SailfishOS-latest-aarch64}"
OUT="$KN/out/sailfish"
STAGE="$OUT/SOURCES/stage"

log() { echo -e "\033[1;34m[sfos]\033[0m $*"; }
die() { echo "sailfish/build.sh: $*" >&2; exit 1; }

first_dir() {
	local d
	for d in "$@"; do [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return 0; }; done
	return 1
}

KEXE="$BIN_DIR/photoncam-kn.kexe"
[ -x "$KEXE" ] || KEXE="$BIN_DIR/photoncam-kn"
[ -x "$KEXE" ] || die "no arm64 binary in $BIN_DIR
  build it first:  ARCH=arm64 scripts/build-kn.sh -PwithApp"
case "$(file -b "$KEXE")" in
	*"ARM aarch64"*) ;;
	*) die "$KEXE is not aarch64" ;;
esac

# Sailfish's libhybris reads bionic's TLS slots at tp+16, where an executable's
# own TLS block starts unless it is aligned past them (host/mgwl.c).  A binary
# without that alignment segfaults in its first EGL call, so refuse it here.
tls_align=$(readelf -lW "$KEXE" | awk '$1 == "TLS" { print $NF }')
[ -z "$tls_align" ] || [ $((tls_align)) -ge 256 ] ||
	die "$KEXE has a TLS segment aligned to $tls_align; it needs 256 (host/mgwl.c)"

[ "$(ls "$BIN_DIR/resources" 2>/dev/null | wc -l)" -ge 70 ] ||
	die "too few resources in $BIN_DIR/resources -- the link stages them"

MALIIT=$(first_dir "${PC_MALIIT_DIR:-}" \
	"${PC_AURORA_MAVEN:+$PC_AURORA_MAVEN/3rd_party/maliit-glib/aarch64}" \
	"$KN/deps/aurora-maven/3rd_party/maliit-glib/aarch64") ||
	die "no aarch64 libmaliit-glib; set PC_AURORA_MAVEN"
SYSROOT=$(first_dir "${PC_ARM_SYSROOT:-}" "$KN/deps/sysroot-arm64") ||
	die "no arm64 sysroot; set PC_ARM_SYSROOT"

# --- stage -------------------------------------------------------------------

rm -rf "$OUT"
mkdir -p "$STAGE/app/lib" "$STAGE/icons"
install -m 0755 "$KEXE" "$STAGE/app/photoncam-kn"
cp -a "$BIN_DIR/resources" "$STAGE/app/resources"
cp -a "$ROOT/app/src/main/assets" "$STAGE/app/assets"
install -m 0755 "$HERE/run.sh" "$STAGE/run.sh"
install -m 0644 "$HERE/photoncamera.desktop" "$STAGE/"
for s in 86 108 128 172; do
	convert "$ROOT/fastlane/metadata/android/en-US/images/icon.png" \
		-resize "${s}x${s}" "$STAGE/icons/$s.png"
done

# Bundle every DT_NEEDED the phone does not have, transitively.  Today that is
# libmaliit-glib (Aurora's 0.99 build) and libcrypt.so.1, which Sailfish ships
# only as .so.2 and the binary links without using.
unavailable=""
while :; do
	needed=$(for f in "$STAGE/app/photoncam-kn" "$STAGE"/app/lib/*; do
			[ -f "$f" ] || continue
			readelf -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'
		done | sort -u)
	have=$( { ls "$STAGE/app/lib"; echo "$unavailable"; grep -v '^#' "$HERE/device-libs.txt"; } | sort -u)
	missing=$(comm -23 <(echo "$needed") <(echo "$have"))
	[ -n "$missing" ] || break
	for lib in $missing; do
		found=""
		for d in "$MALIIT" "$SYSROOT/usr/lib/aarch64-linux-gnu" "$SYSROOT/lib/aarch64-linux-gnu"; do
			[ -e "$d/$lib" ] && { cp -L "$d/$lib" "$STAGE/app/lib/$lib"; found="$d"; break; }
		done
		if [ -n "$found" ]; then
			log "bundling $lib from $found"
		else
			unavailable="$unavailable$lib"$'\n'
		fi
	done
done
[ -z "$unavailable" ] || die "not on the phone and nowhere to bundle from:
$unavailable"

# --- package -----------------------------------------------------------------

version=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\([^']*\)'.*/\1/p" \
	"$ROOT/app/build.gradle" | head -1)
[ -n "$version" ] || die "no versionName in $ROOT/app/build.gradle"
# RPM versions cannot carry a '-'.
version="${version//-/_}"

[ -x "$SFOS_SDK" ] || die "no Sailfish Platform SDK at $SFOS_SDK (set SFOS_SDK)"
log "rpmbuild $version in $SFOS_TARGET"
"$SFOS_SDK" sb2 -t "$SFOS_TARGET" rpmbuild -bb \
	--define "_topdir $OUT" --define "pc_version $version" \
	"$HERE/photoncamera.spec"

find "$OUT/RPMS" -name '*.rpm' -printf 'built: %p (%s bytes)\n'
