#!/bin/bash
# Build the GraalVM native-image shared library the AOT vehicle runs on: the
# same framework, shim and class path the HotSpot vehicle loads, compiled ahead
# of time and exporting the JNI Invocation API so atlas's
# android-translation-layer-image can create its VM from it.
#
#   linux-port/build-image.sh --stub   a one-class image: the launcher's image
#                                      backend and none of PhotonCamera (~30 s)
#   linux-port/build-image.sh          the real image
#   linux-port/build-image.sh --clean  delete this build's own products first
#
# The image is a *shared library*, never an executable: the launcher stays the
# executable because libtranslation_layer_main.so imports apk_path, atl_window,
# get_app_data_dir and the bionic_* calls from it.
#
# native-image cannot cross-compile. The image is always for the machine that
# builds it, and every input here (jars, JSON) is architecture-neutral -- so the
# arm64 image is this script on an arm64 machine, which is what the `image` job
# in .github/workflows/linux-port-click.yml rents from GitHub. Do not try to
# fake it: qemu-user runs the builder 38x slower and Houdini does not finish at
# all (~/UT/firefox-atl/jvm-run/image/NOTES.md has the numbers).
#
# Built by $GRAALVM_HOME, not by $JAVA_HOME: two JDKs on purpose, and atlas, the
# shim and the app must keep being compiled by the latter.
#
#   PORT_IMAGE_XMX           builder heap, default 10g
#   PORT_IMAGE_PARALLELISM   builder threads, default nproc
#   PORT_IMAGE_INIT_AT_RUNTIME  extra --initialize-at-run-time classes. Every
#                            entry must answer an observed error or report line,
#                            never a guess, and be named in BRINGUP_NOTES.md.
#   PORT_IMAGE_EXTRA_ARGS    extra native-image arguments for one debugging build
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

mode="full"
clean=0
for arg in "$@"; do
	case "$arg" in
	--stub) mode="stub" ;;
	--clean) clean=1 ;;
	*) echo "usage: $0 [--stub] [--clean]" >&2; exit 2 ;;
	esac
done

[ "$PORT_CROSS" = 0 ] || {
	echo "build-image.sh cannot cross-compile: run it on the target architecture" >&2
	exit 1
}

native_image="$GRAALVM_HOME/bin/native-image"
[ -x "$native_image" ] || {
	echo "no native-image at $native_image" >&2
	echo "set GRAALVM_HOME to a GraalVM CE 21 for $(uname -m)" >&2
	exit 1
}
# A GraalVM for the wrong architecture runs (its launcher is a shell script) and
# then produces an image for its own, silently.
graal_arch=$(file -b "$GRAALVM_HOME/lib/server/libjvm.so" | grep -oE 'x86-64|aarch64' | head -1)
[ "$graal_arch" != x86-64 ] || graal_arch=x86_64
[ "$graal_arch" = "$(uname -m)" ] || {
	echo "GRAALVM_HOME is a $graal_arch build, this machine is $(uname -m)" >&2
	exit 1
}

# --- shared helpers ---------------------------------------------------------

# The launcher dlopens the .so and looks these three up by name (vm_image.c), so
# an image missing one is useless however well it built. --no-fallback is what
# makes the check mean anything: a fallback image exports them and still needs
# a JVM at run time.
assert_jni_invocation_api() {
	local so="$1" exports
	[ -f "$so" ] || { echo "native-image produced no $so" >&2; exit 1; }
	exports=$("$PORT_NM" -D --defined-only "$so")
	for sym in JNI_CreateJavaVM JNI_GetCreatedJavaVMs JNI_GetDefaultJavaVMInitArgs; do
		grep -qE " T $sym\$" <<<"$exports" ||
			{ echo "$so does not export $sym" >&2; exit 1; }
	done
}

# native-image is a launcher that forks the builder JVM, so the pid we start is
# never the interesting one. Sum of RSS over the tree, in kB.
tree_rss_kb() {
	local pids=("$1") next=() total=0 kb children
	while [ "${#pids[@]}" -gt 0 ]; do
		next=()
		for p in "${pids[@]}"; do
			kb=$(ps -o rss= -p "$p" 2>/dev/null || true)
			[ -z "$kb" ] || total=$((total + kb))
			mapfile -t children < <(pgrep -P "$p" 2>/dev/null || true)
			next+=(${children[@]+"${children[@]}"})
		done
		pids=(${next[@]+"${next[@]}"})
	done
	echo "$total"
}

# Wall clock and peak RSS are reported numbers, not decoration: whether a
# machine can host this build at all is decided by them. Writes $peak_rss_kb
# and $elapsed_s.
run_native_image() {
	local log="$1"; shift
	# The argv is evidence: a build argument leaves no trace in the .so and
	# native-image echoes only some of it back. --add-opens in particular is
	# invisible afterwards, and it is the difference between atlas reading
	# FileDescriptor.fd and a null field at run time.
	printf '%q\n' "$@" >"${log%.log}-argv.txt"
	# a rejected build's log is the only record of it
	[ ! -f "$log" ] || mv -f "$log" "$log.prev"

	local started peak=0 now status=0
	started=$(date +%s)
	"$@" >"$log" 2>&1 &
	local ni_pid=$!
	while kill -0 "$ni_pid" 2>/dev/null; do
		now=$(tree_rss_kb "$ni_pid")
		[ "$now" -le "$peak" ] || peak="$now"
		sleep 2
	done
	wait "$ni_pid" || status=$?
	peak_rss_kb="$peak"
	elapsed_s=$(( $(date +%s) - started ))
	return "$status"
}

# --clean removes this build's products one by one and never the directory:
# $PORT_IMAGE_LIB is an override by design, so its dirname can be $PORT_OUT
# itself -- where the classpath jars and the apk live.
clean_image_products() {
	local dir="$1" name="$2"
	rm -rf "$dir/reports"
	rm -f "$dir/$name.so" "$dir/build.log" "$dir/build.log.prev" \
		"$dir/build-argv.txt" "$dir/build-stats.env" "$dir/.build-started" \
		"$dir/generated-reflect-config.json" "$dir/generated-jni-config.json" \
		"$dir/graal_isolate.h" "$dir/graal_isolate_dynamic.h"
}

# --- the stub image ---------------------------------------------------------

build_stub() {
	local dir="$PORT_OUT/image-stub"
	# deliberately not libphotoncamera.so: a stub must never be mistaken for the
	# real image, by a person or by $PORT_IMAGE_LIB
	local name="libatl-vm-stub"

	[ "$clean" = 0 ] || rm -rf "$dir"
	rm -rf "$dir/classes"; mkdir -p "$dir/classes"
	"$JAVA_HOME/bin/javac" -d "$dir/classes" "$PORT_DIR/tools/VmCheck.java"

	# A hand-written fixture, not metadata: it says which classes the *launcher*
	# looks up by name. VmCheck because --vm-check calls its main(),
	# System.getProperty because the launcher reads the properties back through
	# it, String because that is main's argument element type.
	cat >"$dir/jni-config.json" <<'EOF'
[
  { "name": "VmCheck", "methods": [{ "name": "main", "parameterTypes": ["java.lang.String[]"] }] },
  { "name": "java.lang.System", "methods": [{ "name": "getProperty", "parameterTypes": ["java.lang.String"] }] },
  { "name": "java.lang.String" }
]
EOF

	echo "building the stub image with $("$native_image" --version | head -1)"
	run_native_image "$dir/build.log" \
		"$native_image" --shared -o "$dir/$name" -cp "$dir/classes" \
		-H:JNIConfigurationFiles="$dir/jni-config.json" --no-fallback \
		--add-opens=java.base/java.io=ALL-UNNAMED || {
			tail -40 "$dir/build.log" >&2; exit 1; }
	assert_jni_invocation_api "$dir/$name.so"

	echo "stub image built in ${elapsed_s}s, peak RSS $((peak_rss_kb / 1024)) MB"
	echo "  $dir/$name.so ($(stat -c%s "$dir/$name.so") bytes), exports the JNI Invocation API"
	echo "  run it with: $PORT_IMAGE_LAUNCHER_BIN --vm-library $dir/$name.so --vm-check VmCheck ..."
}

# --- the real image ---------------------------------------------------------

# The class path, in the order the HotSpot launcher assembles it: the framework
# first, then the shim, then the app jars. Order decides which jar wins a
# duplicate class, and api-impl has to win. The jars are listed one by one
# rather than as "dir/*" -- that is the `java` launcher's expansion, and
# native-image does not do it; a literal "*" entry would contribute nothing and
# the image would come out missing the whole app, silently.
image_class_path() {
	local jars=()
	mapfile -t jars < <(find "$PORT_OUT/classpath" -maxdepth 1 -name '*.jar' | sort)
	[ "${#jars[@]}" -ge 30 ] ||
		{ echo "only ${#jars[@]} jars in $PORT_OUT/classpath (run build-classpath.sh)" >&2; exit 1; }
	local IFS=":"
	echo "$ATLAS_API_IMPL_JAR:$PORT_OUT/shim.jar:${jars[*]}"
}

# The two classes whose *build-time* initialisation would be wrong rather than
# merely different: both read a -D property only the running process can answer,
# and SDK_INT is a static final int, so a build-time value is constant-folded
# into every "SDK_INT >= N" branch in the app and no run-time -D can undo it.
# PhotonCamera's minSdk is 26 and it branches on SDK_INT throughout the capture
# path, so this is the gate that matters most here.
init_hazards=(
	"android.os.Build\$VERSION"
	"android.os.SystemProperties"
)

# -H:+PrintClassInitialization writes reports/class_initialization_report_*.csv:
# "Class Name, Initialization Kind, Reason". It is the only machine-readable
# answer to "did Build$VERSION end up initialised at build time", and nothing in
# the metadata can warn about it.
check_class_initialization() {
	local dir="$1" stamp="$2"
	local reports=()
	mapfile -t reports < <(find "$dir/reports" -name 'class_initialization_report_*.csv' \
		-newer "$stamp" 2>/dev/null | sort)
	[ "${#reports[@]}" != 0 ] || {
		echo "no class-initialization report under $dir/reports" >&2; return 1; }
	local report="${reports[-1]}" report_text failed=0 line nested
	report_text=$(cat "$report")

	for class in "${init_hazards[@]}"; do
		# Exact match on the first column: the Reason column names classes too,
		# so a substring match could read another class's row as this verdict.
		line=$(awk -F', ' -v c="$class" '$1 == c { print; exit }' <<<"$report_text")
		# --initialize-at-run-time moves exactly the class it names; nested
		# classes keep whatever kind the heuristic gave them. Reported, not
		# gated: naming one needs an observed report line like any other entry.
		nested=$(awk -F', ' -v p="$class\$" \
			'index($1, p) == 1 && $2 != "RUN_TIME" && $2 != "RERUN"' \
			<<<"$report_text" | wc -l)
		[ "$nested" = 0 ] ||
			echo "class init: $nested nested classes of $class are build-time"

		case "$line" in
		"") echo "class init: $class not in the report (unreachable in this image)" ;;
		*", RUN_TIME,"*|*", RERUN,"*) echo "class init: $line" ;;
		*)
			echo "class init: $line" >&2
			echo "  -> $class must not be initialised at build time: it answers for" >&2
			echo "     the running process, and SDK_INT is constant-folded if it does not." >&2
			failed=1 ;;
		esac
	done

	# AssetManager is BUILD_TIME by native-image's own "proven side-effect free"
	# heuristic, and that survives while its static state stays empty: what fails
	# a build is a live JarFile reaching the image heap. Reported, not gated.
	line=$(awk -F', ' '$1 == "android.content.res.AssetManager" { print; exit }' <<<"$report_text")
	[ -z "$line" ] || echo "class init: $line"

	echo "class-initialization report: $report"
	return "$failed"
}

build_full() {
	local dir name log stamp
	dir="$(dirname "$PORT_IMAGE_LIB")"
	# native-image appends .so to -o under --shared, so -o takes the basename
	name="$(basename "$PORT_IMAGE_LIB" .so)"
	log="$dir/build.log"
	stamp="$dir/.build-started"

	mkdir -p "$dir"
	[ "$clean" = 0 ] || clean_image_products "$dir" "$name"

	[ -f "$ATLAS_OUT/artifacts.env" ] ||
		{ echo "no $ATLAS_OUT/artifacts.env (run build-atlas.sh)" >&2; exit 1; }
	source "$ATLAS_OUT/artifacts.env"
	[ -f "$ATLAS_API_IMPL_JAR" ] ||
		{ echo "no $ATLAS_API_IMPL_JAR (run build-atlas.sh)" >&2; exit 1; }
	[ -f "$PORT_OUT/shim.jar" ] ||
		{ echo "no $PORT_OUT/shim.jar (run build-shim.sh)" >&2; exit 1; }

	# A -cp entry that does not exist is *not* an error to native-image: it warns
	# nothing, builds an image without those classes and exits 0. So every input
	# is checked here rather than left to the build to notice.
	for file in jni-config.json reflect-config.json proxy-config.json \
		resource-config.json serialization-config.json; do
		[ -f "$PORT_NI_CONFIG/$file" ] || {
			echo "no $PORT_NI_CONFIG/$file" >&2
			echo "run linux-port/trace-metadata.sh once and commit what it writes;" >&2
			echo "an image without the traced metadata cannot boot the framework." >&2
			exit 1
		}
	done

	local class_path
	class_path=$(image_class_path)
	# Split once, here: every later consumer wants the entries, and
	# ${class_path//:/ } would break on the first path with a space in it.
	local cp_entries=()
	IFS=':' read -ra cp_entries <<<"$class_path"

	# The classes nothing in the bytecode names: every View the layout inflater
	# builds, every Fragment, every ViewModel, and every name atlas's natives and
	# the app's own JNI libraries call FindClass with. Generated rather than
	# committed -- a dependency bump moves the set.
	local natives=()
	mapfile -t natives < <(find "$ATLAS_OUT" "$PORT_LIB_OUT" -maxdepth 1 \
		\( -name '*.so' -o -name '*.so.0' \) 2>/dev/null | sort)
	[ "${#natives[@]}" -gt 0 ] ||
		{ echo "no native libraries to scan for FindClass names" >&2; exit 1; }
	local gen_reflect="$dir/generated-reflect-config.json"
	local gen_jni="$dir/generated-jni-config.json"
	python3 "$PORT_DIR/image/gen-reflect-config.py" "$gen_reflect" "$gen_jni" \
		"$(IFS=,; echo "${natives[*]}")" "${cp_entries[@]}"

	# Every entry answers an observed report line or an observed build error,
	# never a guess, and is named in BRINGUP_NOTES.md.
	local init_rt="android.os.Build\$VERSION,android.os.SystemProperties"
	[ -z "${PORT_IMAGE_INIT_AT_RUNTIME:-}" ] ||
		init_rt="$init_rt,$PORT_IMAGE_INIT_AT_RUNTIME"

	# native-image fails the build when a class it expected at run time was
	# initialised during it anyway. Each name in the file answers one such error.
	# A file rather than a list here, so the set is reviewable as a diff.
	local bt_file="$PORT_DIR/image/initialize-at-build-time.txt" init_bt=""
	[ ! -f "$bt_file" ] || init_bt=$(grep -vE '^[[:space:]]*(#|$)' "$bt_file" | paste -sd, -)
	local bt_opts=()
	[ -z "$init_bt" ] || bt_opts=("--initialize-at-build-time=$init_bt")

	local extra_args=()
	read -ra extra_args -d '' <<<"${PORT_IMAGE_EXTRA_ARGS:-}" || true

	# app.apk and framework-res.apk are deliberately NOT on -cp: they reach Java
	# as launcher arguments and are read as files through libandroidfw. The apk
	# holds no .class entries at all -- its code is classes*.dex, which
	# native-image cannot read -- so putting it on -cp would add nothing but
	# analysis time and a risk of resource patterns matching apk entries.
	local argv=(
		"$native_image"
		--shared
		-o "$dir/$name"
		-cp "$class_path"
		# One directory each, so a config file a later trace adds is picked up
		# without a change here. ni-config/ is the trace and is never edited;
		# image/extra-config/ is what image *runs* proved the trace cannot see.
		-H:ConfigurationFileDirectories="$PORT_NI_CONFIG,$PORT_DIR/image/extra-config"
		-H:ReflectionConfigurationFiles="$gen_reflect"
		-H:JNIConfigurationFiles="$gen_jni"
		# Without it native-image "succeeds" by emitting a fallback image that
		# needs a JVM at run time -- which would export JNI_CreateJavaVM, pass
		# every check below and be worthless.
		--no-fallback
		# atlas reads java.io.FileDescriptor's private fd (FileDescriptorUtils).
		# On an image this has to be a *build* argument: passed at VM creation it
		# is accepted and ignored, the field stays null, and AssetManager.openFd,
		# Parcel and ParcelFileDescriptor all throw.
		--add-opens=java.base/java.io=ALL-UNNAMED
		-H:+UnlockExperimentalVMOptions
		-H:+ReportExceptionStackTraces
		-H:+PrintClassInitialization
		# The config was traced against HotSpot, so an entry naming a class the
		# image class path does not have is a real signal, not noise.
		-H:+WarnAboutMissingReflectionOrJNIMetadataElements
		"-J-Xmx${PORT_IMAGE_XMX:-10g}"
		"--parallelism=${PORT_IMAGE_PARALLELISM:-$(nproc)}"
		"--initialize-at-run-time=$init_rt"
		${bt_opts[@]+"${bt_opts[@]}"}
		${extra_args[@]+"${extra_args[@]}"}
	)

	echo "building the image with $("$native_image" --version | head -1)"
	echo "  atlas ${ATLAS_BRANCH:-?} ${ATLAS_REV:-?}, ${#cp_entries[@]} class path entries"
	echo "  metadata $PORT_NI_CONFIG, log $log"
	# a failed build must not leave the previous image looking current: the click
	# would package it and describe the wrong sources
	rm -f "$dir/$name.so" "$dir/build-stats.env"
	: >"$stamp"
	local status=0
	run_native_image "$log" "${argv[@]}" || status=$?
	if [ "$status" != 0 ]; then
		echo "native-image failed (exit $status) after ${elapsed_s}s," \
			"peak RSS $((peak_rss_kb / 1024)) MB; last lines of $log:" >&2
		tail -40 "$log" >&2
		exit 1
	fi
	echo "image built in ${elapsed_s}s, peak RSS $((peak_rss_kb / 1024)) MB"

	assert_jni_invocation_api "$dir/$name.so"
	# native-image writes the .so before this gate runs and nothing else marks an
	# image as rejected, so a rejected image is deleted.
	if ! check_class_initialization "$dir" "$stamp"; then
		rm -f "$dir/$name.so"
		echo "removed $dir/$name.so: it failed the class-initialization gate" >&2
		exit 1
	fi

	cat >"$dir/build-stats.env" <<-EOF
		PORT_IMAGE_LIB="$dir/$name.so"
		PORT_IMAGE_BYTES=$(stat -c%s "$dir/$name.so")
		PORT_IMAGE_BUILD_SECONDS=$elapsed_s
		PORT_IMAGE_PEAK_RSS_KB=$peak_rss_kb
		PORT_IMAGE_ARCH="$(uname -m)"
		PORT_IMAGE_GRAALVM="$("$native_image" --version | head -1)"
		PORT_IMAGE_INIT_AT_RUNTIME="$init_rt"
		ATLAS_REV="${ATLAS_REV:-}"
		ATLAS_API_IMPL_SHA="${ATLAS_API_IMPL_SHA:-}"
	EOF

	echo "image: $dir/$name.so ($(stat -c%s "$dir/$name.so") bytes)"
	echo "stats: $dir/build-stats.env, argv: $dir/build-argv.txt"
	echo "run it with: linux-port/run.sh --image --seconds 25"
}

case "$mode" in
stub) build_stub ;;
full) build_full ;;
esac
