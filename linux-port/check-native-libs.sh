#!/bin/bash
# Checks that the app's JNI libraries load inside the launcher.
#
# Compiles tools/NativeLibCheck.java against the port class path and runs it
# through the launcher (--run-class), with --library-path pointing at
# $PORT_LIB_OUT. A pass means System.loadLibrary resolved every library the app's
# static initializers ask for and JNI bound the entry points by name.
#
# Writes out/run.log. Usage: linux-port/check-native-libs.sh
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$PORT_DIR/launcher/display.sh"

launcher="$PORT_LAUNCHER_BIN"
tools_out="$PORT_OUT/tools"
log="$PORT_OUT/run.log"

[ -x "$launcher" ] || { echo "no launcher at $launcher (run build-atlas.sh)" >&2; exit 1; }
[ -f "$ATLAS_OUT/artifacts.env" ] || { echo "no $ATLAS_OUT/artifacts.env (run build-atlas.sh)" >&2; exit 1; }
[ -f "$PORT_OUT/shim.jar" ] || { echo "no $PORT_OUT/shim.jar (run build-shim.sh)" >&2; exit 1; }
[ -f "$PORT_OUT/app.apk" ] || { echo "no $PORT_OUT/app.apk (run build-classpath.sh)" >&2; exit 1; }
[ -f "$PORT_LIB_OUT/libdngCreator.so" ] ||
	{ echo "no $PORT_LIB_OUT/libdngCreator.so (run native/build_natives_linux.sh)" >&2; exit 1; }
source "$ATLAS_OUT/artifacts.env"

class_path="$ATLAS_API_IMPL_JAR:$PORT_OUT/shim.jar:$PORT_OUT/classpath/*"

echo "compiling NativeLibCheck"
mkdir -p "$tools_out"
"$JAVA_HOME/bin/javac" -nowarn -d "$tools_out" -cp "$class_path" "$PORT_DIR/tools/NativeLibCheck.java"

export LD_LIBRARY_PATH="$JAVA_HOME/lib/server:$ATLAS_OUT${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# a fresh data dir, so nothing left by an earlier run can be what made this pass
export ANDROID_APP_DATA_DIR="$PORT_OUT/native-libs-data"
rm -rf "$ANDROID_APP_DATA_DIR"
mkdir -p "$ANDROID_APP_DATA_DIR"

port_start_display

echo "running the check in the launcher, log in $log"
status=0
timeout 120 "$launcher" \
	--api-impl-jar "$ATLAS_API_IMPL_JAR" \
	--framework-res "$ATLAS_FRAMEWORK_RES" \
	--natives-dir "$ATLAS_NATIVES_DIR" \
	--classpath "$PORT_OUT/shim.jar:$PORT_OUT/classpath/*:$tools_out" \
	--library-path "$PORT_LIB_OUT" \
	-X "-XX:ErrorFile=$PORT_OUT/hs_err_pid%p.log" \
	${PORT_EXTRA_JVM_ARGV[@]+"${PORT_EXTRA_JVM_ARGV[@]}"} \
	--run-class NativeLibCheck \
	"$PORT_OUT/app.apk" >"$log" 2>&1 || status=$?

# --- verification -----------------------------------------------------------

log_text=$(cat "$log")

[ "$status" = 0 ] || { echo "the launcher exited $status:" >&2; tail -40 "$log" >&2; exit 1; }

for expected in "loaded libdngCreator.so" "loaded liballocator.so" \
                "loaded libflacRecorder.so" "loaded libcamera2native.so" \
                "loaded libncnnMl.so" \
                "Allocator round trip ok" "native lib check: passed"; do
	grep -qF "$expected" <<<"$log_text" || { echo "run.log has no '$expected'" >&2; exit 1; }
done

if grep -qF "A fatal error has been detected" <<<"$log_text"; then
	echo "the JVM crashed:" >&2
	grep -A 10 "A fatal error has been detected" <<<"$log_text" >&2
	exit 1
fi

echo "native lib check passed: the app's JNI libraries load from $PORT_LIB_OUT"
