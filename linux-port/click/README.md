# The Ubuntu Touch click (arm64)

Packages what `linux-port/` builds — PhotonCamera on OpenJDK 21 with atlas's
framework classes as jars — as an arm64 click for `ubuntu-touch-24.04-1.x`.

    linux-port/build-all.sh            # the x86_64 half, first (see ../README.md)
    linux-port/click/make-click.sh     # -> linux-port/out/click/**/*.click

`make-click.sh` stages the prebuilt inputs and then runs
`clickable build --arch arm64`. To iterate on packaging alone,
`clickable build --arch arm64 --skip-review` from the repository root does the
same thing without re-staging.

`ATL_CAMERA_BACKEND=camera2ndk` is the point of the whole exercise: on the phone
that is the device's own Android camera2 stack through libhybris, and it is the
only backend that produces real sensor frames. The desktop `gst` backend exists
for the UI loop, not for pictures.

## What is built where, and why

The port has an arch-independent half and an arm64 half, and only the second one
has to be cross-compiled:

| Piece | Built | By |
| --- | --- | --- |
| app jars, `app.apk` | host, x86_64 | `build-classpath.sh` |
| atlas: `api-impl.jar`, the natives, the HotSpot launcher | in the container, arm64 | `build-atlas.sh` |
| `shim.jar` + `libportshim.so` | in the container (the `.so` is arm64) | `build-shim.sh` |
| ncnn, and the app's five JNI libraries | in the container, arm64 | `native/build_*_linux.sh` |
| `libarchive-jni.so` | in the container: the same stub as on the desktop | `native/CMakeLists.txt` |
| art support libs, ART's boot jars, `dx`, the bionic stubs, GLFW, `libskia.so` | outside, prebuilt | `stage-prebuilt.sh` |

The port's own scripts do all of it. `PORT_ARCH=arm64` (see `env.sh`) is the
whole switch: it picks the `aarch64-linux-gnu-*` toolchain, the arm64 JDK for
`jni.h`/`libjvm.so`, and it turns off the checks that can only run natively.

`build.sh` is a clickable `builder: custom` script and lives here; the
`clickable.yaml` that points at it is at the repository root, because the build
container only mounts the project root and the build needs `app/src/main/cpp/**`
as well as `linux-port/**`.

## Why atlas is compiled here

The sibling Mercurygram click takes the whole framework prebuilt from the ATL
SDK and never compiles atlas at all. This one cannot: the SDK is built from
atl-touch **master**, and PhotonCamera stands on the **camera2 branch**
(`ralph/camera2-gcam`), which carries the camera2, EGL and `SurfaceView` fixes
the app boots through — see `../BRINGUP_NOTES.md`.

So the SDK is used for that build's *inputs* rather than its output: the
art_standalone support libraries and boot jars, `dx`, the bionic linker stubs,
GLFW 3.4 and a `libskia.so` with skia's headers. None of those cross-compile
from atlas's meson, and art_standalone has no cross build at all — which is what
would otherwise make this a multi-hour emulated build.

`click/atl-sdk.tag` pins the SDK release; `ATL_SDK_TAG` overrides it.

## The prebuilt inputs

`stage-prebuilt.sh` fills `linux-port/out/click-prebuilt/` with what the
container cannot produce, because it cannot see outside this repository.
`PROVENANCE.md` in that directory records exactly what came from where.

That includes the atlas sources themselves (`atlas-src/`), rsynced from
`$ATLAS_DIR` minus its git dir, the shared skia checkout and any builddir. The
**working tree**, not `git archive HEAD`: a bring-up session's uncommitted
framework fixes are exactly what a click gets built to test. The branch and rev
travel in `.port-atlas-rev`, which `build-atlas.sh` reads for its provenance
when there is no git to ask.

## Layout of the click

    lib/        every native object, flat: the launcher, atlas's natives,
                libskia, the app's five JNI libraries, libarchive-jni,
                libportshim, the art support libs, and each library Ubuntu Touch
                does not ship (GLFW, libportal, libswscale, maliit-glib,
                content-hub-glib). One directory, so the natives' $ORIGIN/
                RUNPATH resolves all of it on the device.
    atlas/      api-impl.jar, framework-res.apk, system/etc/fonts.xml, the fonts
    classpath/  shim.jar and the app jars
    jvm/        a jlink image of the arm64 OpenJDK 21, not a copy of the JDK
    app.apk     resources, assets (the shaders and the ncnn models) and the manifest
    run.sh      the device launcher, mirroring linux-port/run.sh

`device-libs.txt` is the device's own `ldconfig -p`. `build.sh` bundles every
`DT_NEEDED` that is not in it, transitively, and fails the build if something is
neither on the device nor in the container — that is what stops "it linked on
the host" from turning into a silent load failure on the phone.

## The bundled JVM

`build.sh` jlinks `jvm/` from the arm64 `jmods` instead of copying the JDK.
jlink is architecture-neutral, so the host's jlink builds the arm64 image — the
cross build stays a cross build. This needs `openjdk-21-jdk-headless:arm64` in
the container; the jre package has no `jmods` and the build stops with that
message.

The module set is `PORT_JVM_MODULES` in `build.sh`. `jdeps` finds most of it,
and the comment there has the command to re-derive it when a dependency is
added; the rest (locales, charsets, EC for TLS, the JDWP agent) is what static
analysis cannot see. A missing module is not a build error — it is a
`NoClassDefFoundError` on whatever path first needs it, so keep the set
generous.

A jlink image also writes real files where Debian's JDK is a tree of symlinks
into `/etc/java-21-openjdk` and `/etc/ssl/certs`, neither of which exists on the
device.

### AppCDS

The click ships a **base** CDS archive and creates the **app** archive on the
device, the same split the sibling Mercurygram click uses.

`build.sh` dumps the base one after jlink (`-Xshare:dump` writes
`jvm/lib/server/classes.jsa`, exactly where the runtime looks when no
`-XX:SharedArchiveFile` is given). A CDS archive is architecture-specific, so
that has to run the click's own arm64 `java`: native on an arm64 builder,
otherwise `qemu-user-static` (hence its entry in `dependencies_host`). It is not
fatal if it fails — the click just starts slower — but it is load-bearing for
the app archive: a dynamic archive layers on a base one, and without a base
HotSpot refuses the flag and says so only in its cds log.

`run.sh` asks for the app archive with `-XX:+AutoCreateSharedArchive
-XX:SharedArchiveFile=$XDG_CACHE_HOME/photoncamera-jvm.nekit/app.jsa`, which the
VM writes at exit — so it lives in the cache directory, not in the read-only
package. Two traps:

* HotSpot records each class-path entry's **size and mtime**; a mismatch is a
  warning to the cds log and a silent fallback. `run.sh` fingerprints
  `classpath/*.jar` plus `atlas/api-impl.jar` the same way and forces a rewrite
  when it differs — which is what makes a reinstall pick up a fresh archive.
* the archive is written mode 444, so a stale one has to be **removed**, not
  overwritten, or every later run falls back for ever.

`PHOTONCAMERA_CDS=off` disables both.
`$XDG_CACHE_HOME/photoncamera-jvm.nekit/cds.log` holds the warnings.

## Driving it on a device

`device.sh` runs, stops, screenshots and logs the installed click over ssh
(`$PORT_DEVICE` picks the host), and `device.sh pull` fetches the DNGs and JPEGs
a capture wrote. An app started from ssh needs `GRID_UNIT_PX` copied out of
Lomiri's environment or it lays out at density 1; `device.sh run` does that.

## Camera permissions

`photoncamera-jvm.apparmor` is `unconfined`: atlas needs the camera, the
microphone and the whole filesystem path the pipeline writes DNGs to, and the
confined templates do not cover a translation layer.

## click-review

`make-click.sh` passes `--skip-review`, because the package fails two checks by
design, both shared with the Mercurygram click:

- `security:template_valid: 'unconfined' not allowed` — a JVM needs far more
  than the click templates grant. This is a NEEDS REVIEW finding: it blocks
  OpenStore submission, not a local install.
- `lint:hardcoded_paths: '/opt/click.ubuntu.com/' in run.sh` — deliberate. The
  launcher prefers the version-independent `current` symlink and only falls back
  to its own directory.

`clickable review --arch arm64` reproduces both.

## Status

Runs on a device with a live viewfinder: the UI draws through Ganesh on the
phone's GPU and `camera2ndk` streams real sensor frames (RAW_SENSOR 4096x3072
plus a YUV preview stream). This needs a Halium rootfs built from
generic_arm64 halium-14.0 **≥ 529**; an older one denies `openCamera`. A
capture is still unproven, because on titan2's square display the bottom bar —
and with it the shutter button — collapses to zero height. See "First device
run" in `../BRINGUP_NOTES.md`.

## Checks worth running on the device

    # nothing unresolved, from the click's own directory
    cd /opt/click.ubuntu.com/photoncamera-jvm.nekit/current/lib
    for f in *.so* android-translation-layer-hotspot; do ldd "$f" | grep -H 'not found' && echo "  ^ $f"; done

    # the app's own log, which is where a fatal exception ends up
    tail -f /tmp/photoncamera-jvm.log
    ls ~/.local/share/photoncamera-jvm.nekit/app.apk_/files/
