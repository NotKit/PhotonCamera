#!/bin/bash
# Record the native-image metadata: what the app reaches by name.
#
# Runs the port's own checks under GraalVM's tracing agent, each into a
# directory of its own, and merges the results with native-image-configure, so
# the metadata covers the paths those checks take and not just a boot.
#
# Nothing here shuts a VM down -- the launcher ends a --run-class check with a
# bare exit(0) and run.sh kills a timed one -- so the agent's shutdown hook
# never runs and none of this works the way the documentation assumes. See
# "config-write-period-secs" and "resolve_snapshot" below: between them they are
# the whole reason this script is not three lines.
#
#   natives   check-native-libs.sh: every System.loadLibrary the app does, and
#             the JNI entry points behind them. Seconds, no camera.
#   boot      run.sh --fresh on the synthetic gst camera: the splash, the
#             permission flow, the camera activity, the GL viewfinder. This is
#             most of the UI's reflection.
#   capture   run.sh on the replay camera, with a scheduled tap on the shutter:
#             a recorded burst played back as a real camera2 still capture, so
#             the trace covers HDRX, the GL pipeline, the DNG writer and the
#             JPEG save. Needs a .atlcam recording (--replay, or
#             $PORT_TRACE_REPLAY, default: the first one in out/replay/); it is
#             reported as skipped rather than silently dropped, because an image
#             without it takes a picture and then fails while processing it.
#
# The boot check runs with a data dir of its own. --fresh wipes what it is
# given, and out/data carries the camera id and the save-raw preference the
# replay run needs (HANDOVER_hdrx_black_jpeg.md) -- wiping those would leave the
# capture check on the front camera, matching no replay stream.
#
# The result belongs in the repository: $PORT_NI_CONFIG is tracked, and
# build-image.sh reads it. Commit what this writes, and never edit it by hand --
# metadata an image *run* proved the trace cannot see goes in
# image/extra-config/ instead, with the failure that justifies it.
#
# Usage: linux-port/trace-metadata.sh [--config-dir DIR] [--only NAME]...
#                                     [--merge] [--dng PATH]
#   --config-dir  where the agent writes; default $PORT_NI_CONFIG
#   --only NAME   run only this check (natives|boot|capture), repeatable. A
#                 partial trace is not a usable config, so this needs --merge or
#                 a --config-dir of its own
#   --merge       add to what is in the config dir instead of wiping it
#   --replay PATH the .atlcam recording the capture check plays back
#   --import NAME=DIR
#                 take an agent directory recorded elsewhere as check NAME's
#                 result instead of running it. The capture check needs a real
#                 GPU: llvmpipe segfaults in LLVMTypeOf when the pipeline
#                 creates its GL context, so a box with no GPU has to borrow
#                 one (BRINGUP_NOTES.md)
#   --tap S:X,Y   when and where the capture check taps the shutter; default
#                 25:270,820, which is the shutter in a 540x960 portrait window
#
# Exits non-zero if a check fails or the config dir did not end up complete.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

config_dir="$PORT_NI_CONFIG"
config_dir_given=0
merge=0
replay="${PORT_TRACE_REPLAY:-}"
tap="${PORT_TRACE_TAP:-25:270,820}"
only=()
declare -A imported=()

while [ $# -gt 0 ]; do
	case "$1" in
	--config-dir) config_dir="$2"; config_dir_given=1; shift 2 ;;
	--only) only+=("$2"); shift 2 ;;
	--replay) replay="$2"; shift 2 ;;
	--import)
		[ "${2#*=}" != "$2" ] || { echo "--import wants NAME=DIR, got '$2'" >&2; exit 1; }
		imported["${2%%=*}"]="${2#*=}"; shift 2 ;;
	--tap) tap="$2"; shift 2 ;;
	--merge) merge=1; shift ;;
	*) echo "unknown option: $1" >&2; exit 1 ;;
	esac
done

all_names=(natives boot capture)

# The recording is large and lives outside the repository; out/replay holds
# symlinks to it. Any one of them is a full still burst, so the first will do.
if [ -z "$replay" ] && [ -d "$PORT_OUT/replay" ]; then
	replay=$(find -L "$PORT_OUT/replay" -maxdepth 1 -name '*.atlcam' | sort | head -1)
fi
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
		! -name '*-config.json' ! -name 'README.md' -print)
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

# config-write-period-secs is not a tuning knob: the agent writes its files from
# a JVM shutdown hook, and nothing here shuts a VM down. atlas's launcher ends a
# --run-class check with a bare exit(0) from C (main.c, run_class_main) and
# never calls DestroyJavaVM; run.sh ends a timed run with SIGTERM and then
# SIGKILL. Without a periodic write both leave the config directory empty while
# every check still passes -- which is exactly how this looks when it is wrong.
#
# Every check traces into a directory of its own, and the results are merged at
# the end with GraalVM's own native-image-configure. One agent per directory is
# what the agent is built for: config-merge-dir across successive processes
# leaves a .lock the next run dies on, because the lock is dropped from the same
# shutdown hook that never runs here.
trace_root="$PORT_OUT/ni-trace"

# The trace has to see what the image will do, not what HotSpot does: atlas
# gates some getDeclaredMethod calls on this property, and without it the agent
# records entries the image never asks for.
image_opt="-Dorg.graalvm.nativeimage.imagecode=runtime"

configure="$GRAALVM_HOME/bin/native-image-configure"
[ -x "$configure" ] ||
	{ echo "no native-image-configure in $GRAALVM_HOME/bin" >&2; exit 1; }

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

# The agent's periodic writer publishes only its FIRST write into the output
# directory. It creates its temp directory *inside* that directory, which
# changes it, so every later write refuses ("... has been modified by another
# process") and is left in a temp directory of its own. Each of those is a
# complete snapshot, so the newest one is the run's real config -- what got
# published is the first second of it. Measured: a 30 s boot published 3 JNI
# classes and left 98 reflection and 61 JNI classes in its last snapshot.
#
# A run that did shut its VM down cleanly would publish properly and leave no
# snapshots, so the directory itself is the fallback rather than an error.
resolve_snapshot() { # name -> writes $trace_root/<name>.final
	local name="$1" dir="$trace_root/$name" final="$trace_root/$name.final" newest
	rm -rf "$final"; mkdir -p "$final"
	newest=$(find "$dir" -mindepth 1 -maxdepth 1 -type d -name 'agent-pid*' \
		-printf '%T@ %p\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)
	[ -n "$newest" ] || newest="$dir"
	find "$newest" -maxdepth 1 -name '*-config.json' -exec cp {} "$final/" \; 2>/dev/null
	[ -n "$(ls -A "$final" 2>/dev/null)" ] || { rm -rf "$final"; return 1; }
	echo "  snapshot: $(basename "$newest")"
}

run_check() { # name script args...
	local name="$1" script="$2"; shift 2
	local log="$PORT_OUT/trace-$name.log" started status=0
	echo
	echo "=== $name: $script $* ==="
	rm -rf "${trace_root:?}/$name" "$trace_root/$name.final"
	mkdir -p "$trace_root/$name"
	export PORT_EXTRA_JVM_ARGS="-agentlib:native-image-agent=config-output-dir=$trace_root/$name,config-write-period-secs=3,config-write-initial-delay-secs=1 $image_opt"
	started=$(date +%s)
	# set +e, not `|| true`: `||` runs another command, and that command's status
	# is what PIPESTATUS then holds, so the check would always look successful.
	set +e
	"$PORT_DIR/$script" "$@" 2>&1 | tee "$log"
	status=${PIPESTATUS[0]}
	set -e
	echo "--- $name exited $status after $(( $(date +%s) - started ))s"
	# run.sh and the --run-class checks all write the launcher's own output to
	# out/run.log, so the next check overwrites it. That log is where the agent
	# reports itself ("jvm option: -agentlib:..."), which is the only evidence
	# of why a check contributed nothing -- keep one per check.
	[ ! -f "$PORT_OUT/run.log" ] || cp "$PORT_OUT/run.log" "$PORT_OUT/trace-$name-run.log"
	# A failed check can still have traced something useful before it died, so
	# the snapshot is resolved either way and its own status is separate.
	if resolve_snapshot "$name"; then
		config_stats "$trace_root/$name.final" "  $name traced:"
	else
		echo "  $name traced nothing"
	fi
	return "$status"
}

echo "tracing into $config_dir with JAVA_HOME=$JAVA_HOME"
[ -z "$replay" ] || echo "replay recording: $replay, tap $tap"
echo "agent: periodic writes into $trace_root/<check>, merged at the end"
config_stats "$config_dir" "before:"

failed=()
skipped=()
for name in "${names[@]}"; do
	# A check recorded on another machine. Resolved the same way a local one is,
	# so an imported directory may be either a published config or the agent's
	# snapshot directories.
	if [ -n "${imported[$name]:-}" ]; then
		echo
		echo "=== $name: imported from ${imported[$name]} ==="
		[ -d "${imported[$name]}" ] ||
			{ echo "no directory at ${imported[$name]}" >&2; failed+=("$name"); continue; }
		rm -rf "${trace_root:?}/$name"
		mkdir -p "$trace_root"
		cp -r "${imported[$name]}" "$trace_root/$name"
		if resolve_snapshot "$name"; then
			config_stats "$trace_root/$name.final" "  $name imported:"
		else
			echo "  $name imported nothing" >&2
			failed+=("$name")
		fi
		continue
	fi
	case "$name" in
	natives) run_check "$name" check-native-libs.sh || failed+=("$name") ;;
	# --fresh: the first-launch path is the one with the permission flow in it,
	# and that flow is reflection the second launch never does again.
	boot)
		run_check "$name" run.sh --seconds 40 --fresh \
			--data-dir "$PORT_OUT/trace-data" --require-preview ||
			failed+=("$name") ;;
	capture)
		if [ -z "$replay" ]; then
			echo
			echo "=== capture: skipped, no recording (--replay PATH or \$PORT_TRACE_REPLAY) ==="
			skipped+=("$name")
		elif [ ! -e "$replay" ]; then
			echo "no recording at $replay" >&2
			failed+=("$name")
		else
			# ATL_DEBUG_TAP is atlas's own scheduled tap (ATLWindow.c): there is
			# no other way to press the shutter on a headless run, and a capture
			# is the whole point of this check.
			ATL_CAMERA_REPLAY="$replay" ATL_DEBUG_TAP="$tap" \
				run_check "$name" run.sh --camera replay --seconds 120 ||
				failed+=("$name")
		fi ;;
	esac
done

if [ "${#failed[@]}" -gt 0 ]; then
	echo
	echo "trace incomplete: ${#failed[@]} check(s) failed: ${failed[*]}" >&2
	echo "(their logs are in $PORT_OUT/trace-<name>.log," \
		"the launcher's own in trace-<name>-run.log)" >&2
	exit 1
fi

# --- merge ------------------------------------------------------------------

# GraalVM's own tool, which is what the agent's "running multiple processes"
# warning points at. One --input-dir per check; --merge adds what was in the
# config directory already, through a copy, because it is also the output.
inputs=()
for name in "${names[@]}"; do
	[ -d "$trace_root/$name.final" ] || continue
	inputs+=("--input-dir=$trace_root/$name.final")
done
if [ "$merge" = 1 ] && compgen -G "$config_dir/*-config.json" >/dev/null; then
	rm -rf "$trace_root/previous"; mkdir -p "$trace_root/previous"
	cp "$config_dir"/*-config.json "$trace_root/previous/"
	inputs+=("--input-dir=$trace_root/previous")
fi
[ "${#inputs[@]}" -gt 0 ] ||
	{ echo "no check traced anything; nothing to merge" >&2; exit 1; }

echo
echo "=== merging ${#inputs[@]} traces into $config_dir ==="
"$configure" generate "${inputs[@]}" --output-dir="$config_dir"
config_stats "$config_dir" "merged:"

# A merge that produced no file is not something to find out about at image
# build time, an hour later.
missing=()
for f in reflect-config.json jni-config.json resource-config.json \
	proxy-config.json serialization-config.json; do
	[ -s "$config_dir/$f" ] || missing+=("$f")
done
if [ "${#missing[@]}" -gt 0 ]; then
	echo
	echo "$config_dir is incomplete, missing: ${missing[*]}" >&2
	echo "(did every run load the agent? grep 'jvm option: -agentlib'" \
		"in $PORT_OUT/trace-<name>-run.log)" >&2
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
	[ "${#imported[@]}" = 0 ] || printf 'imported: %s\n' "${!imported[*]}"
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
