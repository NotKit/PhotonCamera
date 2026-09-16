#!/bin/bash
# The one arm64 library aurora-probe's sysroot does not carry.
#
#   scripts/fetch-arm64-extra.sh
#
# mgwl links -lxkbcommon (keyboard events).  aurora-probe's sysroot-arm64 was
# assembled for Aurora's own link line, which has no xkbcommon in it, so the
# library is simply absent -- and that sysroot belongs to another lane and is not
# edited from here.  This puts the missing .so in a directory of ours that goes
# on the linker's -L path after it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/armlibs-extra"
mkdir -p "$OUT"
[ -e "$OUT/libxkbcommon.so" ] && { echo "already there: $OUT"; exit 0; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
for suite in noble noble-updates; do
	curl -sS "http://ports.ubuntu.com/ubuntu-ports/dists/$suite/main/binary-arm64/Packages.gz" \
		-o "$TMP/$suite.gz"
done
zcat "$TMP"/*.gz > "$TMP/all.txt"
python3 - "$TMP" <<'PY'
import re, sys
tmp = sys.argv[1]
want = {"libxkbcommon0"}
best = {}
for b in open(f"{tmp}/all.txt").read().split("\n\n"):
	m = re.search(r"^Package: (\S+)$", b, re.M)
	if m and m.group(1) in want:
		best[m.group(1)] = re.search(r"^Filename: (\S+)$", b, re.M).group(1)
missing = want - set(best)
if missing:
	raise SystemExit(f"not found in the index: {sorted(missing)}")
open(f"{tmp}/urls.txt", "w").write(
	"".join("http://ports.ubuntu.com/ubuntu-ports/" + f + "\n" for f in sorted(best.values())))
PY
mkdir -p "$TMP/debs" && (cd "$TMP/debs" && xargs -n1 -P4 curl -sS -O < "$TMP/urls.txt")
for d in "$TMP"/debs/*.deb; do dpkg-deb -x "$d" "$TMP/x"; done
cp -a "$TMP"/x/usr/lib/aarch64-linux-gnu/*.so.* "$OUT/"
# The runtime debs ship only libFOO.so.N; ld wants the bare name.
(cd "$OUT" && for f in *.so.*; do b="${f%%.so.*}"; [ -e "$b.so" ] || ln -sf "$f" "$b.so"; done)
ls -la "$OUT"
