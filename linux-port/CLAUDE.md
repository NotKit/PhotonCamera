# linux-port/ conventions

- Every script starts with `source "$(dirname "$0")/env.sh"` (or `../env.sh`) and
  uses `$PORT_OUT`, `$ATLAS_DIR`, `$JAVA_HOME`, `$PORT_LIB_OUT` from there. Never
  hardcode a path or a JDK version in a build script — the toolchain is pinned in
  one place.
- All products go under `linux-port/out/` (gitignored). Nothing generated is
  committed; scripts must be idempotent and safe to re-run.
- `$ATLAS_DIR` is an atl-touch checkout — its own git repo, gitignored here,
  pinned by `click/atl-sdk.tag` (`env.sh`, `$ATLAS_PIN_REV`) on a fresh clone and
  left alone when one already exists. The camera2 work is on master now; there is
  no separate branch to track. It is a source dependency the port builds, not a place
  artifacts are copied from. Fix framework bugs there as commits that go upstream
  to atl-touch; never patch atlas from this repository and never paper over a
  framework bug in the shim jar or the launcher.
- Run `check-env.sh` before debugging a build failure; it catches a wrong JDK, a
  missing Android platform and an atlas checkout without camera2 — the usual
  causes.
- Never edit `app/src/main/cpp/**` or `app/src/main/java/**` for the port's sake.
  A missing include or an NDK-vs-glibc signature difference is fixed in
  `native/include/port_compat.hpp` (force-included into every C++ TU) or with a
  shim header under `native/include/`. A change to app code is a last resort, and
  then only as an escape hatch guarded by a `-D` property, so the Android build
  is bit-identical without it.
- `native/CMakeLists.txt` mirrors `app/src/main/cpp/CMakeLists.txt` target by
  target; it does not include it. Re-read the Android file before changing the
  overlay, and keep the source lists and compile flags in step with it.
- Missing Android system libraries get a declaration-only header in
  `native/include/android/` plus either an implementation in `native/stubs/` or a
  link against atlas's `libandroid.so.0`. Check what atlas already exports
  (`nm -D $ATLAS_OUT/libandroid.so.0`) before writing a stub.
- Gradle glue for the port lives in `gradle/`, is applied from the root
  `build.gradle` only under `-PlinuxPort`, and hangs off `gradle.projectsEvaluated`
  (an `afterEvaluate` registered from the root project runs before AGP's, so
  variant configurations do not exist yet). The same flag guards
  `externalNativeBuild` in `app/build.gradle` — never let a port build invoke the
  NDK, it dirties `app/src/main/cpp/deps/`.
- The port's variant is `debug`. Anything that names app classes (the launcher,
  the checks, the shim) depends on that, because `release` runs R8 and renames
  them.
- Scripts use `set -euo pipefail`, so never pipe a long-running command into
  `grep -q` — grep exits first and the SIGPIPE fails the pipeline. Capture the
  output in a variable, then grep it.
- The compat shim (`shim/`, built by `build-shim.sh`) may only contain classes
  ART's boot classpath has and the JDK lacks: `libcore.*`, `dalvik.*`,
  `android.system.*`, `android.icu.*`, `org.xmlpull.*`, `org.kxml2.*`. It
  compiles against the JDK alone. Nothing can be shimmed into `java.*`. A
  framework gap goes to `$ATLAS_DIR` instead, and is listed in `shim/README.md`
  under "Fixed in atlas".
- ncnn is pinned to a master commit in `native/build_ncnn_linux.sh` (`NCNN_REF`),
  not to a release tag: the FlowNet layers need the 4D `Mat` no tag has yet.
  Moving that pin is a decision, not a chore — the vendored Android library is a
  master build too, and the two want to stay close.
- Verification helpers written in Java go in `tools/`, compiled into
  `$PORT_OUT/tools` by the script that uses them.
- Anything running the launcher needs
  `LD_LIBRARY_PATH=$JAVA_HOME/lib/server:$ATLAS_OUT` — `libjvm.so` is
  deliberately not in its RUNPATH — and `source launcher/display.sh` for
  `port_start_display`/`port_screenshot` instead of its own `Xvfb` block.
- To run app code without a full boot, use the launcher's `--run-class CLASS`
  with a helper in `tools/` (`check-native-libs.sh` is the worked example). That
  is the cheapest way to test one piece of the app while the boot path is still
  incomplete, and it is how the processing pipeline should be exercised on a
  recorded DNG rather than on a live camera.
- This is a GLES app: `ATL_NO_GPU=1` is *not* a fallback here, it is a way to
  smoke-test the UI without the pipeline. Say which one a run was.
- The camera has three backends and only `camera2ndk` (on a device) gives real
  sensor data. A desktop run uses the synthetic `gst` one — never report a
  desktop capture as evidence that capture works.
- `build-all.sh` is the port's build order, written down: every build script
  belongs to one of its steps, so a new one has to be added there too. The
  click is the exception — it is a second vehicle, not a step of the desktop
  build, and `click/make-click.sh` is its one entry point.
- The click cross-compiles in a clickable container that mounts the repository
  and nothing else, so anything it needs from outside has to be staged into
  `out/click-prebuilt/` first (`click/stage-prebuilt.sh`) — the ATL SDK's
  prebuilt dependencies and the atlas sources alike. A build script that reaches
  outside `$REPO_DIR` works on the desktop and fails in the container.
- atlas's **natives** are always compiled, never taken from the ATL SDK: the SDK
  supplies that build's inputs (art_standalone, skia, GLFW, `dx`), not its output.
  Its **`api-impl_classes.jar`** is a different matter — the AOT image is built
  over it on a runner that cannot compile atlas, and that is sound only because
  the SDK tag and `$ATLAS_PIN_REV` name one commit. `click/build.sh` fails the
  build when the image and the compiled framework disagree on the revision.
- Record every deviation from the Android build in `BRINGUP_NOTES.md` as it is
  found, and read it before chasing a symptom.
- The sibling Mercurygram port (`/home/nekit/UT/mercurygram-src/linux-port/`) is
  the reference implementation for everything not written here yet: the
  native-image backend and the driving checks. Read it before inventing a second
  way of doing the same thing.
- There are two vehicles, and `build-all.sh` builds neither of the extra ones:
  the click (`click/make-click.sh`) and the ahead-of-time image
  (`build-image.sh`, GraalVM `native-image`). Both stand on the desktop build's
  products; neither is a step of it.
- `image/ni-config/` is the one generated tree this port commits. native-image
  cannot cross-compile, so the arm64 image is built on a runner that cannot run
  the app — the metadata has to travel with the source. `trace-metadata.sh`
  writes it, nothing edits it by hand, and an entry an image *run* proves the
  trace could not see goes in `image/extra-config/` with its failure recorded.
- `PORT_EXTRA_JVM_ARGS` puts extra `-X` options on any launcher run (`run.sh`,
  `check-native-libs.sh`, `run-dng-pipeline.sh`). It is how the tracing agent
  gets onto a run; do not add a second mechanism for it.
