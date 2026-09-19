#!/bin/bash
# Build mgwl as a STATIC library for Kotlin/Native to link into the .kexe, plus
# the two link-line stubs Aurora's klibs insist on.
#
#   scripts/build-mgwl.sh [x64|arm64]
#
# Kotlin/Native has no JNI: cinterop binds mgwl.h directly and the C goes into
# the binary, so a static archive is what is wanted.
#
# mgwl_clip is compiled although no Kotlin binds it -- mgwl.c calls
# mgwl_clip_note_serial() from every input handler.  It is core Wayland (a
# wl_data_device on the seat mgwl already binds) and adds no dependency.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${1:-x64}"
case "$ARCH" in
	x64)   CC=gcc; OUT="$HERE/build/libmgwl-x64.a"; AR=ar; LIBDIR="$HERE/hostlibs" ;;
	arm64) CC=aarch64-linux-gnu-gcc; OUT="$HERE/build/libmgwl-arm64.a"; AR=aarch64-linux-gnu-ar; LIBDIR="$HERE/armlibs" ;;
	*) echo "usage: build-mgwl.sh [x64|arm64]" >&2; exit 1 ;;
esac
command -v "$CC" >/dev/null || { echo "no $CC" >&2; exit 1; }

mkdir -p "$HERE/build/obj-$ARCH" "$LIBDIR"
CFLAGS="-O2 -fPIC -Wall -Wextra -std=gnu11 -I$HERE/host -I$HERE/host/vendor"
# sensorfw.c is here rather than in an archive of its own: it is host C like
# the rest, and cinterop/sensorfw.def binds it the same way mgwl.def does.
for f in mgwl xdg-shell-protocol mgwl_clip sensorfw; do
	# shellcheck disable=SC2086
	"$CC" $CFLAGS -c "$HERE/host/$f.c" -o "$HERE/build/obj-$ARCH/$f.o"
done
rm -f "$OUT"
"$AR" rcs "$OUT" "$HERE/build/obj-$ARCH"/*.o
echo "== $OUT"
nm --defined-only "$OUT" 2>/dev/null | grep -c ' T mgwl_' | xargs -I{} echo "   {} mgwl_* symbols"
nm --defined-only "$OUT" 2>/dev/null | grep -c ' T pc_sensorfw_' | xargs -I{} echo "   {} pc_sensorfw_* symbols"

# -lruntime-manager-qt5 is on the link line because compose-ui's klib manifest
# names ak-window and ak-uri-launcher, and THEIR cinterop klibs carry it.  Aurora's
# runtime-manager has no counterpart off Aurora; host/runtime_manager_stub.c is
# aurora-probe's stand-in for the nine symbols they reference.  Nothing calls them.
"$CC" -O2 -fPIC -c "$HERE/host/runtime_manager_stub.c" -o "$HERE/build/obj-$ARCH/rm.o"
rm -f "$LIBDIR/libruntime-manager-qt5.a"
"$AR" rcs "$LIBDIR/libruntime-manager-qt5.a" "$HERE/build/obj-$ARCH/rm.o"
echo "== $LIBDIR/libruntime-manager-qt5.a"

# The host's runtime packages ship only versioned .so names; ld wants the bare
# one.  Symlinks into /usr, never into another lane's checkout.
if [ "$ARCH" = x64 ]; then
	for l in Qt5Core.so.5 Qt5DBus.so.5 dbus-1.so.3 stdc++.so.6; do
		[ -e "/usr/lib/x86_64-linux-gnu/lib$l" ] || { echo "missing /usr/lib/x86_64-linux-gnu/lib$l" >&2; exit 1; }
		ln -sf "/usr/lib/x86_64-linux-gnu/lib$l" "$LIBDIR/lib${l%%.so.*}.so"
	done
fi
