#!/bin/bash
# Build the whole port in dependency order: the framework, the app's jars and
# its JNI libraries, from a checkout to a runnable app.
#
#   linux-port/build-all.sh && linux-port/run.sh
#
# Each step is one of the port's own scripts, all of them idempotent, so a
# re-run is an incremental pass and a failed run can be resumed with --from.
#
# Usage: linux-port/build-all.sh [--clean] [--from STEP] [--only STEP] [--list]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

# name | what it does
# atlas comes before the natives on purpose: libncnnMl.so links atlas's
# libandroid.so.0 for the AAssetManager calls that read the models out of the apk.
STEPS=(
	"sources|the atlas checkout"
	"env|toolchain smoke test"
	"atlas|the atlas framework and the HotSpot launcher"
	"ncnn|ncnn from source (the ML nodes)"
	"natives|the app's JNI libraries, glibc"
	"classpath|app jars and app.apk from Gradle"
	"shim|the libcore/dalvik compat shim"
	"checks|the JNI libraries inside the launcher"
)

clean=""
from=""
only=""
while [ $# -gt 0 ]; do
	case "$1" in
	--clean) clean="--clean" ;;
	--from) from="${2:-}"; shift ;;
	--only) only="${2:-}"; shift ;;
	--list)
		for s in "${STEPS[@]}"; do
			IFS='|' read -r name what <<<"$s"
			printf '%-10s %s\n' "$name" "$what"
		done
		exit 0 ;;
	*) echo "usage: $0 [--clean] [--from STEP] [--only STEP] [--list]" >&2; exit 2 ;;
	esac
	shift
done

step_names=$(for s in "${STEPS[@]}"; do echo "${s%%|*}"; done)
for want in $from $only; do
	grep -qx "$want" <<<"$step_names" ||
		{ echo "unknown step: $want (see --list)" >&2; exit 2; }
done

started=$(date +%s)
declare -a TIMINGS=()

# --- the steps --------------------------------------------------------------

step_sources() {
	# atlas is a source dependency this repo does not carry. $ATLAS_DIR already
	# points at a checkout when there is one next door (env.sh); otherwise clone
	# the branch that has the camera2 bring-up.
	if [ ! -f "$ATLAS_DIR/meson.build" ]; then
		echo "no atlas checkout at $ATLAS_DIR: cloning $ATLAS_URL ($ATLAS_BRANCH)"
		git clone -b "$ATLAS_BRANCH" "$ATLAS_URL" "$ATLAS_DIR"
	fi
	# Share the ~8 GB skia tree if there is one; otherwise meson downloads it.
	if [ ! -e "$ATLAS_DIR/subprojects/skia" ] && [ -d "$ATLAS_SKIA_DIR" ]; then
		ln -s "$ATLAS_SKIA_DIR" "$ATLAS_DIR/subprojects/skia"
		echo "linked subprojects/skia -> $ATLAS_SKIA_DIR"
	fi
}

step_env()       { "$PORT_DIR/check-env.sh"; }
step_ncnn()      { "$PORT_DIR/native/build_ncnn_linux.sh" $clean; }
step_natives()   { "$PORT_DIR/native/build_natives_linux.sh" $clean; }
step_classpath() { "$PORT_DIR/build-classpath.sh" $clean; }
step_atlas()     { "$PORT_DIR/build-atlas.sh" $clean; }
step_shim()      { "$PORT_DIR/build-shim.sh" $clean; }

step_checks() {
	# The JNI libraries load inside the real launcher, off java.library.path.
	# Exercises the whole stack short of the UI.
	"$PORT_DIR/check-native-libs.sh"
}

# --- run them ---------------------------------------------------------------

skipping=1
[ -n "$from" ] || skipping=0
for s in "${STEPS[@]}"; do
	IFS='|' read -r name what <<<"$s"
	if [ -n "$only" ]; then
		[ "$name" = "$only" ] || continue
	else
		[ "$name" = "$from" ] && skipping=0
		[ "$skipping" = 0 ] || { echo "-- skip $name"; continue; }
	fi

	echo
	echo "== $name: $what"
	t0=$(date +%s)
	"step_$name"
	TIMINGS+=("$(printf '%-10s %4ds' "$name" $(( $(date +%s) - t0 )))")
done

echo
echo "build-all: $(( $(date +%s) - started ))s"
printf '  %s\n' "${TIMINGS[@]}"
echo
echo "next: linux-port/run.sh"
