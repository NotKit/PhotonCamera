#!/bin/bash
# atl-touch's android.hardware.camera2 (and the small types around it) ->
# kn/gen-atlas/.  Source of truth is atl-touch's src/api-impl, which is never
# edited: a file that converts badly is either post-sed'd here or replaced by
# hand under src/commonMain/kotlin and listed in SKIP below.
#
#   kn/convert-atlas.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# WHERE atl-touch is, in the order settings.gradle.kts resolves its own inputs:
# an explicit answer, then what scripts/fetch-deps.sh clones, then this box's
# worktree.  github.com/NotKit/atl-touch master carries this tree, so a clone
# is enough -- nothing here needs a private checkout.
resolve_atlas() {
	local d
	for d in "${ATLAS:-}" "${PC_ATL_TOUCH:+$PC_ATL_TOUCH/src/api-impl}" \
		"$HERE/deps/atl-touch/src/api-impl" "$HOME/UT/atlas-camera2/src/api-impl"; do
		[ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return 0; }
	done
	return 0
}
ATLAS="$(resolve_atlas)"
[ -n "$ATLAS" ] || {
	echo "convert-atlas: no atl-touch checkout; scripts/fetch-deps.sh clones one" >&2
	exit 1
}
PY="${PYTHON:-$HOME/UT/kn-toolchain/venv/bin/python}"
STAGE="$HERE/out/atlas-src"

# The api-impl files the camera lane owns.  Anything else android.* the app
# names belongs to another lane and is not staged from here.
FILES="
android/hardware/camera2
android/hardware/Sensor.java
android/hardware/SensorEvent.java
android/hardware/SensorEventListener.java
android/hardware/SensorManager.java
android/util/Range.java
android/util/Rational.java
android/util/Size.java
android/util/SizeF.java
android/util/Pair.java
"
# Replaced by hand under src/commonMain/kotlin: their natives are the JNI
# layer's object plumbing (an ImageReader queue, a SurfaceTexture), which this
# port keeps in Kotlin instead.  android.view.Surface, android.media.Image*,
# android.graphics.SurfaceTexture are hand-written for the same reason and are
# not staged at all.
SKIP="
android/hardware/camera2/impl/CameraMetadataNative.java
android/hardware/camera2/impl/CameraDeviceNative.java
"

rm -rf "$STAGE" "$HERE/gen-atlas"
mkdir -p "$STAGE"
for f in $FILES; do
	mkdir -p "$STAGE/$(dirname "$f")"
	cp -r "$ATLAS/$f" "$STAGE/$f"
done
for f in $SKIP; do rm -f "$STAGE/$f"; done
echo "atlas files staged: $(find "$STAGE" -name '*.java' | wc -l)"

RULES=$(ls "$HERE"/j2k/rules/*.py | xargs -n1 basename | sed 's/\.py$//' \
        | grep -v '^__init__$' | grep -vE '^r99[58]_' | paste -sd,)
"$PY" "$HERE/j2k/j2k.py" --src "$STAGE" --out "$HERE/gen-atlas" --rules "$RULES" | tail -6

# j2k's own generated surface (aidl stand-ins, android.R, JDK stubs, the
# java-idiom shim) already comes from convert.sh's gen/; a second copy would
# be a duplicate declaration.
rm -rf "$HERE/gen-atlas/org" "$HERE/gen-atlas/stubs" "$HERE/gen-atlas/photoncam" \
       "$HERE/gen-atlas/android/R.kt"
bash "$HERE/atlas-fixups.sh" "$HERE/gen-atlas"
echo "gen-atlas/: $(find "$HERE/gen-atlas" -name '*.kt' | wc -l) files, $(cat $(find "$HERE/gen-atlas" -name '*.kt') | wc -l) lines, $(grep -rho 'TODO()' "$HERE/gen-atlas" | wc -l) TODO()"
