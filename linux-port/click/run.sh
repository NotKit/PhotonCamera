#!/bin/sh
# Device launcher: run PhotonCamera through atlas. The click is unconfined, so
# this may use external tools.
#
# Mirrors linux-port/run.sh (the desktop boot loop) with device paths and the
# device's own camera2 stack in place of the synthetic GStreamer one.
#
# Two vehicles ship under the same package name, and which one is installed is
# read off the tree rather than configured: lib/libphotoncamera.so is the
# ahead-of-time image (no JVM, no class path, no CDS), and its absence means the
# jlink'd OpenJDK 21 with the jars and the class-data archive.

APP_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
PKG_NAME=photoncamera-jvm.nekit

# Prefer the version-independent 'current' path: it is stable across upgrades.
PKG_ROOT="/opt/click.ubuntu.com/${PKG_NAME}/current"
[ -d "${PKG_ROOT}/lib" ] || PKG_ROOT="${APP_DIR}"

if [ -f "${PKG_ROOT}/lib/libphotoncamera.so" ]; then
    VEHICLE=image
    LAUNCHER="${PKG_ROOT}/lib/android-translation-layer-image"
else
    VEHICLE=hotspot
    LAUNCHER="${PKG_ROOT}/lib/android-translation-layer-hotspot"
fi

# Lomiri starts a click app with XDG_RUNTIME_DIR=~/.cache and
# WAYLAND_DISPLAY=wayland-0, and the compositor's socket is in neither: it is in
# /run/user/<uid>. libwayland accepts an absolute WAYLAND_DISPLAY, so point it
# straight at the socket rather than moving XDG_RUNTIME_DIR, which the app uses
# for other things. Without this GLFW aborts the launch with
# "Wayland: Failed to connect to display" before the first frame.
if [ ! -S "${XDG_RUNTIME_DIR:-}/${WAYLAND_DISPLAY:-wayland-0}" ]; then
    for _sock in "/run/user/$(id -u)/${WAYLAND_DISPLAY:-wayland-0}" "/run/user/$(id -u)/wayland-0"; do
        [ -S "${_sock}" ] && { export WAYLAND_DISPLAY="${_sock}"; break; }
    done
fi

# lib/ holds every native object the click carries — the launcher, atlas's
# framework natives, the app's five JNI libraries and the art support libraries
# — so $ORIGIN covers the rest. The image vehicle stops there; the HotSpot one
# also needs jvm/lib/server, because libjvm.so is deliberately not in the
# launcher's RUNPATH (linux-port/CLAUDE.md).
export LD_LIBRARY_PATH="${PKG_ROOT}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
if [ "${VEHICLE}" = hotspot ]; then
    export JAVA_HOME="${PKG_ROOT}/jvm"
    export LD_LIBRARY_PATH="${JAVA_HOME}/lib/server:${LD_LIBRARY_PATH}"
    # HotSpot takes SIGSEGV for every implicit null check, so a JNI library that
    # installs its own handler and does not chain kills the VM on the next one
    # (libhalidealign does exactly that — BRINGUP_NOTES.md). libjsig interposes
    # sigaction() and keeps the VM's handler in front, delegating what it does
    # not recognise. PHOTONCAMERA_JSIG=off drops it.
    if [ "${PHOTONCAMERA_JSIG:-on}" != off ] && [ -f "${JAVA_HOME}/lib/libjsig.so" ]; then
        export LD_PRELOAD="${JAVA_HOME}/lib/libjsig.so${LD_PRELOAD:+:${LD_PRELOAD}}"
    fi
fi

# Keep app data in one dedicated place. atlas appends "<apk basename>_" to this,
# so the app's own dir is <this>/app.apk_. Honour a caller's value so a debug
# run can use a throwaway dir instead of the real prefs (mirrors linux-port/run.sh).
export ANDROID_APP_DATA_DIR="${ANDROID_APP_DATA_DIR:-${XDG_DATA_HOME:-${HOME}/.local/share}/${PKG_NAME}}"
mkdir -p "${ANDROID_APP_DATA_DIR}"

# Photos go to ~/Pictures/PhotonCamera and raw captures to its Raw/, like
# ~/Pictures/camera.ubports; backups and tuning stay in the app dir
# (FileManager reads photoncamera.photos.dir).
PHOTOS_DIR="${PHOTONCAMERA_PHOTOS_DIR:-${HOME}/Pictures/PhotonCamera}"

# liblog drops anything below INFO without this, and PhotonCamera's own logging
# is mostly Log.d; on the device the journal is the only log there is.
export ANDROID_LOG_TAGS="${ANDROID_LOG_TAGS:-*:V}"

# Without this the launcher opens its default 960x540 window and Lomiri resizes
# it to the panel afterwards; the app then lays out for the full screen while the
# surface still holds the small buffer, and the right/bottom of the UI is cut off
# for good (the layout size only changes once, so nothing redraws it).
export ATL_FORCE_FULLSCREEN=1

# Lomiri never marks the app surface "maximized", so atlas's heuristic would
# report multi-window.
export ATL_MULTI_WINDOW=0

# The framework builds sans-serif from the Roboto faces atlas ships. It looks for
# them beside its own library, which works for the desktop's one flat dir but not
# here: the natives are in lib/ and the atlas data in atlas/.
export ATL_FONT_DIR="${PKG_ROOT}/atlas/system/fonts"

# Wayland app_id must match the desktop file id (<pkg>_<app>_<version>) for
# Lomiri to associate the window with the launcher entry.
if [ -z "${APP_ID:-}" ] && [ -L "/opt/click.ubuntu.com/${PKG_NAME}/current" ]; then
    PKG_VERSION="$(basename "$(readlink -f "/opt/click.ubuntu.com/${PKG_NAME}/current")")"
    export APP_ID="${PKG_NAME}_photoncamera-jvm_${PKG_VERSION}"
fi

# Every processing node in this app is a GLES shader, so ATL_NO_GPU is not a
# fallback here — it is a UI-only smoke test that cannot take a picture.
[ -n "${PHOTONCAMERA_NO_GPU:-}" ] && export ATL_NO_GPU=1

# The camera is the whole point. atlas gates it behind an opt-in, and the backend
# is pinned so a device without libhybris' camera2 NDK library fails loudly
# instead of quietly serving videotestsrc through the gst backend — which
# advertises no RAW stream, so PhotonCamera would find no usable camera at all.
export ATL_UGLY_ENABLE_CAMERA=1
export ATL_UGLY_ENABLE_MICROPHONE=1
export ATL_CAMERA_BACKEND="${ATL_CAMERA_BACKEND:-camera2ndk}"

# camera2 (CameraCharacteristics, the RAW streams) only exists from API 21 on,
# and PhotonCamera's own minSdk is 26. atlas defaults to 9.
export ATL_SDK_INT="${ATL_SDK_INT:-34}"

# --- AppCDS -----------------------------------------------------------------
# The archive holds the parsed, verified form of the classes the app loads, so
# start-up skips most of the class loading. It is a cache, not a dependency, and
# the VM writes it at exit — so it has to live somewhere writable, which the
# package directory is not.
#
# -XX:+AutoCreateSharedArchive (JDK 19+) uses a valid archive and writes a fresh
# one when it is missing or stale. Two details make that less automatic than it
# sounds:
#   * HotSpot records each class-path entry's size AND mtime, and a mismatch is
#     a warning to the cds log plus a silent fallback — invisible from outside.
#     So fingerprint the class path the same way and treat a mismatch as a run
#     that has to write one.
#   * the archive is written mode 444, so a stale one cannot be replaced in
#     place; it has to be removed first or every later run falls back for ever.
#
# This layers on the base archive build.sh dumped into jvm/lib/server/classes.jsa.
# PHOTONCAMERA_CDS=off turns the whole thing off. None of it applies to the image
# vehicle: there are no classes to load, so there is nothing to archive.
CACHE="${XDG_CACHE_HOME:-${HOME}/.cache}/${PKG_NAME}"
mkdir -p "${CACHE}"
CDSOPT=""
if [ "${VEHICLE}" = hotspot ] && [ "${PHOTONCAMERA_CDS:-auto}" != off ]; then
    JSA="${CACHE}/app.jsa"
    CDSOPT="-X -XX:+AutoCreateSharedArchive -X -XX:SharedArchiveFile=${JSA}"
    CDSOPT="${CDSOPT} -X -Xlog:cds=warning:file=${CACHE}/cds.log"
    rm -f "${CACHE}/cds.log"
    CDSFP="$(ls -lLn --time-style=+%s "${PKG_ROOT}"/classpath/*.jar \
        "${PKG_ROOT}/atlas/api-impl.jar" 2>/dev/null |
        awk '{print $5, $6, $NF}' | md5sum | cut -d" " -f1)"
    if [ ! -f "${JSA}" ] || [ "${CDSFP}" != "$(cat "${JSA}.fp" 2>/dev/null)" ]; then
        echo "CDS: archive missing or stale -- this run writes one"
        rm -f "${JSA}" "${JSA}.fp"
    fi
    # The archive is written on the way out of the VM, which is past the exec
    # below: nothing in this script runs again to record what it was written
    # against, so write the fingerprint now. A run that dies before producing an
    # archive does not fool the next one — that check is "archive missing OR
    # fingerprint differs", and a missing archive alone forces a fresh write.
    [ -n "${CDSFP}" ] && printf '%s' "${CDSFP}" > "${JSA}.fp"
fi

# The image already contains the framework, the shim and the app, so it takes
# neither --api-impl-jar nor --classpath: the launcher warns about both and
# ignores them, and this click does not ship the jars at all.
#
# Prepended to "$@" rather than built into a string: the class path ends in a
# literal "*", which the launcher expands itself (a JVM does not), and an
# unquoted variable would hand it to the shell's globbing first.
if [ "${VEHICLE}" = image ]; then
    set -- --vm-library "${PKG_ROOT}/lib/libphotoncamera.so" "$@"
else
    set -- --api-impl-jar "${PKG_ROOT}/atlas/api-impl.jar" \
           --classpath "${PKG_ROOT}/classpath/shim.jar:${PKG_ROOT}/classpath/*" "$@"
fi
set -- -X "-Dphotoncamera.photos.dir=${PHOTOS_DIR}" "$@"

exec "${LAUNCHER}" \
    --framework-res "${PKG_ROOT}/atlas/framework-res.apk" \
    --natives-dir "${PKG_ROOT}/lib" \
    --library-path "${PKG_ROOT}/lib" \
    --launch-activity com.particlesdevs.photoncamera.ui.SplashActivity \
    ${CDSOPT} \
    "$@" \
    "${PKG_ROOT}/app.apk"
