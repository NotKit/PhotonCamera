#!/bin/bash
# Record the native-image metadata: what the app reaches by name.
#
# Runs the port's own checks under GraalVM's tracing agent, all merging into one
# config directory, so the metadata covers the paths those checks take and not
# just a boot. The agent writes its files when the VM shuts down, so every check
# has to be allowed to finish -- a killed run contributes nothing.
#
#   natives   check-native-libs.sh: every System.loadLibrary the app does, and
#             the JNI entry points behind them. Seconds, no camera.
#   boot      run.sh --fresh: the splash, the permission flow, the camera
#             activity, the GL viewfinder. This is most of the UI's reflection.
#   pipeline  run-dng-pipeline.sh on a recorded DNG: PostPipeline, the GL nodes
#             and the DNG writer. Needs a DNG (--dng, or $PORT_TRACE_DNG); it is
#             reported as skipped rather than silently dropped, because an image
#             without it takes pictures and then fails while processing them.
#
# The result belongs in the repository: $PORT_NI_CONFIG is tracked, and
# build-image.sh reads it. Commit what this writes, and never edit it by hand --
# metadata an image *run* proved the trace cannot see goes in
# image/extra-config/ instead, with the failure that justifies it.
#
# Usage: linux-port/trace-metadata.sh [--config-dir DIR] [--only NAME]...
#                                     [--merge] [--dng PATH]
#   --config-dir  where the agent writes; default $PORT_NI_CONFIG
#   --only NAME   run only this check (natives|boot|pipeline), repeatable. A
#                 partial trace is not a usable config, so this needs --merge or
#                 a --config-dir of its own
#   --merge       add to what is in the config dir instead of wiping it
#   --dng PATH    the DNG (or burst directory) the pipeline check reads
#
# Exits non-zero if a check fails or the config dir did not end up complete.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

config_dir="$PORT_NI_CONFIG"
config_dir_given=0
merge=0
dng="${PORT_TRACE_DNG:-}"
only=()

while [ $# -gt 0 ]; do
	case "$1" in
	--config-dir) config_dir="$2"; config_dir_given=1; shift 2 ;;
	--only) only+=("$2"); shift 2 ;;
	--dng) dng="$2"; shift 2 ;;
	--merge) merge=1; shift ;;
	*) echo "unknown option: $1" >&2; exit 1 ;;
	esac
done

all_names=(natives boot pipeline)
names=("${all_names[@]}")
if [ "${#only[@]}" -gt 0 ]; then
	for want in "${only[@]}"; do
		printf '%s\n' "${all_names[@]}" | grep -qx "$want" ||
			{ echo "unknown check '$want' (${all_names[*]})" >&2; exit 1; }
	done
	names=("${only[@]}")
	[ "$merge" = 1 ] || [ "$config_dir_given" = 1 ] || {
		echo "--only ${only[*]} would replace $config_dir with a partial config." >&2
		echo "Pass --merge to add to it, or --config-dir DIR to trace elsewhere." >&2
		exit 1
	}
fi

# The agent ships with GraalVM, so the checks have to run on GraalVM's libjvm.so.
[ -x "$GRAALVM_HOME/bin/java" ] ||
	{ echo "no java in \$GRAALVM_HOME ($GRAALVM_HOME)" >&2; exit 1; }
[ -f "$GRAALVM_HOME/lib/libnative-image-agent.so" ] ||
	{ echo "no tracing agent in $GRAALVM_HOME/lib" >&2; exit 1; }
# env.sh keeps an externally-set JAVA_HOME only while it reports 21.x and
# silently replaces anything else with the port's OpenJDK -- which has no agent,
# so the run would die on an unresolvable -agentlib with nothing pointing here.
graal_java=$("$GRAALVM_HOME/bin/java" -version 2>&1) ||
	{ echo "$GRAALVM_HOME/bin/java -version failed:" >&2; echo "$graal_java" >&2; exit 1; }
case "$graal_java" in
*'version "21.'*) ;;
*) echo "\$GRAALVM_HOME is not a JDK 21, so env.sh would drop it: $(head -1 <<<"$graal_java")" >&2
	exit 1 ;;
esac
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# The agent only ever ADDS to a config-merge-dir, so a stale directory keeps
# entries nothing traces any more -- and each surviving entry is an image root
# the build then has to honour. Start empty unless asked to merge.
stamp_file="${config_dir%/}.trace-stamp"
if [ "$merge" = 0 ] && [ -e "$config_dir" ]; then
	[ -d "$config_dir" ] || { echo "$config_dir is not a directory" >&2; exit 1; }
	# rm -rf on a tracked path: only when everything in it is agent output. The
	# README that documents the directory is allowed to stay.
	foreign=$(find "$config_dir" -mindepth 1 -maxdepth 1 \
		! -name '*-config.json' ! -name 'README.md' \
		! -name 'agent-extracted-predefined-classes' -print)
	[ -z "$foreign" ] || {
		echo "refusing to wipe $config_dir: it holds files no trace wrote:" >&2
		printf '%s\n' "$foreign" >&2
		exit 1
	}
	echo "wiping $config_dir (--merge to keep what is in it)"
	find "$config_dir" -mindepth 1 -maxdepth 1 ! -name 'README.md' -exec rm -rf {} +
fi
rm -f "$stamp_file"
mkdir -p "$config_dir"

# config-write-period-secs, and it is not a tuning knob: the agent writes its
# files from a JVM shutdown hook, and NOTHING here shuts a VM down. atlas's
# launcher ends a --run-class check with a bare exit(0) from C (main.c,
# run_class_main) and never calls DestroyJavaVM, and run.sh ends a timed run
# with SIGTERM and then SIGKILL. Both leave the config directory empty while
# every check still passes -- which is exactly how this looks when it is wrong.
# A periodic write means the last few seconds of a run are what is lost, rather
# than all of it.
agent_opt="-agentlib:native-image-agent=config-merge-dir=$config_dir"
agent_opt="$agent_opt,config-write-period-secs=3,config-write-initial-delay-secs=1"
# The trace has to see what the image will do, not what HotSpot does: atlas
# gates some getDeclaredMethod calls on this property, and without it the agent
# records entries the image never asks for.
image_opt="-Dorg.graalvm.nativeimage.imagecode=runtime"
export PORT_EXTRA_JVM_ARGS="$agent_opt $image_opt"

# Counts, not diffs: this is what goes in BRINGUP_NOTES.md and what says at a
# glance whether a check contributed anything.
config_stats() { # dir prefix
	python3 - "$1" "$2" <<-'PY'
		import json, os, sys
		d, prefix = sys.argv[1], sys.argv[2]
		def load(name):
		    p = os.path.join(d, name)
		    try:
		        with open(p) as f:
		            return json.load(f), os.path.getsize(p)
		    except (FileNotFoundError, ValueError):
		        return None, 0
		refl, refl_size = load("reflect-config.json")
		jni, jni_size = load("jni-config.json")
		res, _ = load("resource-config.json")
		proxy, _ = load("proxy-config.json")
		def n(x):
		    return len(x) if x is not None else 0
		res_n = 0
		if isinstance(res, dict):
		    r = res.get("resources", [])
		    res_n = (len(r.get("includes", [])) + len(r.get("excludes", []))
		             if isinstance(r, dict) else len(r)) + len(res.get("bundles", []))
		print(f"{prefix} reflect {n(refl)} classes ({refl_size} B), "
		      f"jni {n(jni)} classes ({jni_size} B), "
		      f"resource {res_n} entries, proxy {n(proxy)}")
	PY
}

run_check() { # name script args...
	local name="$1" script="$2"; shift 2
	local log="$PORT_OUT/trace-$name.log" started status=0
	echo
	echo "=== $name: $script $* ==="
	started=$(date +%s)
	# set +e, not `|| true`: `||` runs another command, and that command's status
	# is what PIPESTATUS then holds, so the check would always look successful.
	set +e
	"$PORT_DIR/$script" "$@" 2>&1 | tee "$log"
	status=${PIPESTATUS[0]}
	set -e
	echo "--- $name exited $status after $(( $(date +%s) - started ))s"
	config_stats "$config_dir" "  after $name:"
	return "$status"
}

echo "tracing into $config_dir with JAVA_HOME=$JAVA_HOME"
echo "agent option: $PORT_EXTRA_JVM_ARGS"
config_stats "$config_dir" "before:"

failed=()
skipped=()
for name in "${names[@]}"; do
	case "$name" in
	natives) run_check "$name" check-native-libs.sh || failed+=("$name") ;;
	# --fresh: the first-launch path is the one with the permission flow in it,
	# and that flow is reflection the second launch never does again.
	boot)
		run_check "$name" run.sh --seconds 40 --fresh --require-preview ||
			failed+=("$name") ;;
	pipeline)
		if [ -z "$dng" ]; then
			echo
			echo "=== pipeline: skipped, no DNG (--dng PATH or \$PORT_TRACE_DNG) ==="
			skipped+=("$name")
		elif [ ! -e "$dng" ]; then
			echo "no DNG at $dng" >&2
			failed+=("$name")
		else
			run_check "$name" run-dng-pipeline.sh "$dng" \
				"$PORT_OUT/trace-pipeline.png" || failed+=("$name")
		fi ;;
	esac
done

echo
config_stats "$config_dir" "merged:"

if [ "${#failed[@]}" -gt 0 ]; then
	echo
	echo "trace incomplete: ${#failed[@]} check(s) failed: ${failed[*]}" >&2
	echo "(their logs are in $PORT_OUT/trace-<name>.log)" >&2
	exit 1
fi

# Every check passing is not the same as the agent having written a config: it
# writes at VM shutdown, and an option that never reached the VM leaves the
# directory as it was while each check still exits 0.
missing=()
for f in reflect-config.json jni-config.json resource-config.json \
	proxy-config.json serialization-config.json; do
	[ -s "$config_dir/$f" ] || missing+=("$f")
done
if [ "${#missing[@]}" -gt 0 ]; then
	echo
	echo "the agent left $config_dir incomplete, missing: ${missing[*]}" >&2
	echo "(did every run load the agent? grep 'jvm option' in the trace logs)" >&2
	exit 1
fi
count_entries() { # file
	python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$1" ||
		{ echo "$1 does not parse" >&2; exit 1; }
}
reflect_n=$(count_entries "$config_dir/reflect-config.json")
jni_n=$(count_entries "$config_dir/jni-config.json")
# Either one on its own is a real trace -- check-native-libs.sh reflects on
# nothing and still records the JNI surface the libraries load through. Both
# empty is the failure: the agent ran and saw nothing, which is a check that
# never reached the app.
[ "$reflect_n" -gt 0 ] || [ "$jni_n" -gt 0 ] ||
	{ echo "$config_dir holds no reflection and no JNI entries: nothing was traced" >&2; exit 1; }

merge_word=no; [ "$merge" = 0 ] || merge_word=yes
{
	printf 'checks: %s\n' "${names[*]}"
	[ "${#skipped[@]}" = 0 ] || printf 'skipped: %s\n' "${skipped[*]}"
	printf 'merged onto an existing config: %s\ndate: %s\njava: %s\n' \
		"$merge_word" "$(date -Is)" "$JAVA_HOME"
	printf 'atlas: %s\napp: %s\n' \
		"$(sed -n 's/^ATLAS_REV="\(.*\)"/\1/p' "$ATLAS_OUT/artifacts.env" 2>/dev/null)" \
		"$(cat "$PORT_OUT/app-source-rev" 2>/dev/null)"
	printf 'reflect-config: %s classes\njni-config: %s classes\n' "$reflect_n" "$jni_n"
} >"$stamp_file"

echo
if [ "${#names[@]}" = "${#all_names[@]}" ] && [ "${#skipped[@]}" = 0 ]; then
	echo "trace complete: ${names[*]} all passed, $reflect_n reflect and $jni_n jni classes"
else
	# Say so rather than let "complete" stand for a config covering one path:
	# build-image.sh builds from whatever is in this directory.
	echo "partial trace: ran ${names[*]}${skipped[*]:+, skipped ${skipped[*]}}," \
		"so $config_dir is not the full profile ($reflect_n reflect, $jni_n jni classes)"
fi
echo "config: $config_dir (commit it), stamp: $stamp_file"
