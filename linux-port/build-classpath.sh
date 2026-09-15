#!/bin/bash
# Build the app's HotSpot classpath and the matching APK.
#
# Drives the Gradle jarForLinuxPort task, which jars AGP's javac output instead
# of dexing it, then smoke-tests the result with linux-port/tools/PortClassCheck.
#
# Usage: linux-port/build-classpath.sh [--clean]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

# The current dev branch names three classes that were never committed. An
# explicit revision lets bring-up use the last compilable app without changing
# the working tree; the native libraries still come from this checkout.
if [ -n "${PORT_APP_REV:-}" ]; then
	rev=$(git -C "$REPO_DIR" rev-parse --verify "$PORT_APP_REV^{commit}")
	source_dir="$PORT_OUT/app-source-${rev:0:12}"
	if [ ! -f "$source_dir/.port-source-rev" ]; then
		rm -rf "$source_dir"
		mkdir -p "$source_dir"
		git -C "$REPO_DIR" archive "$rev" | tar -x -C "$source_dir"
		echo "$rev" >"$source_dir/.port-source-rev"
	fi
	mkdir -p "$source_dir/linux-port/gradle" "$source_dir/linux-port/tools"
	cp "$REPO_DIR/build.gradle" "$source_dir/build.gradle"
	cp "$REPO_DIR/app/build.gradle" "$source_dir/app/build.gradle"
	cp "$PORT_DIR/env.sh" "$PORT_DIR/build-classpath.sh" "$source_dir/linux-port/"
	cp "$PORT_DIR/gradle/jar-for-linux-port.gradle" "$source_dir/linux-port/gradle/"
	cp "$PORT_DIR/tools/PortClassCheck.java" "$source_dir/linux-port/tools/"
	PORT_APP_REV= PORT_SOURCE_REV="$rev" "$source_dir/linux-port/build-classpath.sh" "$@"
	exit 0
fi

CLASSPATH_DIR="$PORT_OUT/classpath"
APK="$PORT_OUT/app.apk"

# -PlinuxPort skips the NDK build; the port has its own JNI libraries.
gradle_port() {
	# sh, not ./gradlew: the wrapper is mode 644 in this repository.
	(cd "$REPO_DIR" && sh gradlew -PlinuxPort "$@")
}

# clean gets its own invocation: Gradle does not order it against the tasks that
# repopulate the same build dir.
if [ "${1:-}" = "--clean" ]; then
	rm -rf "$CLASSPATH_DIR" "$APK"
	gradle_port :app:clean :circularbarlib:clean
fi

gradle_port :app:jarForLinuxPort

# --- verification -----------------------------------------------------------

for jar in photoncamera-app.jar photoncamera-res.jar circularbarlib.jar; do
	[ -s "$CLASSPATH_DIR/$jar" ] || { echo "missing $CLASSPATH_DIR/$jar" >&2; exit 1; }
done

jar_count=$(find "$CLASSPATH_DIR" -maxdepth 1 -name '*.jar' | wc -l)
[ "$jar_count" -ge 30 ] || { echo "only $jar_count jars in $CLASSPATH_DIR" >&2; exit 1; }

[ -s "$APK" ] || { echo "missing $APK" >&2; exit 1; }
apk_listing=$(unzip -l "$APK")
# The pipeline reads its shaders and its ML models out of the apk through
# AssetManager, so an apk without them boots and then fails at the first capture.
for entry in AndroidManifest.xml resources.arsc assets/shaders/ assets/models/; do
	grep -q "$entry" <<<"$apk_listing" || { echo "$APK has no $entry" >&2; exit 1; }
done

# The app's own JNI libraries come from linux-port/native/, never from the apk.
# One of them in there means the NDK build ran despite -PlinuxPort, which also
# dirties app/src/main/cpp/deps/. Libraries the AARs bring (libarchive-jni.so)
# are expected and stay.
app_libs=$(grep -oE 'lib/[^/]+/lib(dngCreator|allocator|flacRecorder|camera2native|ncnnMl)\.so' \
	<<<"$apk_listing" || true)
if [ -n "$app_libs" ]; then
	echo "$APK carries the app's own JNI libraries; the NDK build ran under -PlinuxPort:" >&2
	echo "$app_libs" >&2
	exit 1
fi

# Smoke test: the classes the launcher and the checks name must resolve off the
# classpath alone. Static initializers stay off (they reach into android.* / JNI),
# except for the R class, where a zero id would mean we picked up a compile-time
# stub instead of the linked resource table. Classes that extend android.*
# directly can only resolve once build-atlas.sh adds the atlas framework jar.
CHECK_DIR="$PORT_OUT/tools"
mkdir -p "$CHECK_DIR"
"$JAVA_HOME/bin/javac" -d "$CHECK_DIR" "$PORT_DIR/tools/PortClassCheck.java"

"$JAVA_HOME/bin/java" -cp "$CHECK_DIR:$CLASSPATH_DIR/*" PortClassCheck \
	com.particlesdevs.photoncamera.processing.opengl.GLProg \
	com.particlesdevs.photoncamera.processing.render.Parameters \
	com.particlesdevs.photoncamera.processing.DngCreator \
	com.particlesdevs.photoncamera.api.NativeEngine \
	'com.particlesdevs.photoncamera.R$string#app_name' \
	--needs-android \
	com.particlesdevs.photoncamera.app.PhotonCamera \
	com.particlesdevs.photoncamera.ui.SplashActivity \
	com.particlesdevs.photoncamera.ui.camera.CameraActivity \
	com.particlesdevs.photoncamera.capture.CaptureController

echo "classpath ready: $jar_count jars in $CLASSPATH_DIR, apk at $APK"
echo "${PORT_SOURCE_REV:-$(git -C "$REPO_DIR" rev-parse HEAD)}" >"$PORT_OUT/app-source-rev"
