#!/bin/bash
# THE FAST LOOP for the Ubuntu Touch click of the Kotlin/Native port:
#
#   kn/click/make-click.sh              -> kn/out/click/*.click
#
# It pins the host's arm64 build and then runs clickable, which packages that
# instead of linking its own — seconds rather than the ~40 minutes a container
# build takes. The self-contained way needs no host build and no this script:
#
#   clickable build --arch arm64        # fetches the deps and links in the container
#
# The host half must already exist here —
#
#   ARCH=arm64 kn/scripts/build-kn.sh -PwithApp
#
# — and this checks rather than guesses.
#
# Anything after -- goes to clickable (e.g. -- --verbose).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"          # kn/
REPO="$(cd "$HERE/.." && pwd)"

BIN="$HERE/build/bin/linuxArm64/releaseExecutable"
[ -x "$BIN/photoncam-kn.kexe" ] || {
	echo "no arm64 binary at $BIN/photoncam-kn.kexe" >&2
	echo "  build it on the host first:  ARCH=arm64 scripts/build-kn.sh -PwithApp" >&2
	exit 1
}

"$HERE/click/stage-prebuilt.sh"

extra=()
[ "${1:-}" = "--" ] && { shift; extra=("$@"); }

# From the repository root: clickable.yaml is there because the container mounts
# only the project root, and this packaging needs kn/** and app/src/main/assets
# at the same time.
#
# PC_CLICK_BUILD=staged: what was just staged is what gets packaged, even when a
# source file is newer than it. Without this, build.sh would decide the tree is
# stale and link the whole thing again in the container -- which is a reasonable
# default there and the opposite of what this script is for.
cd "$REPO"
CLICKABLE_ENV_PC_CLICK_BUILD=staged \
	clickable build --arch arm64 --skip-review "${extra[@]+"${extra[@]}"}"

echo
echo "built:"
find "$HERE/out/click" -maxdepth 1 -name '*.click' -printf '  %p (%s bytes)\n' 2>/dev/null || true
echo
# hostname only: clickable prepends phablet@ itself, and phablet@phablet@host
# fails with a bare "Permission denied (publickey)".
echo "install it with:  clickable install --ssh <device>"
