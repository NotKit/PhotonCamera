#!/bin/bash
# ../ui-compose's Android <vector> drawables -> composeResources/drawable/*.svg
#
#   scripts/convert-drawables.sh
#
# Compose Multiplatform's painterResource reads a drawable through Aurora's
# components-resources, whose toXmlElement() is SVGDOM -- an SVG parser.  So an
# Android vector XML does not merely render wrong, it THROWS ("Can't wrap
# nullptr"), while an .svg of the same glyph parses and draws.  ui-compose ships
# 75 vectors and no .svg, and its Android build needs the XML, so the conversion
# lands HERE and ui-compose is not touched.
#
# The copy into a bare drawable/ is not decoration: vector-to-svg.py's out_name()
# puts the source's flavour into the output stem when "src" is on the path, and
# ui-compose's path is src/commonMain/... -- which would name every glyph
# "ic_flash_on.commonMain.svg" and no Res.drawable.ic_flash_on would resolve.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/../ui-compose/src/commonMain/composeResources/drawable"
OUT="$HERE/composeResources/drawable"

[ -d "$SRC" ] || { echo "no $SRC" >&2; exit 1; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/drawable"
cp "$SRC"/*.xml "$TMP/drawable/"

rm -rf "$OUT"; mkdir -p "$OUT"
mkdir -p "$HERE/out"
python3 "$HERE/scripts/vector-to-svg.py" "$TMP/drawable" -o "$OUT" \
	--colors "$HERE/../app/src/main/res" --report "$HERE/out/vector-to-svg.txt"
echo "== $(ls "$OUT" | wc -l) svg in $OUT"
