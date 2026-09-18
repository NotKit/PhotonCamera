#!/bin/bash
# The two inputs the Kotlin/Native link needs and this repository does not carry:
#
#   deps/aurora-maven    Aurora's CMP fork, laid out as a maven repository
#                        (922 MB, git-lfs, public, no login).  The Compose and
#                        skiko klibs, and the aarch64 libmaliit-glib the click
#                        bundles -- 3rd_party/maliit-glib/aarch64 lives in here.
#   deps/sysroot-arm64   an Ubuntu noble arm64 sysroot assembled from
#                        ports.ubuntu.com debs (141 MB, 329 shared objects)
#   deps/atl-touch       the framework whose android.hardware.camera2 becomes
#                        kn/gen-atlas (public; master carries the camera2 work)
#
#   scripts/fetch-deps.sh
#
# Both are INPUTS, never patched and never forked, so deps/ is gitignored.  The
# URLs, the branch and the package list are aurora-probe's fetch-deps.sh, which
# is where this lane's sysroot came from in the first place.
#
# IT DOWNLOADS NOTHING WHEN SOMETHING ALREADY ANSWERS, and that matters more than
# it sounds: this box has aurora-probe's copy and 21 GB of free disk, so a second
# 922 MB clone must not be the price of running the packaging.  The order below
# is settings.gradle.kts's, line for line; when the two disagree Gradle links
# against one checkout and this script fetches another.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"          # kn/
DEPS="$HERE/deps"
LEGACY="/home/nekit/UT/firefox-atl/compose-ut/aurora-probe"

# The finished clone measures 922 MB, and git wants room for the pack while it
# smudges the lfs objects on top of that.  Failing now beats stopping half way
# through with a full disk and a checkout that looks complete.
NEED_MB=2500

die() { echo "fetch-deps: $*" >&2; exit 1; }

# Prints the directory that already answers for $1, or nothing.  It ends in a
# plain `return 0` because finding nothing is the NORMAL case here, and under
# `set -e` a function that falls off the end with a failed test takes the whole
# script with it -- silently, before the fetch it was asked about.
have() {
	local env_val="$2" in_project="$DEPS/$1" legacy="$LEGACY/$1"
	for d in "$env_val" "$in_project" "$legacy"; do
		[ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return 0; }
	done
	return 0
}

fetch_aurora_maven() {
	local found
	found="$(have aurora-maven "${PC_AURORA_MAVEN:-}")"
	[ -z "$found" ] || { echo "aurora-maven: $found"; return; }

	# WITHOUT git-lfs the clone still succeeds: every klib in it comes out a
	# 130-byte pointer file, and the failure surfaces much later as a Compose
	# klib that Gradle cannot read.  Refuse here, where the cause is still named.
	git lfs version >/dev/null 2>&1 ||
		die "git-lfs is not installed; the aurora-maven clone would be pointer files"

	local free
	free=$(df -Pm "$HERE" | awk 'NR==2 {print $4}')
	[ "$free" -ge "$NEED_MB" ] ||
		die "only ${free} MB free under $HERE, and the clone needs about ${NEED_MB} MB"

	mkdir -p "$DEPS"
	echo "aurora-maven: cloning 922 MB into $DEPS/aurora-maven (git-lfs, a while)"
	# --depth 1 on the ONE tag this lane builds against; the history is 3 GB of
	# klibs that nothing here reads.
	git clone --depth 1 --branch aurora-0.0.4 \
		https://hub.mos.ru/auroraos/kotlin-multiplatform/aurora-maven.git \
		"$DEPS/aurora-maven"
	[ -d "$DEPS/aurora-maven/3rd_party/maliit-glib/aarch64" ] ||
		die "the clone has no 3rd_party/maliit-glib/aarch64 -- wrong branch, or lfs did not smudge"
}

# The sysroot's contents, in one place because the skip check compares against
# it: a sysroot assembled from an OLDER list is not "already there", it is a link
# that fails with "cannot find -lfoo" twenty minutes in.  Aurora's own link line
# plus what this lane added to it.
WANT_PKGS="libc6 libc6-dev linux-libc-dev libgcc-s1 libgcc-13-dev gcc-13-base libstdc++6
libstdc++-13-dev libqt5core5t64 libqt5dbus5t64 libegl1 libgles2 libglvnd0 libglx0 libgl1
libwayland-client0 libwayland-egl1 libwayland-cursor0 libwayland-server0 libcrypt1
libfontconfig1 libfreetype6 libexpat1 libdbus-1-3 libglib2.0-0t64 libpcre2-8-0 libpcre2-16-0
zlib1g libicu74 libdouble-conversion3 libzstd1 libsystemd0 libcap2 libgcrypt20 libgpg-error0
liblzma5 libpng16-16t64 libjpeg-turbo8 libbrotli1 libbz2-1.0 libffi8 libatomic1 libmd0
libselinux1"

fetch_sysroot() {
	local SR="$DEPS/sysroot-arm64" found
	found="$(have sysroot-arm64 "${PC_ARM_SYSROOT:-}")"
	# Anything but our own copy is somebody else's checkout: used as it is, never
	# rewritten from here, whatever its package list turns out to be.
	if [ -n "$found" ] && [ "$found" != "$SR" ]; then echo "sysroot-arm64: $found"; return; fi
	if [ -d "$SR" ]; then
		[ "$(cat "$SR/.packages" 2>/dev/null)" = "$WANT_PKGS" ] &&
			{ echo "sysroot-arm64: $SR"; return; }
		echo "sysroot-arm64: the package list changed, reassembling $SR"
		rm -rf "$SR"
	fi
	echo "sysroot-arm64: assembling from ports.ubuntu.com into $SR"
	# NOT local: the EXIT trap runs at top level, where a local of this function
	# is already gone and `set -u` would abort the cleanup instead of doing it.
	TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
	for suite in noble noble-updates; do for comp in main universe; do
		curl -sS "http://ports.ubuntu.com/ubuntu-ports/dists/$suite/$comp/binary-arm64/Packages.gz" \
			-o "$TMP/$suite-$comp.gz"
	done; done
	zcat "$TMP"/*.gz > "$TMP/all.txt"
	# A name that is not in the index is an error and not a shrug -- a sysroot
	# missing one .so fails the link with "cannot find -lfoo".  aurora-probe's
	# list, which this one started as, asks for Debian's libbrotlidec1 and
	# libbrotlicommon1, which noble does not have under those names (it has
	# libbrotli1), and so its own sysroot silently has no brotli at all.
	WANT_PKGS="$WANT_PKGS" python3 - "$TMP" <<'PY'
import os, re, sys
tmp = sys.argv[1]
want = set(os.environ["WANT_PKGS"].split())
best = {}
for b in open(f"{tmp}/all.txt").read().split("\n\n"):
	m = re.search(r"^Package: (\S+)$", b, re.M)
	if m and m.group(1) in want:
		best[m.group(1)] = re.search(r"^Filename: (\S+)$", b, re.M).group(1)
missing = want - set(best)
if missing:
	raise SystemExit(f"not in the arm64 index: {sorted(missing)}")
open(f"{tmp}/urls.txt", "w").write(
	"".join("http://ports.ubuntu.com/ubuntu-ports/" + f + "\n" for f in sorted(best.values())))
PY
	mkdir -p "$TMP/debs" && (cd "$TMP/debs" && xargs -n1 -P8 curl -sS -O < "$TMP/urls.txt")
	mkdir -p "$SR"
	for d in "$TMP"/debs/*.deb; do dpkg-deb -x "$d" "$SR"; done
	ln -sfn usr/lib "$SR/lib"
	# The runtime debs ship only libFOO.so.N and ld wants the bare name; konan's
	# link line is written against the dev names.
	(cd "$SR/usr/lib/aarch64-linux-gnu" && for f in *.so.*; do
		b="${f%%.so.*}"; [ -e "$b.so" ] || ln -sf "$f" "$b.so"
	done
	# libpng16 is the only one whose -l name is not its file name: a machine
	# with png.h makes host/natives/build.sh write -lpng (and -ljpeg, hence
	# libjpeg-turbo8 above), and whether it does is just which -dev packages
	# that machine has.  Both are on the device, so linking them is fine -- what
	# is not fine is the link failing with "cannot find -lpng" on one box and
	# not another.
	[ -e libpng.so ] || ln -sf libpng16.so.16 libpng.so)
	# The list this tree was built from, for the skip check above.
	printf '%s' "$WANT_PKGS" >"$SR/.packages"
}

# atl-touch is NOT under $LEGACY: it is its own checkout, and on this box it is
# a worktree of ~/UT/atlas rather than a clone of its own.  Shallow, because
# convert-atlas.sh reads a tree and never a history.
fetch_atl_touch() {
	local d
	for d in "${PC_ATL_TOUCH:-}" "$DEPS/atl-touch" "$HOME/UT/atlas-camera2"; do
		[ -n "$d" ] && [ -d "$d/src/api-impl" ] && { echo "atl-touch: $d"; return; }
	done
	echo "atl-touch: cloning into $DEPS/atl-touch"
	mkdir -p "$DEPS"
	git clone --depth 1 https://github.com/NotKit/atl-touch.git "$DEPS/atl-touch"
	[ -d "$DEPS/atl-touch/src/api-impl/android/hardware/camera2" ] ||
		die "the atl-touch clone carries no src/api-impl/android/hardware/camera2"
}

fetch_aurora_maven
fetch_sysroot
fetch_atl_touch
echo "deps ready"
