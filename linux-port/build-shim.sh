#!/bin/bash
# Build the libcore/dalvik compat shim into out/shim.jar and out/lib/.
#
# The shim holds classes ART's boot classpath has and the JDK lacks:
# libcore.*, dalvik.*, android.system.*, android.icu.*, org.xmlpull.*,
# org.kxml2.*, org.json.*. It compiles against the JDK alone — never against api-impl.jar —
# and it is the last resort: a framework gap belongs in $ATLAS_DIR as a commit
# on the atlas branch, not here (shim/README.md).
#
# Usage: linux-port/build-shim.sh [--clean]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

classes="$PORT_OUT/shim-classes"
jar="$PORT_OUT/shim.jar"
native_lib="$PORT_LIB_OUT/libportshim.so"

[ "${1:-}" != "--clean" ] || rm -rf "$classes" "$jar" "$native_lib"
rm -rf "$classes"
mkdir -p "$classes"

mapfile -t sources < <(find "$PORT_DIR/shim/src" -name '*.java' | sort)

if [ "${#sources[@]}" -gt 0 ]; then
	# -bootclasspath is deliberately absent: these classes must compile against
	# the JDK the port runs on, so that a class the JDK already has fails loudly
	# here instead of shadowing it at runtime.
	"$JAVA_HOME/bin/javac" -nowarn -d "$classes" "${sources[@]}"
	echo "shim: ${#sources[@]} sources"
else
	# An empty jar is still the right product: the launcher's class path names it
	# unconditionally, and a missing file there is a silent class path hole.
	echo "shim: no sources yet (see shim/README.md)"
fi

# Resources (service registrations such as META-INF/services/...) ship as-is.
if [ -d "$PORT_DIR/shim/resources" ]; then
	cp -r "$PORT_DIR/shim/resources/." "$classes/"
fi

"$JAVA_HOME/bin/jar" --create --file "$jar" -C "$classes" .

"$PORT_CC" -shared -fPIC -O2 -Wall -Wextra -std=gnu11 \
	-I"$PORT_TARGET_JAVA_HOME/include" -I"$PORT_TARGET_JAVA_HOME/include/linux" \
	-o "$native_lib" "$PORT_DIR/shim/native/port_shim.c"

# --- verification -----------------------------------------------------------

# Nothing can be shimmed into java.*: the JDK's own boot class loader wins, so a
# class there would be silently ignored at runtime.
jar_listing=$(unzip -l "$jar")
if grep -qE ' java/' <<<"$jar_listing"; then
	echo "$jar defines classes under java/, which the boot class loader shadows" >&2
	exit 1
fi

native_symbols=$("$PORT_NM" -D --defined-only "$native_lib")
for symbol in Java_libcore_util_NativeAllocationRegistry_applyFreeFunction Java_libcore_io_Posix_gettid; do
	grep -q " $symbol\$" <<<"$native_symbols" || {
		echo "$native_lib does not export $symbol" >&2; exit 1; }
done

if [ "$PORT_CROSS" = 0 ]; then
	mkdir -p "$PORT_OUT/tools"
	"$JAVA_HOME/bin/javac" -d "$PORT_OUT/tools" -cp "$jar" "$PORT_DIR/tools/ShimCheck.java"
	"$JAVA_HOME/bin/java" -Djava.library.path="$PORT_LIB_OUT" -cp "$PORT_OUT/tools:$jar" ShimCheck
fi

echo "shim ready: $jar ($(grep -cE '\.class$' <<<"$jar_listing" || true) classes), $native_lib"
