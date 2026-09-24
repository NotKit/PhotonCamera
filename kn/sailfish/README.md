# The SailfishOS RPM (aarch64)

The same Kotlin/Native binary as the Ubuntu Touch click, packaged as an RPM.
Tested on the Jolla Phone (2026), Sailfish OS 5.3, Android 16 vendor.

    ARCH=arm64 kn/scripts/build-kn.sh -PwithApp   # the binary, as for the click
    kn/sailfish/build.sh                          # -> kn/out/sailfish/RPMS/aarch64/

    scp kn/out/sailfish/RPMS/aarch64/photoncamera-*.rpm defaultuser@<phone>:
    ssh defaultuser@<phone> 'devel-su pkcon install-local photoncamera-*.rpm'

`build.sh` runs `rpmbuild` in the Sailfish Platform SDK's sb2 target
(`/srv/sailfishos/sdks/sfossdk/mer-sdk-chroot`, target `SailfishOS-latest-aarch64`;
`SFOS_SDK` and `SFOS_TARGET` override them).

## What differs from Ubuntu Touch

* **TLS alignment.** Sailfish's libhybris reads bionic's TLS slots at `tp+16`.
  With glibc on aarch64 that is where the executable's own TLS block starts, so
  the first EGL call faulted inside the hybris linker. `host/mgwl.c` aligns the
  block to 256, which moves it to `tp+256`. `build.sh` refuses a binary without it.
* **Two bundled libraries.** `libmaliit-glib.so.0` as on UT, plus `libcrypt.so.1`
  from the noble sysroot. Sailfish ships only `.so.2`, and the binary imports
  nothing from it. `device-libs.txt` is the phone's `ldconfig -p`.
* **Launcher** (`run.sh`, installed as `/usr/bin/photoncamera`): it sets
  `EGL_PLATFORM=wayland`, which an ssh shell lacks. It derives `GRID_UNIT_PX`
  from Silica's pixel ratio (1.5 -> density 2.25), and it `cd`s to the app
  directory because the ncnn models are read from a relative `assets`.
* **No sandbox.** The camera goes through libhybris' camera2 NDK (binder, ashmem,
  `/system`, `/vendor`). No Sailjail permission grants that yet, so the
  `.desktop` file has `Sandboxing=Disabled`.

Lipstick exports `xdg_wm_base`, so mgwl needs no changes for it.

## Running it over ssh

The display has to be on, or Lipstick sends no frame callbacks:

    dbus-send --system --type=method_call --dest=com.nokia.mce \
        /com/nokia/mce/request com.nokia.mce.request.req_display_state_on
    /usr/bin/photoncamera
