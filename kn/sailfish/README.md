# The SailfishOS RPM (aarch64)

The same Kotlin/Native binary as the Ubuntu Touch click, packaged as an RPM.
Tested on the Jolla Phone (2026), Sailfish OS 5.3, Android 16 vendor.

    ARCH=arm64 kn/scripts/build-kn.sh -PwithApp   # the binary, as for the click
    kn/sailfish/build.sh                          # -> kn/out/sailfish/RPMS/aarch64/

After changes under `app/src/main/java`, run `kn/convert.sh` before building.
`build-kn.sh` uses the generated `kn/gen` tree but does not refresh it.
Set `PC_ARM_SYSROOT` for both build commands if the default arm64 sysroot lacks a
development library such as `libpng.so`.

    scp kn/out/sailfish/RPMS/aarch64/photoncamera-*.rpm defaultuser@<phone>:
    ssh defaultuser@<phone> 'devel-su pkcon install-local photoncamera-*.rpm'

`build.sh` runs `rpmbuild` in the Sailfish Platform SDK's sb2 target
(`/srv/sailfishos/sdks/sfossdk/mer-sdk-chroot`, target `SailfishOS-latest-aarch64`;
`SFOS_SDK` and `SFOS_TARGET` override them).

CI builds the click once, then packages its staged binary and resources:

    PC_SFOS_BIN_DIR="$PWD/kn/out/click/install" PC_SFOS_RPMBUILD=host kn/sailfish/build.sh

Host packaging needs `rpm`, `patchelf`, `python3`, and ImageMagick. It does not
need the Sailfish SDK or compile any code. Both packages are uploaded by
`.github/workflows/kn-click.yml` and attached to the same test release.

## What differs from Ubuntu Touch

* **TLS alignment.** Sailfish's libhybris reads bionic's TLS slots at `tp+16`.
  With glibc on aarch64 that is where the executable's own TLS block starts, so
  the first EGL call faulted inside the hybris linker. `host/mgwl.c` aligns the
  block to 256, which moves it to `tp+256`. `build.sh` refuses a binary without it.
* **Bundled library.** `libmaliit-glib.so.0`, as on UT. The unused
  `libcrypt.so.1` dependency is removed from the staged RPM binary with
  `patchelf`; packaging refuses removal if it imports any of the library's
  symbols or symbol versions. The original build and click are unchanged.
  `device-libs.txt` is the phone's `ldconfig -p`.
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
