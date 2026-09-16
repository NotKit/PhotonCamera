#!/bin/bash
# Java -> Kotlin for the Kotlin/Native lane, through the j2k.py copied from
# firefox-atl/kn-app.  kn/gen/ is OUTPUT and is erased on every run: never edit
# it.  A construct that converts wrongly is fixed in the Java under
# app/src/main/java, so the Android build keeps working and the next run is
# right too.  Adding a rule under j2k/rules/ is the last resort.
#
#   kn/convert.sh            app/src/main/java minus drop.txt -> kn/gen/
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PY="${PYTHON:-$HOME/UT/kn-toolchain/venv/bin/python}"
SRC="$ROOT/app/src/main/java"
STAGE="$HERE/out/java-src"

# gen/ is replaced by one rename at the end, so a typecheck.sh running in
# another lane never sees a half-written tree.
GEN_NEW="$HERE/out/gen.new"
rm -rf "$STAGE" "$GEN_NEW"
mkdir -p "$STAGE"
# The app and the circularbarlib module (manual-mode models; its knob Views
# are in drop.txt) share one staging tree; their packages do not overlap.
LIB="$ROOT/circularbarlib/src/main/java"
for d in "$SRC" "$LIB"; do
    rsync -a --exclude-from="$HERE/drop.txt" \
          --include='*/' --include='*.java' --exclude='*' "$d/" "$STAGE/"
done
echo "java files staged: $(find "$STAGE" -name '*.java' | wc -l) (dropped: $(( $(find "$SRC" "$LIB" -name '*.java' | wc -l) - $(find "$STAGE" -name '*.java' | wc -l) )))"

# Every rule except the two "fiat" ones, which instrument lazy initialisers
# with a run-time logger (atl.fiat.*) that was GeckoView bring-up machinery.
RULES=$(ls "$HERE"/j2k/rules/*.py | xargs -n1 basename | sed 's/\.py$//' \
        | grep -v '^__init__$' | grep -vE '^r99[58]_' | paste -sd,)
"$PY" "$HERE/j2k/j2k.py" --src "$STAGE" --out "$GEN_NEW" --rules "$RULES" | tail -8

# j2k emits GeckoView's generated surface (aidl stand-ins, android.R, the JDK
# TODO() stubs) for any --src.  The shims under src/commonMain are real
# implementations, so the stub surface is not wanted; only the converted
# app and the java-idiom shim stay.
rm -rf "$GEN_NEW/org" "$GEN_NEW/stubs" "$GEN_NEW/android"
rm -rf "$HERE/gen.old"; [ -d "$HERE/gen" ] && mv "$HERE/gen" "$HERE/gen.old"
mv "$GEN_NEW" "$HERE/gen"; rm -rf "$HERE/gen.old"
echo "gen/: $(find "$HERE/gen" -name '*.kt' | wc -l) files, $(cat $(find "$HERE/gen" -name '*.kt') | wc -l) lines, $(grep -rho 'TODO()' "$HERE/gen" | wc -l) TODO()"
