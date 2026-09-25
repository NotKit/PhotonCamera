# PhotonCamera on desktop OpenJDK (linux-port)

Build glue for running PhotonCamera on x86_64 desktop Linux on a stock OpenJDK
21 HotSpot JVM, using the [atlas](https://gitlab.com/android_translation_layer/android_translation_layer)
(atl-touch) Android framework as plain **jars** instead of dex under ART — and,
on top of that, as an arm64 Ubuntu Touch click (`click/`).

No ART, no dex2oat, no bionic_translation: the app's Java is AGP's javac output
jarred without d8, and the app's JNI libraries are rebuilt from
`app/src/main/cpp` against glibc.

It is the same construction as the Mercurygram port
(`/home/nekit/UT/mercurygram-src/linux-port/`), which is the reference for
anything this README does not answer. What is different here is the camera:
PhotonCamera is a `android.hardware.camera2` app that does all its processing in
GLES shaders, so the port stands on atlas's **camera2 support** (the work
Google Camera was brought up on, on atl-touch master today) and needs a real GL
context, where Mercurygram runs happily on CPU raster.

Bring-up workarounds and crash notes: `BRINGUP_NOTES.md`. Conventions for
working in here: `CLAUDE.md`.

## Boot architecture

`android-translation-layer-hotspot` replaces atlas's ART launcher: it links
`libjvm.so` from the JDK and boots HotSpot through the JNI invocation API, sets
the system properties atlas reads, builds the class path, then drives atlas's
normal startup sequence (`Context.createApplication` →
`ContentProvider.createContentProviders` → `Activity.createMainActivity`) and
enters the GLib main loop. Resources and assets — the shaders, the LUTs, the
ncnn models — still come from the built APK through atlas's libandroidfw path,
so only *code* loading changes.

```
atlas android-translation-layer-hotspot
        |  JNI_CreateJavaVM
        v
   libjvm.so  (OpenJDK 21 HotSpot)
        |
        |  classpath
        +--> out/atlas/api-impl.jar        atlas Android framework (javac, pre-dex)
        +--> out/shim.jar                  libcore/dalvik classes HotSpot lacks
        +--> out/classpath/*.jar           PhotonCamera + androidx deps
        |
        |  System.loadLibrary from out/lib (java.library.path)
        +--> libtranslation_layer_main.so  atlas natives: Skia, GLFW, libandroidfw, camera
        +--> libdngCreator.so              tinydng writer            (glibc)
        +--> liballocator.so               off-heap buffers          (glibc)
        +--> libflacRecorder.so            audio notes               (glibc)
        +--> libportshim.so               libcore native helpers    (glibc)
        +--> libcamera2native.so           native-engine.cpp         (glibc)
        +--> libncnnMl.so                  FlowNet + KernelNet, ncnn  (glibc)
        |
        v
   GLib main loop  ->  ATLWindow (GLFW + Skia)  +  camera backend
```

## Layout

| Path | Contents |
| --- | --- |
| `env.sh` | shared environment (`ATLAS_DIR`, `ATLAS_BUILDDIR`, `ATLAS_OUT`, `JAVA_HOME`, `PORT_OUT`, `PORT_LIB_OUT`, camera defaults); source it, don't run it |
| `build-all.sh` | every build step below, in dependency order — the one-command build |
| `check-env.sh` | toolchain smoke test; exits 0 when the environment is usable |
| `build-atlas.sh` | builds atlas from `$ATLAS_DIR` into `out/atlas-build/`, installs `out/atlas/` |
| `build-classpath.sh` | builds the HotSpot class path and the APK from the Android sources |
| `native/` | the glibc build of `app/src/main/cpp` (`CMakeLists.txt` is an overlay, not an include) |
| `native/build_ncnn_linux.sh` | ncnn from source, the one dependency this repository ships only as an Android prebuilt |
| `build-shim.sh`, `shim/` | the libcore/dalvik compat shim and its JNI back end; see `shim/README.md` |
| `build-image.sh` | the GraalVM `native-image` vehicle: one `.so` with the framework, the shim and the app compiled ahead of time |
| `trace-metadata.sh`, `image/` | the image's inputs — the traced reflection/JNI metadata (`image/ni-config`, tracked), the generator for what a trace cannot see, and the build-time class-initialisation list |
| `gradle/` | Gradle glue applied only under `-PlinuxPort` (the `jarForLinuxPort` task) |
| `tools/` | small Java helpers the checks run |
| `launcher/display.sh` | the Xvfb/screenshot helper the run scripts share |
| `run.sh` | runs PhotonCamera on the launcher; `--seconds N` also checks the log and takes a screenshot |
| `check-native-libs.sh` | loads the app's JNI libraries inside the launcher and calls into them |
| `click/` | the arm64 Ubuntu Touch click: the clickable builder, the device launcher and the ssh driver (`click/README.md`) |
| `out/` | all build products and run logs — gitignored, safe to delete |

## Prerequisites

- OpenJDK 21 (`openjdk-21-jdk-headless`). `env.sh` uses
  `/usr/lib/jvm/java-21-openjdk-amd64` unless `JAVA_HOME`/`JAVA_21_HOME` says
  otherwise; the toolchain is pinned so every step sees the same runtime.
- The Android SDK AGP compiles against (`ANDROID_HOME`, platform 36 here). The
  NDK is never invoked: `-PlinuxPort` turns `externalNativeBuild` off.
- Host toolchain: `cc`, `c++`, `cmake`, `ninja`, `pkg-config`, `unzip`, plus
  `meson` and `gn` + `clang` for atlas and its skia subproject.
- An atlas checkout. `env.sh` picks up
  `../atl-touch` when `linux-port/atlas` does not exist; `build-all.sh`
  clones `$ATLAS_URL` at `$ATLAS_BRANCH` otherwise. atlas's own dependencies
  (`art-standalone`, GTK4, GLFW, the AOSP support libraries in
  `/usr/local/lib/art`) must already be installed.
- A Wayland or X11 session with working GL. Every processing node is a shader,
  so unlike the Mercurygram port this one cannot fall back to CPU raster for
  anything but a boot smoke test.
- `libarchive` development files, for the `libarchive-jni.so` stub
  (`BRINGUP_NOTES.md`). Without them `DngCreator`'s zipped-DNG path fails at
  runtime and nothing else does.
- Network access on the first build: `native/build_ncnn_linux.sh` fetches ncnn
  (~1 min to build, CPU-only), and the overlay downloads the four header-only
  deps the Android CMakeLists downloads.
- `libomp-dev` is optional but wanted: without it clang has no OpenMP runtime and
  ncnn inference runs single-threaded.
- `check-env.sh` verifies all of the above except the run-time extras.
- For the click only: `clickable` 8.3+ and a working docker or podman. Nothing
  else — the cross toolchain, the target sysroot and the container are
  clickable's, and atlas's expensive dependencies come prebuilt from the ATL SDK
  (`click/README.md`).

## Usage

```sh
linux-port/build-all.sh      # build everything
linux-port/run.sh            # start PhotonCamera
linux-port/run.sh --seconds 20 --fresh --require-preview # verify synthetic preview
linux-port/click/make-click.sh --both  # the two arm64 Ubuntu Touch clicks
```

```sh
source linux-port/env.sh          # environment for any manual step
linux-port/build-all.sh --list    # the steps
linux-port/build-all.sh --clean   # rebuild everything from scratch
linux-port/build-all.sh --from atlas   # resume after a failed step
linux-port/build-all.sh --only natives # just one step
PORT_APP_REV=fdce2d2f linux-port/build-all.sh # last compilable app revision
```

Order matters in two places: the framework before the shim, because the shim
compiles against `api-impl.jar`; and the framework before the natives, because
`libncnnMl.so` links atlas's `libandroid.so.0` for the `AAssetManager` calls
that read the ML models out of the apk.

The one long step is atlas: a fresh `out/atlas-build/` builds skia from scratch.
When the atlas checkout already has a builddir, point at it —

```sh
ATLAS_BUILDDIR=/home/nekit/UT/atl-touch/builddir linux-port/build-atlas.sh
```

— which is how the framework install was verified. `build-atlas.sh` reuses a
builddir it did not configure instead of wiping it, but **never pass `--clean`
with a borrowed builddir**: that deletes it.

## The ahead-of-time vehicle

The same app again, with the Java compiled ahead of time by GraalVM
`native-image` instead of loaded, parsed, verified and JIT-ed at run time. The
framework, the shim and the app become one shared library that exports the JNI
Invocation API, and atlas's `android-translation-layer-image` creates its VM
from it by `dlopen` instead of linking `libjvm.so`. On the sibling Fenix port
this was 1.7–2.3x off first paint.

```sh
linux-port/trace-metadata.sh          # once: record what the app reaches by name
linux-port/build-image.sh --stub      # ~30 s: prove the toolchain, none of the app
linux-port/build-image.sh             # the real image
linux-port/run.sh --image --seconds 25
```

Three things about it are not optional, and each cost somebody a week to learn:

* **`native-image` cannot cross-compile.** The image is always for the machine
  that built it. Every input is architecture-neutral — jars and JSON — so an
  arm64 image is `build-image.sh` on an arm64 machine, which is what the CI
  workflow rents. Do not try to emulate it.
* **The metadata has to be traced, and it is committed.** A closed world has no
  class loader: anything reached by name — every View in a layout, every
  `GetMethodID` atlas does on a concrete activity class — must be registered or
  it is a `NoSuchMethodError` at the moment that screen opens.
  `image/ni-config/` is the agent's record of real runs, and it is the one
  generated tree this port tracks, because the machine that builds the arm64
  image cannot run the app. `image/gen-reflect-config.py` covers the two
  families a trace structurally cannot see.
* **A trace only registers the arms it took.** One boot is not every path, which
  is why `trace-metadata.sh` drives the JNI check and the DNG pipeline as well,
  and why `image/extra-config/` exists for what an image *run* proves is still
  missing.

`.github/workflows/linux-port-click.yml` builds both vehicles and publishes them
as `-aot` and `-cds` clicks: the app's jars on an x86_64 runner, the image on an
`ubuntu-24.04-arm` one, the cross build and the packaging back on x86_64.

## The camera

atlas has three camera backends and `env.sh` defaults to the one that exists on
a desktop:

| `ATL_CAMERA_BACKEND` | what it is | what PhotonCamera gets |
| --- | --- | --- |
| `gst` (desktop default) | one synthetic camera2 FULL device over a GStreamer source (`ATL_CAMERA_GST_SRC`, e.g. `v4l2src`) | preview, YUV and JPEG; **no RAW sensor stream**, so no capture pipeline |
| `camera2ndk` (device) | the phone's own Android camera2 stack through libhybris | the real thing — this is what the click targets |
| `hybris` | Camera1 only | nothing; PhotonCamera is camera2-only |

So the desktop loop is for the UI, the settings, the gallery and the offline
processing path; pictures are a device story. `$ATLAS_DIR/GCAM_HANDOVER.md` and
`CAMERA2_BRINGUP.md` are the state of that half — Google Camera, another
camera2 + HDR+ app, takes and saves pictures on a pixel9 through this framework.

## Where it stands

The JNI check passes, and `run.sh --seconds 20 --fresh --require-preview` boots
through the camera activity and shows the synthetic GStreamer preview under
Xvfb. Ganesh renders the UI on llvmpipe, while an EGL pbuffer feeds the app's
`GLSurfaceView` into the scene. The class path and APK for that run were built
from `fdce2d2f`, because current `dev` still fails Java compilation on the
three uncommitted classes
listed in `BRINGUP_NOTES.md`. Set `PORT_APP_REV=fdce2d2f` to rebuild those app
artifacts from an isolated source export under `out/`.

The desktop `gst` camera supplies no RAW stream, so the preview proves neither
capture nor image processing. `run.sh --no-gpu` passes as a separate UI-only
smoke test. The arm64 click remains to be built and tested on a device.
