#!/bin/sh
# Device launcher for the Kotlin/Native PhotonCamera click.  Lomiri execs this
# through the desktop hook; every precondition scripts/run-phone.sh discovered
# is set below, because there is no ssh here to set it afterwards.
#
# SHELL BUILTINS ONLY until the final exec: click confinement forbids exec'ing
# anything outside the package, so no pgrep, no id, no readlink.
set -e

APP_DIR="${0%/*}"
case "$APP_DIR" in
	/*) ;;
	*) APP_DIR="$PWD/${APP_DIR#./}" ;;
esac
PKG=photoncamera.thekit

# libmaliit-glib.so.0 is the one DT_NEEDED Ubuntu Touch does not carry: it is on
# the link line because compose-ui's klib manifest names ak-keyboard-maliit, so
# the loader demands it whether or not a keyboard is ever shown.  Without it the
# process dies with "error while loading shared libraries" before main().
export LD_LIBRARY_PATH="$APP_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# The hybris linker is dlopen'd after this process already has its TLS, and on a
# Mali device there is no room left in the static block: "cannot allocate memory
# in static TLS block", before a single camera call.  glibc reserves the surplus
# when asked, and only when asked at start-up.
export GLIBC_TUNABLES=glibc.rtld.optional_static_tls=4096

# host/appdir_stub.c answers Aurora's appdir_get_path(PACKAGE_FILES) with this,
# and CMP's resource reader appends "/resources" to it.  It defaults to the
# directory of /proc/self/exe, which is already right here -- named anyway so a
# future symlinked or relocated launcher cannot move the resources out from
# under the first frame that draws an icon.
export ATL_APPDIR="$APP_DIR"

# The pipeline's shaders, LUTs and ncnn models (app/src/main/assets).  Without
# them the first GL program compile dies loading "shaders/merge/merge00.glsl".
export PHOTONCAMERA_ASSETS="$APP_DIR/assets"

# ONE writable root for everything the app writes: shared_prefs, files, cache
# and DCIM/{Camera,PhotonCamera/{Raw,Tuning}}.  It has to be one directory
# because Context.defaultHome() and Environment.root() read the same variable,
# and it has to be under Pictures because that is the only user-visible place
# the apparmor profile's picture_files group grants -- and where the gallery
# looks for what a capture produced.
export PHOTONCAMERA_HOME="${PHOTONCAMERA_HOME:-$HOME/Pictures/PhotonCamera}"

# atlas gates the camera behind an opt-in and the backend is pinned, so a device
# without libhybris' camera2 NDK library fails loudly instead of quietly
# offering a backend with no RAW stream -- on which PhotonCamera finds no usable
# camera at all.  camera2 (CameraCharacteristics, the RAW streams) starts at API
# 21 and the app's own minSdk is 26; atlas defaults to 9.
export ATL_UGLY_ENABLE_CAMERA=1
export ATL_CAMERA_BACKEND="${ATL_CAMERA_BACKEND:-camera2ndk}"
export ATL_SDK_INT="${ATL_SDK_INT:-34}"

# THE SURFACE'S APP ID.  Mir hands touch, and Lomiri a foreground lifecycle,
# only to a surface the shell can match to the application it started -- by the
# app id, which for a click is the <pkg>_<app>_<version> triplet Lomiri exports
# as APP_ID.  With the two out of step the shell decides this is a background
# app and SIGSTOPs it, and the window never takes a tap.
export PC_APP_ID="${APP_ID:-photoncamera}"

# Lomiri remaps XDG_RUNTIME_DIR for a confined app, and the compositor's socket
# is not in the remapped one; libwayland accepts an absolute path, so hand it
# the real socket instead of moving XDG_RUNTIME_DIR, which the app uses for
# other things.  /proc/self/status is the only way to the uid without exec'ing.
APP_UID=32011
while read -r _k _v _r; do
	[ "$_k" = "Uid:" ] && { APP_UID="$_v"; break; }
done < /proc/self/status 2>/dev/null || true

for _c in "/run/user/$APP_UID/${WAYLAND_DISPLAY:-wayland-0}" "/run/user/$APP_UID/wayland-0" \
	"${XDG_RUNTIME_DIR:-}/${WAYLAND_DISPLAY:-wayland-0}"; do
	case "$_c" in ""|/wayland-0) continue ;; esac
	if [ -S "$_c" ]; then
		export WAYLAND_DISPLAY="$_c"
		export PC_SOCKET="$_c"
		break
	fi
done

# GRID_UNIT_PX is Lomiri's, and a click app inherits it: the UI density is that
# divided by 8.  Started any other way it is absent and the app lays out at
# density 2, which is not what the phone shows.

# DISPLAY IS LOMIRI'S TOO, AND IT IS A TRAP.  Every click app inherits
# DISPLAY=:0, which is a live Xwayland, and glvnd picks its EGL vendor from the
# environment: eglGetDisplay(EGL_DEFAULT_DISPLAY) then answers with Mesa
# swrast instead of the Adreno, and the merge dies on llvmpipe with SIGILL
# halfway through a capture.  This app is a Wayland client and has no use for
# X; android/opengl/EGL.kt prefers the window's own display for the same
# reason, and this keeps every other library out of the X11 path as well.
unset DISPLAY

# THE ARGUMENT IS A RUN LENGTH IN SECONDS and 0 means "until the window closes".
# The bring-up default is 10, so an app launched without it would vanish ten
# seconds in and look like a crash.
exec "$APP_DIR/photoncam-kn" 0
