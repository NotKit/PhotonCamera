#!/bin/bash
# Runs PhotonCamera on the HotSpot launcher.
#
# The launcher boots atlas's normal startup sequence with the JVM in place of
# ART: Context.createApplication -> ContentProvider.createContentProviders ->
# Application.onCreate -> Activity.createMainActivity -> GLib main loop.
#
# Usage: linux-port/run.sh [--seconds N] [--stop-file PATH] [--data-dir DIR]
#                          [--fresh] [--activity CLASS] [--camera BACKEND]
#                          [--require-preview] [--image]
#                          [--no-gpu] [-- ARGS...]
#   --seconds N   quit after N seconds and check run.log (0 = run until closed)
#   --stop-file   with --seconds, quit as soon as this file appears
#   --data-dir    app data dir; default out/data, kept between runs
#   --fresh       wipe the data dir first (a first-launch run)
#   --activity    activity to start; default $PORT_ACTIVITY
#   --camera      ATL_CAMERA_BACKEND for this run (gst|camera2ndk|hybris|none)
#   --no-gpu      set ATL_NO_GPU=1. The app renders its whole pipeline with
#                 GLES, so this only makes sense for a UI-only smoke run.
#   --image       run the ahead-of-time vehicle: atlas's image launcher creating
#                 its VM from $PORT_IMAGE_LIB (build-image.sh) instead of
#                 libjvm.so. The launcher warns about --api-impl-jar and
#                 --classpath and ignores them, so one argv drives both.
#   --require-preview  with --seconds, require a synthetic camera frame and
#                 its GL presentation into the SurfaceView.
#   The window is portrait $PORT_WINDOW_WIDTH x $PORT_WINDOW_HEIGHT by
#   default (env.sh); the camera UI needs a tall display.
# Anything after -- is passed straight to the launcher.
#
# Writes out/run.log and, with --seconds, out/screenshots/run.png.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$PORT_DIR/launcher/display.sh"

seconds=0
stop_file=""
data_dir="${ANDROID_APP_DATA_DIR:-$PORT_OUT/data}"
fresh=0
no_gpu=0
require_preview=0
image=0
activity="$PORT_ACTIVITY"
extra_args=()

while [ $# -gt 0 ]; do
	case "$1" in
	--seconds) seconds="$2"; shift 2 ;;
	--stop-file) stop_file="$2"; shift 2 ;;
	--data-dir) data_dir="$2"; shift 2 ;;
	--activity) activity="$2"; shift 2 ;;
	--camera) export ATL_CAMERA_BACKEND="$2"; shift 2 ;;
	--fresh) fresh=1; shift ;;
	--no-gpu) no_gpu=1; shift ;;
	--image) image=1; shift ;;
	--require-preview) require_preview=1; shift ;;
	--) shift; extra_args=("$@"); break ;;
	*) echo "unknown option: $1" >&2; exit 1 ;;
	esac
done

if [ "$require_preview" = 1 ] && { [ "$seconds" = 0 ] || [ "$ATL_CAMERA_BACKEND" != gst ] || [ "$no_gpu" = 1 ]; }; then
	echo "--require-preview needs --seconds N, the gst camera and GPU rendering" >&2
	exit 2
fi

launcher="$PORT_LAUNCHER_BIN"
vehicle_args=()
if [ "$image" = 1 ]; then
	launcher="$PORT_IMAGE_LAUNCHER_BIN"
	vehicle_args=(--vm-library "$PORT_IMAGE_LIB")
	[ -f "$PORT_IMAGE_LIB" ] ||
		{ echo "no image at $PORT_IMAGE_LIB (run build-image.sh)" >&2; exit 1; }
fi
log="$PORT_OUT/run.log"
shots="$PORT_OUT/screenshots"

[ -x "$launcher" ] || { echo "no launcher at $launcher (run build-atlas.sh)" >&2; exit 1; }
[ -f "$ATLAS_OUT/artifacts.env" ] || { echo "no $ATLAS_OUT/artifacts.env (run build-atlas.sh)" >&2; exit 1; }
[ -f "$PORT_OUT/shim.jar" ] || { echo "no $PORT_OUT/shim.jar (run build-shim.sh)" >&2; exit 1; }
[ -f "$PORT_OUT/app.apk" ] || { echo "no $PORT_OUT/app.apk (run build-classpath.sh)" >&2; exit 1; }
[ -f "$PORT_LIB_OUT/libdngCreator.so" ] ||
	{ echo "no $PORT_LIB_OUT/libdngCreator.so (run native/build_natives_linux.sh)" >&2; exit 1; }
source "$ATLAS_OUT/artifacts.env"

mkdir -p "$shots"
[ "$fresh" = 1 ] && rm -rf "$data_dir"
mkdir -p "$data_dir"
export ANDROID_APP_DATA_DIR="$data_dir"

# libjvm.so is deliberately not in the launcher's RUNPATH
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server:$ATLAS_OUT${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# The camera is the whole point of this app, and atlas gates it behind an opt-in.
export ATL_UGLY_ENABLE_CAMERA="${ATL_UGLY_ENABLE_CAMERA:-1}"
export ATL_UGLY_ENABLE_MICROPHONE="${ATL_UGLY_ENABLE_MICROPHONE:-1}"
# camera2 (CameraCharacteristics, the RAW streams) only exists from API 21 on,
# and PhotonCamera's own minSdk is 26. atlas defaults to 9.
export ATL_SDK_INT="${ATL_SDK_INT:-34}"
# Unlike the Mercurygram port, this one needs a real GL context: every
# processing node is a shader. ATL_NO_GPU is opt-in, and only for a UI smoke run.
[ "$no_gpu" = 0 ] || export ATL_NO_GPU=1

# No on-screen keyboard on a desktop run: the maliit backend would talk to
# the session bus and pop the host's keyboard. The click sets its own module.
export ATL_IM_MODULE="${ATL_IM_MODULE:-none}"
# liblog drops anything below INFO by default, and the app's own logging is
# mostly Log.d.
export ANDROID_LOG_TAGS="${ANDROID_LOG_TAGS:-*:V}"

port_start_display

run_launcher() {
	# exec, so that backgrounding this function gives $! the launcher's own pid
	# and not that of the subshell wrapping it.
	exec "$launcher" \
		${vehicle_args[@]+"${vehicle_args[@]}"} \
		--api-impl-jar "$ATLAS_API_IMPL_JAR" \
		--framework-res "$ATLAS_FRAMEWORK_RES" \
		--natives-dir "$ATLAS_NATIVES_DIR" \
		--window-width "$PORT_WINDOW_WIDTH" \
		--window-height "$PORT_WINDOW_HEIGHT" \
		--classpath "$PORT_OUT/shim.jar:$PORT_OUT/classpath/*" \
		--library-path "$PORT_LIB_OUT" \
		--launch-activity "$activity" \
		-X "-XX:ErrorFile=$PORT_OUT/hs_err_pid%p.log" \
		${PORT_EXTRA_JVM_ARGV[@]+"${PORT_EXTRA_JVM_ARGV[@]}"} \
		"${extra_args[@]}" \
		"$PORT_OUT/app.apk"
}

# Interactive run: no timeout, no checks, the window is the output.
if [ "$seconds" = 0 ]; then
	echo "running PhotonCamera ($activity, camera $ATL_CAMERA_BACKEND," \
		"$([ "$image" = 1 ] && echo image || echo hotspot)), log in $log"
	run_launcher 2>&1 | tee "$log"
	exit "${PIPESTATUS[0]}"
fi

if [ -n "$stop_file" ]; then
	rm -f "$stop_file"
	echo "running PhotonCamera for up to ${seconds}s (until $stop_file), log in $log"
else
	echo "running PhotonCamera for ${seconds}s, log in $log"
fi
status=0
run_launcher >"$log" 2>&1 &
launcher_pid=$!

waited=0
while [ "$waited" -lt "$((seconds * 2))" ]; do
	[ -n "$stop_file" ] && [ -e "$stop_file" ] && break
	kill -0 "$launcher_pid" 2>/dev/null || break
	sleep 0.5
	waited=$((waited + 1))
done
# The screenshot is the last thing before the kill, so the UI has had the whole
# run to draw.
port_screenshot "$shots/run.png"
# SIGTERM first, but do not leave a launcher behind: it ignores SIGTERM, and the
# next build would fail to overwrite the running binary.
kill "$launcher_pid" 2>/dev/null || true
for _ in $(seq 20); do
	kill -0 "$launcher_pid" 2>/dev/null || break
	sleep 0.5
done
kill -9 "$launcher_pid" 2>/dev/null || true
wait "$launcher_pid" 2>/dev/null || status=$?

# --- verification -----------------------------------------------------------

log_text=$(cat "$log")

# 143 is SIGTERM, 137 the SIGKILL that follows when the launcher ignored it;
# both are how a timed run ends. Anything else is a real exit status.
if [ "$status" != 0 ] && [ "$status" != 143 ] && [ "$status" != 137 ]; then
	echo "the launcher exited $status:" >&2
	tail -40 "$log" >&2
	exit 1
fi

for expected in "JVM launched successfully" "boot: Application.onCreate returned" \
                "boot: main activity started"; do
	grep -qF "$expected" <<<"$log_text" || {
		echo "run.log has no '$expected'" >&2
		tail -40 "$log" >&2
		exit 1
	}
done

if grep -qF "A fatal error has been detected" <<<"$log_text"; then
	echo "the JVM crashed:" >&2
	grep -A 10 "A fatal error has been detected" <<<"$log_text" >&2
	exit 1
fi

# JNI ExceptionDescribe also prints "Exception in thread" for reflection
# failures the app catches, so use the actual uncaught-exception markers.
uncaught=$(grep -cE '^(error: exception during|FATAL EXCEPTION:|uncaught exception)' <<<"$log_text" || true)
[ "$uncaught" = 0 ] || {
	echo "$uncaught uncaught exceptions in $log:" >&2
	grep -E -A 12 '^(error: exception during|FATAL EXCEPTION:|uncaught exception)' <<<"$log_text" | head -60 >&2
	exit 1
}

if [ "$require_preview" = 1 ]; then
	# Frames reaching the app is the hard requirement. The display half is
	# backend-specific film: SurfaceView/SurfaceTexture log their first
	# posted frame, but the GLSurfaceView viewfinder this app uses logs
	# nothing, so a working preview can show neither marker. That half is
	# verified by looking at the window (or the screenshot, when one exists).
	grep -qF "Camera gst: first frame" <<<"$log_text" || {
		echo "run.log has no 'Camera gst: first frame'" >&2; exit 1; }
fi

echo "run ok: booted to $activity, no uncaught exceptions in $((waited / 2))s"
[ -f "$shots/run.png" ] && echo "screenshot: $shots/run.png"
exit 0
