# Bring-up notes

Every deviation from the Android build, and every wall the port hit, recorded as
it was found. This file is the memory between sessions.

## `dev` does not compile (2026-09-06)

`0a5ea3df` (and its parent `f07d5e9b`, "Highlights reconstruction, LLF fix,
demosaicing fix") references three classes that exist in no branch of this
repository:

```
PostPipeline.java:569   new ModernInitial()
PostPipeline.java:574   new LocalLaplacian2()
Bayer2Float.java:68     OpposedGL.compute(...)
TunableRegistry.java:21 postpipeline.LocalLaplacian2.class
```

`git log --all -- '*OpposedGL*'` finds nothing, so the files were simply never
committed. `:app:compileDebugJavaWithJavac` fails with six `cannot find symbol`
errors, the port's or Android Studio's build alike.

`fdce2d2f` ("Update merging") is the last commit that compiles, and the Gradle
half of the port was verified against it in a worktree: 104 classpath jars, a
54 MB `app.apk` with the shaders and the ncnn models in it, and every class
`build-classpath.sh` names resolving. Upstream has to fix `dev`.

Still true on 2026-09-13: `:app:compileDebugJavaWithJavac` fails with the same
six missing-symbol errors. `PORT_APP_REV=fdce2d2f build-classpath.sh` now
rebuilds the known-good class path from an isolated source export under `out/`;
it does not change the working tree or the app's Java sources.

## The HotSpot launcher is missing `apk_split_paths` (2026-09-06)

The first attempt to run anything inside the launcher dies before any Java runs:

```
android-translation-layer-hotspot: symbol lookup error:
libtranslation_layer_main.so: undefined symbol: apk_split_paths
```

`libtranslation_layer_main.so` imports its executable's globals, and the camera2
branch added one: `7b85641d` ("camera2, and Google Camera on a Pixel HAL") gave
`android_content_Context.c` a `native_get_split_apk_paths`, backed by
`char **apk_split_paths` defined in `src/main-executable/main.c` — the **ART**
launcher. `src/main-executable-hotspot/main.c` came from master and defines no
such thing, so every JVM run crashes at load.

The fix is in atlas, on the atlas branch, not here: define the global in the
HotSpot launcher. `Java_android_content_Context_native_1get_1split_1apk_1paths`
null-checks it (`while (apk_split_paths && apk_split_paths[n])`), so
`char **apk_split_paths = NULL;` is enough for a single-APK run; wiring it to a
real split set is a separate story.

`build-atlas.sh` now derives the import list from the library instead of naming
symbols, and fails the build on a gap like this — a weak undefined symbol
(`atl_im_backend_maliit`) is skipped, because the loader is allowed to leave
those unresolved. Fixed on the atlas camera2 branch by `3906437f` (HotSpot
single-APK runs leave the new global null). `check-native-libs.sh` and `run.sh`
now pass their boot checks.

## First HotSpot boot (2026-09-13)

The first launcher run after the symbol fix reached `Context` and failed on
`org.xmlpull.v1.XmlPullParserException`. The shim now carries the XML Pull v1
API and a StAX-backed `KXmlParser`, then the libcore classes each subsequent
run demanded: `NativeAllocationRegistry`, `Libcore`/`Os`/`Posix`,
`android.system.Os` and its value types, `BlockGuard`, `IoUtils`, `VMRuntime`,
and `CloseGuard`. These came from the sibling Mercurygram port's shim; see
`shim/README.md` for the class list and runtime differences. `build-shim.sh`
builds `libportshim.so` into `out/lib/` so native peers are actually freed.
`tools/ShimCheck.java` exercises the JNI free function, thread ID and XML
parser; `check-native-libs.sh` passes inside the launcher.

Camera layout inflation then called
`ViewConfiguration.getScaledOverscrollDistance()`, absent from atlas. The
camera2 branch now supplies it (`c50e1215`), using the same 10-pixel default
its `AbsListView` used internally. That is a framework API fix, not a shim.

The first GPU run crashed in Skia's `GrGLExtensions::init` on X11/GLX. A
standalone GLFW + Skia probe reproduced it: `glfwGetProcAddress` returned EGL
entry points even with no current EGL display, and Skia dereferenced a bogus
`eglQueryString` result. The camera2 branch now omits those entry points in
that situation (`466eddb1`); the standalone probe and a 15-second Ganesh app
run both pass on llvmpipe. The fix leaves EGL lookups intact when an EGL display
is current, as on the device.

The next run reached `CameraActivity`, but its `GLSurfaceView` logged
`EGL_BAD_NATIVE_WINDOW`: X11 has no `wl_egl_window` behind a `SurfaceView`.
atlas now gives Java EGL producers targeting a `SurfaceView` a pbuffer
(`ef7835bc`) and
reads each swapped frame back through the existing `ANativeWindow` to
`Surface.postFrame`. The
`SurfaceView` then blits it into the Skia scene. The Wayland layer path remains
the direct EGL window path. This is the same kind of fallback atlas already
uses for GL producers writing to a `SurfaceTexture`, but the destination is a
`SurfaceView` instead of its texture mailbox.

Two camera2 API gaps appeared once the GStreamer session configured
(`033664a0`):
`CaptureRequest.Builder.setPhysicalCameraKey` and
`CaptureResult.SENSOR_NEUTRAL_COLOR_POINT`. The result key now reads the
existing `android.sensor.neutralColorPoint` metadata tag. Per-physical request
settings are not implemented by atlas; the Builder method throws
`UnsupportedOperationException`, which PhotonCamera catches while applying
optional vendor tags. It must not silently write one sensor's value into the
logical camera's global request.

`run.sh --seconds 20 --fresh --require-preview` now passes: the log records a
first GStreamer frame and a first GL frame posted to the `SurfaceView`, and the
screenshot shows the synthetic colour bars. Two reflective lookups in
`NativeEngine` print `Exception in thread` through JNI's `ExceptionDescribe`
and are then caught by the app; `run.sh` checks the actual fatal-exception
markers instead. The `gst` backend still advertises no RAW stream, so this is
preview evidence, not capture or image-processing evidence. `--no-gpu` remains
a separate UI-only smoke run.

## The app's JNI libraries under glibc

`native/CMakeLists.txt` mirrors `app/src/main/cpp/CMakeLists.txt` for the four
small libraries. Deviations:

- **`find_package(JNI)` fails on a headless JDK** — `Could NOT find JNI (missing:
  AWT)`, because `openjdk-21-jdk-headless` ships no `libjawt.so`. Nothing here
  needs AWT, so the overlay takes `jni.h` from `$JAVA_HOME/include` directly.
- **`uint8_t` and friends are not declared.** The NDK's libc++ pulls `<cstdint>`
  in transitively; libstdc++ does not, and `allocator.cpp` uses `uint8_t` /
  `uint16_t` without including anything.
- **`AttachCurrentThread(&env, ...)` does not compile.** Android types the first
  parameter `JNIEnv**`, the JDK types it `void**`, and C++ rejects the
  conversion (`native-engine.cpp:29`). Both are fixed in
  `native/include/port_compat.hpp`, force-included into every C++ TU — never by
  editing `app/src/main/cpp/**`.
- **liblog.** atlas's `libandroid.so.0` exports the NDK surface but not
  `__android_log_*`, so `stubs/android_log.c` provides them on stderr, the way
  the Mercurygram port does. `PHOTONCAMERA_LOG_LEVEL` raises the threshold.
- **The header-only deps are fetched outside the app tree.** The Android
  CMakeLists downloads `tiny_dng_writer.h`, `technicallyflac.h`, `archive.h` and
  `archive_entry.h` into `app/src/main/cpp/deps/`; the overlay puts them in
  `out/native/app-jni/deps` instead, so a port build never dirties the app tree.

## `libarchive-jni.so`

`dngCreator.cpp` does not link libarchive: it `dlopen`s `"libarchive-jni.so"` —
the library the `me.zhanghai.android.libarchive` AAR ships — and resolves the
`archive_*` entry points with `dlsym`. On the desktop that name does not exist,
so `native/stubs/libarchive_jni.c` is an empty translation unit linked against
the system libarchive: `dlsym` searches a handle's dependency chain, so every
symbol resolves through the `DT_NEEDED`. It needs `-Wl,--no-as-needed`, or the
link drops the only dependency that makes it work.

The apk does carry the AAR's own `lib/arm64-v8a/libarchive-jni.so`, and an early
plan for the click was to lift that out instead of building a stub. It cannot
be: it is a **bionic** object (`NEEDED liblog.so, libm.so, libdl.so, libc.so`,
"for Android 21"), and this vehicle has no bionic at all — nothing here loads
`bionic_translation`, whose libraries are staged only to satisfy atlas's
configure-time `cc.find_library`. So the click builds the same stub, and
`libarchive-dev` is a target dependency in `clickable.yaml`. Ubuntu Touch ships
no libarchive either, so `build.sh` bundles `libarchive.so.13` and its closure.

That stub is also why the cross build has to pass `PKG_CONFIG_EXECUTABLE`:
`native/CMakeLists.txt` probes libarchive with `pkg_check_modules`, and CMake
would otherwise answer with the *host's* libarchive and link an x86_64 library
into an arm64 build.

## ncnn, built from source

`app/src/main/cpp/ncnn/` carries ncnn (Tencent's inference framework, BSD-3) as
an Android per-ABI **static** library only: `arm64-v8a` built with Vulkan (91 MB
`libncnn.a` plus glslang), `armeabi-v7a` CPU-only. Nothing to link on x86_64, so
`native/build_ncnn_linux.sh` builds it: CPU-only, static, into
`out/native/ncnn/{include,lib}`, about a minute. `native/CMakeLists.txt` then
builds the real `libncnnMl.so` (17 MB) instead of the stub it falls back to.

Three things had to be right, and each one is a wall on its own:

1. **No release tag works.** The custom FlowNet layers index a 4D `ncnn::Mat`
   (`Mat::n`, `Mat::nstep`), which no tag through `20260526` has — the vendored
   Android library is itself a master build (`1.0.20260817`). The script pins a
   master commit (`NCNN_REF`, default `6a1bf000`) and fetches it by sha, which is
   why it uses `git fetch --depth 1` rather than `git clone --branch`.
2. **`__ANDROID_API__` is what exposes the asset API.** `ncnnMl.cpp` loads the
   models with `Net::load_param(AAssetManager*, path)`, and ncnn compiles those
   overloads (plus `DataReaderFromAndroidAsset`) only under
   `NCNN_PLATFORM_API && __ANDROID_API__ >= 9`. Both the ncnn build and the
   `ncnnMl` target define it at 9: that turns on exactly the asset half and
   `NCNN_LOGE` through liblog, and nothing else — `AHardwareBuffer` needs 26, and
   `__ANDROID__` itself stays undefined, so the allocator and the CPU probing
   keep their POSIX paths. The declarations come from `native/include/android/`
   (`asset_manager.h`, `asset_manager_jni.h`, `bitmap.h`, `log.h`), the
   implementations from atlas's `libandroid.so.0`, which exports all five
   `AAsset_*` calls ncnn uses. That is why **atlas has to be built before the
   natives**, and why `build-all.sh` puts it there.
3. **ncnn's `mat.h` includes `<android/bitmap.h>` and `<jni.h>`** under the same
   guard, so the ncnn build needs the port's include dir *and* the JDK's.

OpenMP: Debian has no `libomp` for clang here, so `find_package(OpenMP)` fails,
ncnn is built with `NCNN_OPENMP=OFF` and `ncnnMl.cpp`'s own `omp_*` calls compile
out behind `#ifdef _OPENMP`. Correct, but single-threaded — install `libomp-dev`
and rebuild both for threads. The Android build instead links the NDK's static
`libomp.a`, which has no equivalent here.

`--vulkan` is wired but unused: it pulls ncnn's glslang submodule and would also
need the three `*_vulkan.cpp` FlowNet layers and the SPIR-V headers
`flownet/gen_shader_header.py` generates. `flownet_register.h` picks the CPU
creators when `NCNN_VULKAN` is 0, so the CPU build is complete on its own.
KernelNet only asks for Vulkan under `KN_GPU=1`, and FlowNet turns it off when
ncnn reports no GPU.

`libncnnMl.so` keeps CMake's build RPATH, so `ldd` resolves `libandroid.so.0`
from `out/atlas/` on this machine. At runtime it is `LD_LIBRARY_PATH` that
matters (`run.sh` sets it), and the click will stage the libraries together.

## Framework gaps to expect

Found by reading, not yet by running — each needs a real failure before it is
worth fixing:

- **GLES 3.1.** Four files use `android.opengl.GLES31` (compute shaders);
  atlas's api-impl has `GLES10`, `GLES11Ext`, `GLES20` and `GLES30` only. That
  is an atlas story, not a shim one.
- **`NativeEngine`'s hidden-API bypass.** `native-engine.cpp` `dlopen`s
  `libart.so` and pokes `art::hiddenapi`, then falls back to
  `FindClass("dalvik/system/VMRuntime")`. The shim supplies `VMRuntime`, but
  its hidden-API bypass is a no-op on HotSpot. `check-native-libs.sh` confirms
  the native path returns without aborting.
- **RAW on the desktop.** The `gst` backend synthesises a camera2 FULL device
  with YUV, JPEG and PRIVATE streams. PhotonCamera declares
  `android.hardware.camera2.capability.raw` and captures RAW_SENSOR, so a
  desktop run cannot take a picture; that is a device path
  (`ATL_CAMERA_BACKEND=camera2ndk`) or, eventually, a synthetic RAW source in
  atlas's gst backend.

## The arm64 click (2026-09-14)

The click cross-compiles in a clickable container, and the three things that
made it different from the sibling Mercurygram click are all consequences of
the camera2 branch:

- **atlas cannot be taken prebuilt.** The ATL SDK is published per atl-touch
  commit, but from **master**: `sdk-040d634` is `040d634` on master, and the
  five camera2/EGL/SurfaceView commits this port boots through are on
  `ralph/camera2-gcam`. So `click/build.sh` compiles the framework and uses the
  SDK only for that build's inputs — the art_standalone support libraries and
  boot jars, `dx`, the bionic stubs, GLFW and `libskia.so` with skia's headers.
  None of those cross-compile from atlas's meson, and art_standalone has no
  cross build at all.
- **The atlas sources have to be inside the repository.** clickable mounts the
  project root and nothing else, and `$ATLAS_DIR` is a worktree next door. So
  `click/stage-prebuilt.sh` rsyncs the working tree into
  `out/click-prebuilt/atlas-src` — the working tree and not `git archive HEAD`,
  because a bring-up session's uncommitted framework fixes are exactly what a
  click is built to test. Its `.git` is a gitfile pointing outside and does not
  travel, so the branch and rev are written to `.port-atlas-rev` and
  `build-atlas.sh` reads them for its provenance.
- **libarchive-jni.so is still a stub** — see above; the apk's arm64 copy is a
  bionic object.

The click build also found the same bug twice, in two scripts: **`git rev-parse
--git-dir` answers with an ancestor's repository**, and every directory under
`out/` has the PhotonCamera checkout as an ancestor.

- `native/build_ncnn_linux.sh` used it to ask whether its source directory was
  already a checkout. It was told yes, so `git remote get-url origin` returned
  PhotonCamera's origin and the fetch asked GitHub for an ncnn commit in the
  PhotonCamera repository: `fatal: remote error: upload-pack: not our ref`, with
  nothing in it to say ncnn's remote was never used. It had only ever worked
  because a real ncnn checkout already sat there from an earlier run. The test
  is `[ -d "$src/.git" ]` now, and the remote URL is set rather than assumed.
- `build-atlas.sh` used it to decide whether it could read atlas's provenance.
  For the click's source export it answered yes and the build reported
  `atlas dev 0a5ea3df` — PhotonCamera's branch and rev — while running the
  "did the build touch the atlas tree?" check against PhotonCamera's working
  tree, where it means nothing. It now requires `$ATLAS_DIR/.git` to exist in
  its own right (a file for a worktree, a directory for a clone) and otherwise
  falls back to the `.port-atlas-rev` the export carries.

Everything else the arm64 build needed was already there: `PORT_ARCH=arm64` in
`env.sh` picks the toolchain, `native/toolchain-cross.cmake` and
`native/write-meson-cross.sh` describe the target to CMake and to meson, and
every script's verification already skips what a cross build cannot run.

## Gradle

- `gradlew` is mode 644 in this repository, so the port's scripts run
  `sh gradlew`, not `./gradlew`, and never `chmod` a tracked file.
- The port's variant is `debug`: it is the only build type that does not run R8,
  which would rename the classes the launcher and the checks name.
- `app/build.gradle` bumps `version.properties` from
  `gradle.taskGraph.whenReady` when the graph has `assembleDebug`.
  `jarForLinuxPort` depends on `packageDebug` instead, so a port build leaves
  the file alone — confirmed by a clean `git status` after a full run.
- The APK legitimately carries `lib/*/libarchive-jni.so` from the AAR. Only the
  app's *own* libraries (`libdngCreator.so`, …) appearing there mean the NDK ran
  despite `-PlinuxPort`, which is what `build-classpath.sh` checks for.

## First device run (titan2, 2026-09-14)

`photoncamera-jvm.nekit_0.93_arm64.click` installs and starts. The app reaches
`CameraFragment`, atlas picks up `GRID_UNIT_PX` from Lomiri, and the GL thread
creates its surface and compiles the preview shaders — `ATLWindow: GPU rendering
(Ganesh) on Mali-G615 MC2, OpenGL ES 3.2`. The process stays up; nothing here is
fatal. Three things do not work:

- **`openCamera` was refused by the Android camera service — fixed by updating
  the Halium rootfs.** `Camera camera2ndk: openCamera('0') failed (-10012)` is
  `ACAMERA_ERROR_CAMERA_DISABLED`; logcat gave `connectDevice:2596: Camera
  disabled by device policy`. On Halium the camera service is
  `/system/bin/minimediaservice` (droidmedia links libcameraservice in — there
  is no `cameraserver` binary in the image), and
  `CameraServiceProxyWrapper::isCameraDisabled` returned true because no
  CameraServiceProxy runs without system_server. Halium's
  `0014-halium-cameraservice-do-not-deny-camera-when-proxy-u.patch` makes that
  return false; it landed in hybris-patches on 2026-09-04, so the port needs a
  generic_arm64 halium-14.0 build **≥ 529**. The device was on an Aug 29 image;
  after `/var/lib/lxc/android/android-rootfs.img` was replaced with build 537
  the camera opens and streams.
- **`Camera2ApiAutoFix` still throws before the open.** `NativeEngine` cannot `dlopen`
  `libart.so` (expected on HotSpot, see "Framework gaps"), so
  `CameraMetadata.getKeys(...)` is not found, `getCameraCharacteristicsKeys`
  returns null and `Camera2ApiAutoFix.ExposureTime` NPEs — which is what puts
  the "This device doesn't support Camera2 API." toast on screen. Independent of
  the camera service: the vendor-key fallback needs to survive a null key list.
- **`MediaPlayer.prepare` fails twice** on the shutter sounds in
  `CameraFragment.onResume`. Cosmetic.

`ldd` in the click's `lib/` reports `libjvm.so`, `libandroid.so.0` and
`libarchive.so.13` as not found; all three resolve through the
`LD_LIBRARY_PATH` `run.sh` sets, so that check is only meaningful under the
launcher.

### The bottom bar collapses on a square display

titan2's panel is 1440x1440 and the app window is 1440x1377, so
`displayAspectRatio` is 1.046. `camera_fragment.xml` anchors `layout_bottombar`
(height `0dp`) between the bottom of `dummy_reference_view` and the bottom of
the parent, and `CameraUIViewImpl` gives that dummy view a `3:4` ratio whenever
`displayAspectRatio <= 16f/9f`. At 1440 wide that is 1920 px tall against a
1377 px container, so the bottom bar resolves to zero height and the shutter
button, mode switcher and gallery thumbnail are all off-screen. The viewfinder
fills the window instead, which is why it looks correct.

The same `displayAspectRatio <= 16f/9f` branch in
`CustomBinding.adjustCameraContainer` re-anchors `camera_container` to the top
of the parent, so the preview also runs underneath the top bar.

Neither is a port bug — the layout assumes a display at least 4:3 tall, and
Android on a square screen would do the same. Fixing it means capping the dummy
ratio at the container's own aspect ratio, which is an app change, not a port
one.

## Camera and preview on oneplus11 (2026-09-14)

The app opened the selfie camera, never any other, and drew a stretched
upside-down preview. Two framework gaps and one non-bug, all checked on device
by swapping a single method in and out of `api-impl.jar`.

### `CameraMetadata.getKeys()` — the one that mattered

`Camera2ApiAutoFix.Init()` enumerates the characteristics keys through AOSP's
package-private `CameraMetadata.getKeys(Class, Class, CameraMetadata, int[],
boolean)`, reached by reflection. atlas had only the public no-arg `getKeys()`,
so the lookup returned null and `ExposureTime()` threw an NPE out of
`UpdateCameraCharacteristics` (`CaptureController.java:1665`) — before the
`runOnUiThread` block below it.

That block is where `mPreviewSize` is assigned, `setAspectRatio` and
`updatePreviewMirror` run, and `onCharacteristicsUpdated` fires. Losing it gave
`previewSize:null`, a preview at raw buffer size, no aux lens buttons, no flash
button, and a "This device doesn't support Camera2 API" toast. Fixed in atlas
(`camera2: add CameraMetadata's package-private getKeys()`); the log then reads
`previewSize:1440x1080` and the lens and flash buttons appear.

Any app that reads vendor keys will want this, so it belongs in the generator
(`tools/gen-camera2-tags.py`) rather than the generated file.

### `SurfaceHolder.lockHardwareCanvas()` kills the UI thread

`onCharacteristicsUpdated` calls `SurfaceViewOverViewfinder.clear()`, which locks
a hardware canvas. atlas had `lockCanvas()` but not the API 26
`lockHardwareCanvas()`:

```
Exception in thread "main" java.lang.NoSuchMethodError: 'android.graphics.Canvas android.view.SurfaceHolder.lockHardwareCanvas()'
    at SurfaceViewOverViewfinder.clear(SurfaceViewOverViewfinder.java:407)
    at CameraFragment$CameraEventsListenerImpl.onCharacteristicsUpdated(CameraFragment.java:1192)
    at CaptureController.lambda$UpdateCameraCharacteristics$2(CaptureController.java:1628)
    at android.os.Looper.loop(Looper.java:137)
```

A `NoSuchMethodError` is an `Error`, so it goes straight through the app's
`catch (Exception)` and out of `Looper.loop()`: the main thread dies and no UI
message is ever handled again. The camera keeps streaming into a half-drawn
window with no bottom bar and no mode selector. It runs on every camera open and
on every restart, so flip, mode and quad toggle were all dead. Fixed in atlas
(`view: add SurfaceHolder.lockHardwareCanvas()`).

This one hid behind the `getKeys()` gap: until that was fixed the NPE aborted
`UpdateCameraCharacteristics` first, so `clear()` was never reached and the
startup log looked clean.

### `LENS_FACING` is *not* inverted

Worth writing down because the values look wrong until you check which API they
belong to. camera2 and the NDK use `FRONT = 0`, `BACK = 1`
(`NdkCameraMetadataTags.h`, `CameraMetadata.LENS_FACING_*`); it is *Camera1*'s
`CameraInfo` that uses `CAMERA_FACING_BACK = 0`. The HAL byte for oneplus11's
front camera is 0 and atlas passes it through verbatim, so app labels, the aux
lens list and the mirror flag are all correct. Nothing to fix here — "correcting"
either side would swap front and back everywhere.

The device's NDK ids, fingerprinted by focal length through
`/android/system/bin/dumpsys media.camera`:

| id | facing | orientation | focal | role |
| --- | --- | --- | --- | --- |
| 0 | BACK | 90 | 5.59 | logical (phys 3,2) — app skips |
| 1 | FRONT | 270 | 3.23 | selfie |
| 2 | BACK | 90 | 5.59 | main, and what the app now starts on |
| 3 | BACK | 90 | 2.59 | ultrawide |
| 4 | BACK | 90 | 7.08 | tele |
| 5 | BACK | 90 | 5.59 | logical (phys 3,2,4) — app skips |

### Still open

The preview is upright and correctly letterboxed on the back camera. The front
camera is untested: Lomiri offers no input injection to phablet (`/dev/uinput`
is root-only and `sudo` wants a password), so the flip button cannot be tapped
over ssh. If it comes up 180° out, suspect `configureTransform` — it early-returns
on `null == mPreviewSize` (`CaptureController.java:1139`), and 180° is what its
formula gives a back camera (90 + 90) while the front wants 360 = 0.

`GLPreview.onMeasure` also computes an aspect fit and then unconditionally
overwrites it with `setMeasuredDimension(mRatioWidth, mRatioHeight)`
(`GLPreview.java:178-182`) — raw pixels, not a ratio — and `GLPreview.setTransform()`
is an empty stub. Both are app bugs, and neither is currently visible.

## HDRX processing on oneplus11 (2026-09-15)

A shutter press captured its burst and then produced nothing. Three framework
gaps, one behind the other; each was found only after the one in front of it was
fixed. There is no input injection on Lomiri, so the shutter was pressed with
atlas's own `ATL_DEBUG_TAP=<seconds>:<x>,<y>` — window pixels, and
`ATL_DUMP_HIERARCHY=all` prints the bounds to aim at.

- **`android.opengl.GLES31` did not exist.** The app's whole raw pipeline is
  compute shaders (`import static android.opengl.GLES31.*` in `GLProg`,
  `GLTexture`, `GLBuffer`), so `ApplyHdrX` died on a `NoClassDefFoundError` right
  after packing the frames. Fixed in atlas (`opengl: add GLES 3.1, and the 3.0
  calls a compute pipeline needs`) — the 3.1-only calls plus the 3.0 ones those
  imports resolve to by inheritance (`glMapBufferRange`, `glBindBufferBase`, the
  `glUniform*ui` family).
- **A heap `Buffer` was taken for a direct one.** `get_nio_buffer` treated a
  non-zero `Buffer.address` as a direct address, but the JDK stores the array
  base offset there for heap buffers — 16 on HotSpot. `ESD4D.createKernelsMap`
  uploads a `FloatBuffer.wrap(float[])`, so the Adreno driver got `0x10` as its
  pixel pointer and the JVM took a SIGSEGV at exactly that address. Fixed in
  atlas (`jni: a heap buffer is not a direct one`), which decides on `isDirect()`.
  Every GL binding shares that helper, so this was one crash away on any app
  that uploads from a heap buffer.
- **`Bitmap.copyPixelsFromBuffer` was an empty method.** With the two above
  fixed, the pipeline ran to the end and wrote a 3072x4096 JPEG in which every
  pixel was zero: the app reads its finished frame back with `glReadPixels` and
  copies it into a `Bitmap`, and that copy did nothing. The camera was not the
  problem — `ATL_CAMERA_RECORD` plus `atl-camrec.py stats` showed the RAW frames
  arriving with min 59, max 397 on a 1023 white level. Fixed in atlas
  (`graphics: implement Bitmap.copyPixelsFromBuffer()`). **Not yet confirmed on
  device**: the run meant to check it hit the pre-capture race below.

Processing takes about 12 s for a 15-frame burst, and the app does not restart
the preview until it finishes, so the viewfinder is frozen for that whole time
with no progress in the UI. It is not a hang — `kill -3` shows the main thread
in the GLib loop and the GL thread idle — but it reads as one.

### Still open after that

- **The pre-capture sequence never converges.** `W/CaptureController: Timed out
  waiting for pre-capture sequence to complete.` on every shot, and the app then
  runs `captureStillPicture()` more than once. Each call resets `mExposures` and
  clears `IMAGE_BUFFER` (`CaptureController.java:2318-2321`), so when the second
  one lands while the first burst's images are still in flight, processing throws
  `NullPointerException` out of `exposures.get(frame.getTimestamp())`
  (`HdrxProcessor.java:124`) and saves nothing. Intermittent — it depends on how
  many frames `FrameNumberSelector` asks for. The timestamps themselves are
  sound: a recording shows every buffer's timestamp present in the results.
- **A second capture in the same session is refused.** After one burst,
  `createCaptureRequest` fails with `-10006` (`ACAMERA_ERROR_CAMERA_SERVICE`),
  every queued frame comes back `failed, reason 1` (flushed), and
  `CameraDevice.onError` reports error 4. The first capture after a fresh start
  works, which is why this hides behind the first press.

Building the skia subproject from source fails on this host:

```
skcms/src/Transform_inl.h:160:12: error: use of undeclared identifier '__builtin_ia32_vcvtph2ps256'
```

The vendored skcms predates clang 22's builtin rename. Nothing in atlas is
wrong; pass the staged host build instead, which `build-atlas.sh` already
supports:

```
PORT_SKIA_PREBUILT=$PWD/linux-port/out/skia-prebuilt linux-port/build-atlas.sh
```

## Portrait desktop window (2026-09-15)

`run.sh` launched the app at the launcher's default 960x540 landscape, where
the camera UI's bottom bar overflows off-screen and the shutter is unreachable
(`ATL_DUMP_HIERARCHY` put it at x -47..48). `PORT_WINDOW_WIDTH`/`HEIGHT` in
`env.sh` (default 540x960) are now passed as `--window-width/--height`, and
`launcher/display.sh` sizes the Xvfb screen to fit. In portrait the shutter
lands centered and on-screen, at (223,773)-(318,868) on the default window.

## The camera2 metadata members camera apps reflect on (2026-09-15)

PhotonCamera overrides what the HAL reported — black level, colour transform,
white point — and camera2 offers no public way to do that, so it reflects:
`CameraCharacteristics.mProperties`, `CaptureResult.mResults` and
`CaptureRequest.mLogicalCameraSettings`, then calls `setBase()`/`set()` on the
`CameraMetadataNative` it finds. atlas held the same bags under its own names
(`metadata`, `results`, `settings`) and had no typed setters, so every override
was dropped on a `NoSuchFieldException` the app swallows — `D/CameraAPI: Failed
to set CaptureResult key` in the log and a picture rendered from unfixed
metadata. `BlackLevelPattern.mCfaOffsets` and the image plane's `mBuffer` are
reached the same way. Fixed in atlas (`camera2: name the metadata members what
AOSP names them`).

Nothing in either build references these by name, so the next rename would be
just as silent: `tools/CameraReflectionCheck.java` looks all eight up and
`build-atlas.sh` runs it over the jar it just built.

## The black JPEG was an uninitialized GLSL local (2026-09-15)

A desktop replay capture saved a healthy stacked DNG and a near-black JPEG with
one white shape at the bottom left. That shape is the watermark, rendered
correctly — which clears the whole tail of the pipeline at once: the
`RotateWatermark/addwatermark_rotate` shader ran, the watermark asset loaded,
`drawBlocksToOutput` read the tiles back, `Bitmap.copyPixelsFromBuffer` copied
them and the encoder wrote them. Crop the JPEG before concluding anything about
a readback.

Histogramming every node's output found the crossing exactly: `CorrectingFlow`
out at mean 103.3, `Sharpening` out at mean 0.0. `sharpening/lsharpening3.glsl`
declared `float sharp;` and then accumulated into it. Reading an uninitialized
local is undefined in GLSL; Adreno gives zero, the desktop gives NaN, and
`clamp(NaN + center, 0.0, 1.0)` is black. `float sharp = 0.0;` and the JPEG is
the recorded scene, mean 103.9. This is an app bug, not a port one — the fix is
correct on Android too.

The probe is a `probeNode()` call at the end of `GLBasePipeline.runAll()`'s
loop, `GLHistogram.Compute` over `node.GetProgTex()`, behind
`-Dphoton.probeNodes=1` (`run.sh ... -- -X -Dphoton.probeNodes=1`). It is not
committed — the port builds the app from the pinned `PORT_APP_REV` archive
under `out/app-source-*`, not the working tree, so it and any app-source fix
have to be applied there and re-applied whenever that tree is regenerated.
A shader can also be overridden without any rebuild by dropping it at
`out/data/app.apk_/assets/<path>`: the asset manager tries the data dir before
the apk.

## The replay backend answered a still capture with the preview's failures (2026-09-15)

A replayed still burst reached the app as `onCaptureSequenceCompleted` alone —
no `onCaptureStarted`, no `onCaptureCompleted` — so `mCaptureResult` stayed null
and processing died on `Cannot invoke CaptureResult.get(...) because "result" is
null`. The request ids were right all along.

`camera_replay.c` played the whole recorded burst segment under one live
request id. That segment opens with the events the recorded device produced
when *its* shutter was pressed: three `ATL_REC_FAILED` and five `ATL_REC_LOST`
for the preview request it abandoned. The first of those reached
`CameraCaptureSession.dispatchCaptureFailed`, which removes a one-shot sequence
from `sequences` — so every later started and result for that id found nothing
and was dropped, and the eighth one took `Burst.outstanding` to zero and
reported the sequence complete.

The backend now indexes the recording into frames (a started, the buffers
carrying its timestamp, the result with its frame number) and answers one
request with one frame, matched by the recorded request id: the requests the
trigger opened are the burst's, everything before it is the pre-roll, and the
events of a request nothing replays are dropped rather than handed to whatever
is in flight. Two things fell out of that:

- A result chunk carries no timestamp of its own, so `result_at()` was stamping
  every result with `shift` alone. The app pairs images to results by
  `SENSOR_TIMESTAMP`, so `mExposures` was keyed on a constant. The frame the
  result belongs to now supplies it.
- A frame the recording stopped inside (a started with no result) is dropped:
  the request it answered would never complete, and the burst would hang one
  frame short of `onCaptureSequenceCompleted`.

A one-shot with `CONTROL_CAPTURE_INTENT_STILL_CAPTURE` takes the burst; any
other one-shot — a pre-capture sequence is one — takes a pre-roll frame, where
it used to replay the entire burst.

With that, a desktop replay run takes eight frames, merges them and saves the
scene: the port's own end-to-end HDRX check, no phone in the loop.

## The ahead-of-time vehicle, and what the tracing agent does not see (2026-09-19)

`build-image.sh` compiles the framework, the shim and the app into one GraalVM
`native-image` shared library, which atlas's `android-translation-layer-image`
creates its VM from. The build arguments and most of the traps are the sibling
Fenix lane's work — `~/UT/firefox-atl/jvm-run/image/NOTES.md`, and the click
packaging's `image/build-image.sh` — and are not re-derived here.

Three things are this port's own.

**The tracing agent writes from a JVM shutdown hook, and nothing here shuts a VM
down.** atlas's launcher ends a `--run-class` check with a bare `exit(0)` from C
(`main.c`, `run_class_main`) and never calls `DestroyJavaVM`; `run.sh` ends a
timed run with SIGTERM and then SIGKILL. Both leave the config directory empty
while every check still exits 0 — a silent, total loss that reads as "the agent
did not load". It did: `grep 'jvm option: -agentlib'` in the run log says so.
`trace-metadata.sh` therefore adds `config-write-period-secs=3`, so what is lost
is the last few seconds of a run rather than all of it. Fixing the launcher to
destroy its VM would be better and belongs in atlas.

**The app's own JNI libraries name almost nothing.** `app/src/main/cpp` has two
`FindClass` calls in total — `dalvik/system/VMRuntime` and `java/lang/String`,
both in the hidden-API unsealing hack in `native-engine.cpp`. So the FindClass
name scan in `image/gen-reflect-config.py` is really about **atlas's** natives:
`libtranslation_layer_main.so` drives the whole framework from C, and it is
where a missed `GetMethodID` becomes a fatal `NoSuchMethodError` rather than a
caught `NoSuchMethodException`. A first scan over the build here found 69
framework and app classes plus 25 JDK names, against 399 classes reached by name
from layouts, fragments and view models.

**One atl-touch commit, two consumers.** The image is built over the
`api-impl_classes.jar` the ATL SDK publishes, because the arm64 machine that
builds it cannot compile atlas; the click compiles the framework's natives from
the sources. `click/atl-sdk.tag` names both (`env.sh`, `$ATLAS_PIN_REV`), and
`click/build.sh` refuses to package an image whose `IMAGE.txt` names a different
revision — two frameworks in one package is a `NoSuchMethodError` somewhere in
the boot, not a link error.

**The agent publishes only its first periodic write.** It creates its temp
directory *inside* the output directory, which changes that directory, so every
later write refuses with "... has been modified by another process" and is left
in a temp directory of its own. A 30 s boot published 3 JNI classes and left 98
reflection and 61 JNI classes in its last snapshot — and the published files
look like a perfectly ordinary, tiny trace. Each snapshot is complete, so
`trace-metadata.sh` takes the newest one as the run's result, traces every check
into a directory of its own, and merges them with GraalVM's own
`native-image-configure generate` — which is what the agent's own warning about
"running multiple processes" points at. `config-merge-dir` across successive
processes does not work here for a second reason: the agent's `.lock` is
released from the same shutdown hook that never runs, so the next check dies in
`Agent_OnLoad` before its VM exists.

**The capture arm needs a real GPU, and this host has no render node.** With the
replay backend and a scheduled `ATL_DEBUG_TAP` on the shutter, the run gets as
far as the pipeline's own EGL context and then dies in llvmpipe:

    SIGSEGV ... C  [libLLVM.so.21.1+0x4680514]  LLVMTypeOf+0x4

about 45 s in, right after `egl_context:` is logged for the second time. So the
committed trace covers the boot, the JNI surface and the capture up to the first
pipeline shader, and **not** the processing pipeline, the DNG writer or the JPEG
save. `--import NAME=DIR` exists for that: record the capture arm on a box with
a GPU and merge it in. kit-pc is the one here, and its framework was two weeks
stale when this was written, so the first attempt there took no picture at all.

## The first image, and it boots (2026-09-19)

| | |
|---|---|
| wall | **114 s** |
| peak RSS, whole process tree | **3.7 GB** |
| `libphotoncamera.so` | **74.8 MB** |
| class path | 106 entries (framework + shim + 104 app jars) |
| metadata | 96 reflection and 63 JNI classes traced, plus 399 name-instantiated and 94 FindClass names generated |

`android.os.Build$VERSION`, `android.os.SystemProperties` and
`android.content.res.AssetManager` all came out `RUN_TIME`, which is the gate
that matters: a build-time `SDK_INT` is constant-folded into every
`SDK_INT >= N` branch in the capture path.

`run.sh --image --seconds 30` boots to `SplashActivity` with no uncaught
exception, no `libjvm.so` mapped and the launcher reporting
`image: opened .../libphotoncamera.so`. The only reflective misses in the log
are androidx's `TypefaceCompatApi26Impl` probing for
`Typeface.createFromFamiliesWithDefault`, a hidden API atlas does not have —
caught, and identical on the HotSpot vehicle.

Not measured yet: start-up against the HotSpot vehicle, and anything past the
first frame. The walls after the metadata are the ones firefox-atl's lane
enumerated, and they are found by running the image rather than by reading it.

Also fixed while getting here, and worth repeating because it is silent:
`init_bt=$(grep ... | paste ...)` in `build-image.sh` exited the whole script
under `set -e -o pipefail` the moment `initialize-at-build-time.txt` held
nothing but comments — no image, no message, exit 0 through a pipe.

## Rebased onto upstream `dev` ba55cec5 (2026-09-20)

The branch had been sitting on `9efb24a4` while upstream took 136 commits: the
M3E UI rework, HEIC, the fp16 raw path, `.mcraw` raw video and Halide CPU
alignment. Only `.gitignore` conflicted. Four things broke the port, in the
order a run finds them.

### The natives overlay drifted from `app/src/main/cpp/CMakeLists.txt`

Upstream added `rawF16.cpp` to the `allocator` target and a new `mcraw` library;
`native/CMakeLists.txt` mirrors that file target by target and had neither.
`Allocator.createF16()` is on the HDRX path for every capture, so the miss would
have been an `UnsatisfiedLinkError` at merge time, not at load time.

`allocator` now also needs `AndroidBitmap_getInfo`/`lockPixels`/`unlockPixels`
(`wrapBitmap`). atlas's `libandroid.so.0` exports all three, so it is linked in
rather than stubbed. `halidealign` is deliberately absent: the kernels are
prebuilt arm64 `.a` files and `HalideAlignment` falls back to the GL pyramid
when the library will not load.

### `KeyguardManager$KeyguardDismissCallback` took down `SplashActivity`

Upstream's secure-camera support calls `SecureCameraHelper.applyLockscreenFlags()`
from `SplashActivity.onCreate`. HotSpot verifies a whole class on first use, and
verifying `SecureCameraHelper.requestDismissKeyguard()` loads the callback type
even though nothing calls that method — `NoClassDefFoundError` before the first
activity. ART verifies per method, which is why this is a port-only failure.

Fixed in atlas: `KeyguardManager.KeyguardDismissCallback`,
`requestDismissKeyguard()` (nothing is locked, so it succeeds immediately),
`createConfirmDeviceCredentialIntent()` (null, no credential configured) and
no-op `Activity.setShowWhenLocked()`/`setTurnScreenOn()`.

### `View.invalidateDrawable()` recursed until the stack ran out

atlas routed it through the no-arg `invalidate()`. Material's
`BaseProgressIndicator` overrides `invalidate()` and calls
`getCurrentDrawable().invalidateSelf()` from it, which comes straight back —
`StackOverflowError` out of `CameraActivity`'s layout, twice per run. AOSP
damages the drawable's dirty bounds through `invalidate(l,t,r,b)` instead and
never touches the no-arg overload. Fixed in atlas to match.

### `org.json` is on ART's boot classpath and not in the JDK

`RawVideoProcessor` writes the `.mcraw` container metadata with `JSONObject`, so
`DefaultSaver`'s constructor — reached the moment the camera opens — died on
`NoClassDefFoundError: org/json/JSONException`. It is not `android.*`, so it is
not an atlas gap; it is a runtime the JDK lacks, the same case as `org.xmlpull`.
Implemented in `shim/src/org/json/`. `ShimCheck` pins the writer's exact output,
because that string is what ends up inside the container.

After all four: `run.sh --seconds 35 --fresh --require-preview` boots to the
camera, the gst test pattern reaches the viewfinder, and the new M3E shutter and
mode switcher render. Nothing here was run on a device.

### The Halide aligner's fault handler took SIGSEGV away from HotSpot

The first device capture after the rebase saved its JPEG and then died. The only
report is from the aligner, long after it finished:

```
D/ESD4D   : Halide alignment time: 341ms
D/CameraEventsListener: ImageSaved: .../IMG_20260922_220650.jpg
E/HalideAlignment: caught fault in halidealign native code: SIGSEGV
                   pc=0x7f90bdaf7c insn=0xb940084a si_addr=0x8 sym=? base=0x0
E/HalideAlignment: fault on non-guarded thread; re-raising SIGSEGV
```

Nothing is wrong with the kernels, and nothing is wrong with the camera. The
faulting instruction is `ldr w10, [x2, #8]` with `si_addr=0x8`: a load of the
compressed klass word from a null oop, at a PC `dladdr` cannot place in any ELF
object. That is JIT'd Java in HotSpot's code cache taking an **implicit null
check** — the ordinary way a `NullPointerException` is raised, several hundred
times a second in a running VM.

`align_jni.cpp`'s `ensure_fault_trap()` installs a process-wide handler for
SIGILL/SIGBUS/SIGFPE/SIGSEGV/SIGABRT on the first `HalideAlignment` JNI call and
never removes it — `ALIGN_FAULT_GUARD` arms only the per-thread `g_guard_active`
flag, not the handlers. The handler saves the previous disposition but never
delegates to it: off the guarded thread it restores the default and `raise()`s,
so the first null check on any other thread after one burst is fatal. Upstream
added it in `fbc9aa06`; the aligner itself is fine.

This is a port-visible bug, not a port-only one — ART uses SIGSEGV for implicit
null checks too — but the port is where it bites, because
`linux-port/native/CMakeLists.txt` now builds `halidealign` for arm64 (it used
to be absent, and `ESD4D` fell back to the GL pyramid, which is why captures
worked before the rebase).

Fixed in the launchers, not in the app: `port_preload_jsig` (`env.sh`, called by
`run.sh`, `check-native-libs.sh`, `run-dng-pipeline.sh`, and open-coded in
`click/run.sh`, which cannot source it) puts the JDK's **`libjsig.so`** in
`LD_PRELOAD`. It interposes `sigaction()`, so the VM's handler stays in front and
hands on only what it does not recognise. `PHOTONCAMERA_JSIG=off` drops it.

Measured on oneplus11, same `ATL_DEBUG_TAP` shutter press each time:

| aligner | `libjsig` | result |
| --- | --- | --- |
| `halide` | no | JPEG saved, then SIGSEGV — twice out of two |
| `gl` | no | JPEG saved, app alive |
| `halide` | yes | JPEG saved, app alive, alignment still 331 ms |

One caveat found while confirming this. With `libjsig` the handler is still
installed, and HotSpot now *delegates* to it what it does not recognise — so a
genuine native fault reads as a `halidealign` report with the VM in the frames:

```
#00 libhalidealign.so   #01 libjvm.so   #02 JVM_handle_linux_signal
#03 __kernel_rt_sigreturn   #04 pc ... ? ((nil))
```

`JVM_handle_linux_signal` in the trace is how to tell the two apart: present
means HotSpot saw the signal first and declined it (a real crash), absent means
the handler stole it (the bug above). One such fault does happen on the SIGTERM
shutdown path, which is what `click/device.sh stop` sends to get the AppCDS dump
— so that dump may not complete. Seen twice, in the same teardown but not the
same signal: a SIGSEGV in EGL teardown (`si_addr=0x421`, `ldr x11, [x11]`, right
after an `eglCreateContext`) and a SIGABRT from a C++ destructor. The SIGABRT is
correctly *not* chained — HotSpot installs no SIGABRT handler, so nobody else
wanted it and re-raising is right. Neither is spontaneous: left alone after a
capture the app ran on for 4.5 min with zero faults, so shooting is unaffected
and only a clean stop is.

The null check itself is real and still there: with the GL aligner the same
moment logs a *caught* `ClassNotFoundException:
android.graphics.ImageDecoder$OnHeaderDecodedListener` out of Glide's decode
path, loading the gallery thumbnail of the picture just written. That is an
atlas gap worth closing, but it is not what killed the process.

**The `-aot` vehicle needed the handler fixed, not the launcher.**
`native-image` takes implicit null checks as SIGSEGV exactly as HotSpot does, and
there is no `libjsig` to preload — Substrate VM has no such shim — so the image
would have kept dying after the first burst. Fixed at the source instead:
`fault_handler` now hands any signal that is not on the guarded thread back to
the handler installed before it (`chain_to_previous`), and only logs and
re-raises when nobody else was handling it. That is the behaviour both runtimes
need, and the one ART needs too, so it is written unconditionally rather than
behind a port `-D`: the previous code was wrong on Android as well, just not
visibly.

With that in place `libjsig` is no longer load-bearing for `-cds`, and it stays
anyway — it is the documented way to keep the VM's handlers in front of *any*
JNI library, and it is what makes a future one that repeats this mistake
harmless. `PHOTONCAMERA_JSIG=off` is the way to exercise the chaining path on
the JVM vehicle, which is what an `-aot` run does by construction. Measured that
way on oneplus11: alignment 350 ms, JPEG saved, process alive, no fault — the
same configuration that was fatal before the fix.

### The click CI could not take a picture, and the atlas pin was why

The first `-aot` capture ever attempted died well before the aligner:

```
FATAL EXCEPTION: pool-4-thread-2
java.lang.NoSuchMethodError: android.opengl.GLES30.glProgramParameteri(int, int, int)
	at ...GLProg.useShader(GLProg.java:267)
	at ...ESD4D.Run(ESD4D.java:630)
```

Not a native-image metadata gap, which is what an `-aot`-only failure usually
is. `GLProg.useShader` asks for `GL_PROGRAM_BINARY_RETRIEVABLE_HINT` on every
compute program — new in the rebase, 0 occurrences before it and 2 after — and
atlas only grew those entry points in `1c4953c8` ("opengl: add the GLES 3.0
program binary calls", 2026-09-20 19:20). The pin, `sdk-c1f1a59`, is 2026-09-20
12:21: seven hours older, and `f661ea9f` moved the pin there before the atlas
commit existed.

It was never an `-aot` problem. `stage-prebuilt.sh` leaves an existing
`$ATLAS_DIR` checkout alone and only clones `$ATLAS_PIN_REV` when there is none,
so a local `make-click.sh` compiled the working tree (which had the commit)
while CI cloned the pin (which did not) — **both** CI clicks were unable to
capture, and only the local one worked. That is the same local-vs-CI split the
`kn` port hit with arm64 ncnn.

Fixed by pushing the two atlas commits that were only ever local (`clipboard:
copy and paste through content-hub`, `opengl: add the GLES 3.0 program binary
calls`) to `NotKit/atl-touch`, cutting an SDK release from them and moving
`click/atl-sdk.tag`.

**The lesson is about the pin, not the calls.** `$ATLAS_PIN_REV` is derived from
`atl-sdk.tag`, so an atlas fix a bring-up session makes locally is invisible to
CI until it is pushed *and* an SDK release names it. A capture that works here
and fails in CI should send you to `IMAGE.txt` / `.port-atlas-rev` and
`git log <pin>..HEAD` in `$ATLAS_DIR` before anything else.
