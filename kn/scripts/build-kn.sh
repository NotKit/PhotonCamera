#!/bin/bash
# Build the Kotlin/Native binary.
#
#   ARCH=x64   scripts/build-kn.sh [gradle args...]     the desktop
#   ARCH=arm64 scripts/build-kn.sh [gradle args...]     the phone
#
# ONE ARCH PER GRADLE RUN.  Building the pair in a single invocation OOMs on this
# box -- konan keeps a whole LLVM module per target off-heap -- so there is no
# ARCH=both here, on purpose.
#
# Three things have to exist before Gradle starts: the SVG drawables (CMP cannot
# read an Android <vector>), the mgwl static archive the linker wants on its -L
# path, and the cinterop headers under host/.
#
# -PwithApp adds the converted app (gen/, gen-atlas/ and the other lanes' shims)
# and the gles/natives/atlcamera cinterops; it is off by default and will not
# link until the type-check reaches 0.
#
# --clean-cinterop drops the cinterop klibs first.  IT IS NOT OPTIONAL after a
# .def or a host/*.h has changed: Gradle does not look inside either, so the
# stale klib is used and the new symbol is simply "unresolved", with nothing in
# the message to say why.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
if [ "${1:-}" = "--clean-cinterop" ]; then
	shift
	rm -rf "$HERE"/build/classes/kotlin/*/main/cinterop "$HERE"/build/klib
	echo "== dropped the cinterop klibs"
fi
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
GRADLE="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle | head -1)"
ARCH="${ARCH:-x64}"
cd "$HERE"

"$HERE/scripts/convert-drawables.sh"

case "$ARCH" in
	x64)   "$HERE/scripts/build-mgwl.sh" x64
	       TASK=linkDebugExecutableLinuxX64 ;;
	arm64) "$HERE/scripts/build-mgwl.sh" arm64
	       "$HERE/scripts/fetch-arm64-extra.sh"
	       TASK=linkReleaseExecutableLinuxArm64 ;;
	*) echo "ARCH must be x64 or arm64" >&2; exit 1 ;;
esac

# Five lane agents share this box and Gradle is not safe against itself on one
# project directory.  The lock is the whole of the coordination.
flock -w 1800 /tmp/photoncam-kn-gradle.lock \
	"$GRADLE" --no-daemon "$TASK" "$@"
ls -la "$HERE"/build/bin/*/*/*.kexe 2>/dev/null
