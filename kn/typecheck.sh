#!/bin/bash
# Type-check the converted app plus the hand-written shims as one klib, with
# no link.  ~40 s.  Prints the error count, the top message shapes, the top
# unresolved names and the worst files; the full log is out/typecheck-TAG.log.
#
#   kn/typecheck.sh [TAG] [extra source dirs...]
#
# Every .klib under out/klib/ is put on the library path (-l), so cinterop
# bindings built by hand (cinterop/*.def) are visible to the shims.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${1:-$$}"; shift || true
KN="${KN:-$HOME/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin/kotlinc-native}"
OUT="$HERE/out"; mkdir -p "$OUT"
LOG="$OUT/typecheck-$TAG.log"
# photoncam/host is the Compose-facing side and needs the CMP klibs only the
# Gradle build has; everything else under src/commonMain is plain Kotlin.
SRCS=$(find "$HERE/src/commonMain/kotlin" -mindepth 1 -maxdepth 1 -type d ! -name photoncam | sort)
SRCS="$SRCS $(find "$HERE/src/commonMain/kotlin/photoncam" -mindepth 1 -maxdepth 1 ! -name host | sort)"
KLIBS=(); for k in "$OUT"/klib/*.klib; do [ -e "$k" ] && KLIBS+=(-l "$k"); done
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}" "$KN" -target linux_x64 -p library -nowarn \
    -o "$OUT/typecheck-$TAG.klib" ${KLIBS[@]+"${KLIBS[@]}"} "$HERE/gen" "$HERE/gen-atlas" $SRCS "$@" >"$LOG" 2>&1
rc=$?
rm -f "$OUT/typecheck-$TAG.klib"
n=$(grep -c ': error: ' "$LOG")
# A compiler crash (a stack trace, exit 2) has no ': error: ' lines; never
# report that as a clean run.
if [ "$rc" != 0 ] && [ "$n" = 0 ]; then
    echo "COMPILER FAILED (exit $rc), no error lines; see $LOG"; tail -5 "$LOG"; exit 2
fi
echo "errors: $n  (exit $rc, log $LOG)"
[ "$n" = 0 ] && exit 0
echo "== message shapes"
grep ': error: ' "$LOG" | sed -E 's/^[^ ]+ error: //' | sed -E "s/'[^']*'/'X'/g" | sort | uniq -c | sort -rn | head -15
echo "== unresolved names"
grep ': error: unresolved reference' "$LOG" | grep -oE "'[^']+'" | sort | uniq -c | sort -rn | head -40 | paste - - - -
echo "== worst files"
grep ': error: ' "$LOG" | cut -d: -f1 | sed "s|^$HERE/||" | sort | uniq -c | sort -rn | head -15
exit 1
