#!/bin/sh
# SailfishOS launcher for the Kotlin/Native PhotonCamera.  The same binary as
# the Ubuntu Touch click; kn/click/run.sh explains the variables they share.
set -e

APP_DIR="${PC_APP_DIR:-/usr/share/photoncamera}"

# libmaliit-glib.so.0 and libcrypt.so.1 are DT_NEEDED and Sailfish has neither.
export LD_LIBRARY_PATH="$APP_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export GLIBC_TUNABLES=glibc.rtld.optional_static_tls=4096
export ATL_APPDIR="$APP_DIR"
export PHOTONCAMERA_ASSETS="$APP_DIR/assets"
export PHOTONCAMERA_HOME="${PHOTONCAMERA_HOME:-$HOME/Pictures/PhotonCamera}"

export ATL_UGLY_ENABLE_CAMERA=1
export ATL_CAMERA_BACKEND="${ATL_CAMERA_BACKEND:-camera2ndk}"
export ATL_SDK_INT="${ATL_SDK_INT:-34}"
export PC_APP_ID="${PC_APP_ID:-photoncamera}"

# The session sets these for its apps, but an ssh shell has none of them, and
# without EGL_PLATFORM libhybris does not pick its wayland platform.
export EGL_PLATFORM=wayland
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
[ -n "$WAYLAND_DISPLAY" ] || export WAYLAND_DISPLAY=/run/display/wayland-0

# The app reads Lomiri's grid unit (8 px per density step).  Silica's pixel
# ratio 1.0 is the original Jolla at Android hdpi, so density = 1.5 * ratio.
if [ -z "$GRID_UNIT_PX" ]; then
	ratio=$(dconf read /desktop/sailfish/silica/theme_pixel_ratio 2>/dev/null || true)
	GRID_UNIT_PX=$(awk -v r="${ratio:-1.5}" 'BEGIN { printf "%d", 12 * r + 0.5 }')
	export GRID_UNIT_PX
fi

# NcnnMl reads its models from "assets" relative to the working directory;
# a click starts in its package dir, a Sailfish app in $HOME.
cd "$APP_DIR"

# 0 is the run length: until the window closes.
exec "$APP_DIR/photoncam-kn" 0
