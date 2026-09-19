#!/bin/bash
# One command for the Ubuntu Touch click of the OpenJDK port:
#
#   linux-port/click/make-click.sh                  the HotSpot click
#   linux-port/click/make-click.sh --vehicle image  the ahead-of-time one
#   linux-port/click/make-click.sh --both           one of each
#
# It stages the arm64 inputs that come from outside this repository (the build
# container only sees the repository itself), then runs clickable's cross build.
# The arch-independent halves must already exist — linux-port/build-all.sh
# produces them, and this checks rather than guesses.
#
# The two vehicles share a package name and version, so a device holds one at a
# time and installing the other is an upgrade — the app's data survives the
# swap. clickable always names its output <package>_<version>_<arch>.click, so
# --both moves each build aside before the next overwrites it.
#
# The image vehicle needs linux-port/out/image-arm64/libphotoncamera.so, which
# native-image cannot cross-compile: build it on an arm64 machine, or take the
# photoncamera-image-arm64 artifact from a CI run.
#
# Anything after -- goes to clickable (e.g. -- --verbose).
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"

vehicles=(hotspot)
extra=()
while [ $# -gt 0 ]; do
	case "$1" in
	--vehicle) vehicles=("$2"); shift 2 ;;
	--both) vehicles=(hotspot image); shift ;;
	--) shift; extra=("$@"); break ;;
	*) echo "usage: $0 [--vehicle hotspot|image] [--both] [-- CLICKABLE ARGS]" >&2; exit 2 ;;
	esac
done
for v in "${vehicles[@]}"; do
	case "$v" in hotspot|image) ;; *) echo "unknown vehicle '$v'" >&2; exit 2 ;; esac
done

# Only what the container cannot produce itself. Not shim.jar and not the JNI
# libraries: build.sh builds those inside the container against the arm64
# api-impl.jar, so a host-side one is never consumed.
for f in "$PORT_OUT/app.apk" "$PORT_OUT/classpath"; do
	[ -e "$f" ] || {
		echo "missing $f: run linux-port/build-classpath.sh first" >&2
		exit 1
	}
done

"$PORT_DIR/click/stage-prebuilt.sh"

cd "$REPO_DIR"
out="$PORT_OUT/clicks"
mkdir -p "$out"
# The vehicle reaches build.sh through a file: clickable does not forward the
# environment into its container. Left behind it would silently decide the next
# build, so it goes on the way out.
trap 'rm -f "$REPO_DIR/.vehicle"' EXIT

for v in "${vehicles[@]}"; do
	echo
	echo "== $v"
	echo "$v" >"$REPO_DIR/.vehicle"
	clickable build --arch arm64 --skip-review "${extra[@]+"${extra[@]}"}"
	click=$(ls "$PORT_OUT"/click/*.click)
	case "$v" in image) sfx=aot ;; hotspot) sfx=cds ;; esac
	mv "$click" "$out/$(basename "${click%.click}")-$sfx.click"
done

echo
echo "built:"
find "$out" -name '*.click' -printf '  %p (%s bytes)\n'
echo
# Not `clickable install`: it installs whatever clickable last built, and these
# have been renamed out of its build dir so the two vehicles can sit side by side.
echo "install one with:  scp <file>.click phablet@<device>: &&"
echo "                   ssh phablet@<device> pkcon install-local --allow-untrusted <file>.click"
