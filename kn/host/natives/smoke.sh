#!/bin/bash
# Link smoke.c against libphotoncam_native.a and run it.  See smoke.c.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(cd "$HERE/../.." && pwd)/out/natives/${PORT_ARCH:-x86_64}"
# A real PNG from the app's own resources, so the codec is exercised on the
# bytes it will actually meet.
PNG="${SMOKE_PNG:-$(cd "$HERE/../../.." && pwd)/app/src/main/res/drawable/neutral_lut.png}"
[ -f "$PNG" ] || { echo "no PNG at $PNG" >&2; exit 1; }
clang -O1 -I"$HERE" -DSMOKE_PNG="\"$PNG\"" -DSMOKE_JPEG="\"$OUT/smoke-out.jpg\"" \
	-o "$OUT/smoke" "$HERE/smoke.c" $(cat "$OUT/link-flags.txt")
PHOTONCAMERA_LOG_LEVEL=6 "$OUT/smoke"
