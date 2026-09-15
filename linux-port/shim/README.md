# The libcore/dalvik compat shim

Classes ART's boot classpath has and a stock JDK does not. HotSpot runs the app
with `api-impl.jar` (the atlas framework) on an ordinary class path, so anything
the app or the framework reaches for outside `java.*` and outside `android.*`
has to come from somewhere — this jar.

**Allowed here:** `libcore.*`, `dalvik.*`, `android.system.*`, `android.icu.*`,
`org.xmlpull.*`, `org.kxml2.*`.

**Not allowed here:**

- `java.*` — the boot class loader always wins, so such a class would be
  silently ignored. If the framework calls a method only libcore adds to a JDK
  class, the fix is in atlas's own compilation.
- `android.*` — that is the framework. A missing class or method is an atlas
  bug: fix it in `$ATLAS_DIR` as a commit on the atlas branch, which goes
  upstream to atl-touch, and list it below under *Fixed in atlas*.
- Anything the app happens to need. The shim replaces a *runtime*, not a
  dependency.

Approximations (ICU patterns, plural rules, likely subtags) must say so in the
class javadoc and in this file, so nobody later mistakes them for real ICU
behaviour.

## Contents

The first boot and camera activity needed the following classes, copied from
the sibling Mercurygram port's shim (`../BRINGUP_NOTES.md` records the runs):

| Classes | Caller | Runtime difference |
| --- | --- | --- |
| `org.xmlpull.v1.*`, `org.kxml2.io.KXmlParser` | framework XML resources | The pull parser uses JDK StAX. Empty-element detection and namespace counts are approximate. |
| `libcore.util.NativeAllocationRegistry` | Skia-backed `Paint`, `Canvas`, `Path` | JDK `Cleaner` calls the registered native free function through `libportshim.so`; the ART allocation-size hint has no effect. |
| `libcore.io.Libcore`, `Os`, `Posix`, `IoUtils` | process and preference code | Process IDs use `ProcessHandle`; raw descriptor calls use `libportshim.so`. |
| `android.system.Os`, `OsConstants`, `ErrnoException`, `StructStat`, `StructStatVfs` | shared preferences | Filesystem metadata uses JDK NIO; descriptor calls use the shim JNI library. |
| `dalvik.system.BlockGuard`, `VMRuntime`, `CloseGuard` | preferences, layouts, cursors | StrictMode and leak warnings are disabled. Arrays are ordinary HotSpot arrays; `addressOf` returns zero. |

`../build-shim.sh` builds both `out/shim.jar` and `out/lib/libportshim.so`.
`../tools/ShimCheck.java` exercises the native free function, thread IDs and
XML parser. The factory has no `KXmlSerializer` yet; add one if a real run
reaches `android.util.Xml.newSerializer()`.

## Fixed in atlas

Framework gaps that were fixed in `$ATLAS_DIR` rather than shimmed here.

- `ViewConfiguration.getScaledOverscrollDistance()` for list layout.
- X11 `SurfaceView` EGL presentation through a pbuffer and Skia bitmap.
- `CaptureRequest.Builder.setPhysicalCameraKey()` and
  `CaptureResult.SENSOR_NEUTRAL_COLOR_POINT` for camera2 preview.
- `SurfaceHolder.lockHardwareCanvas()` for the viewfinder overlay; without it a
  `NoSuchMethodError` escaped `Looper.loop()` and killed the UI thread on every
  camera open.
- `CameraMetadata.getKeys(Class, Class, CameraMetadata, int[], boolean)`, AOSP's
  package-private key enumerator, which camera apps reach by reflection to read
  vendor keys.
