import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.BlackLevelPattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The AOSP private members PhotonCamera reaches by reflection, checked by name.
 *
 * Nothing in either build references them, so a rename in atlas breaks the app
 * with a swallowed NoSuchFieldException and a picture that quietly ignores
 * every override the app applied. See CameraReflectionApi in the app.
 */
public final class CameraReflectionCheck {
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        field(CameraCharacteristics.class, "mProperties", CameraMetadataNative.class);
        field(CaptureResult.class, "mResults", CameraMetadataNative.class);
        field(CaptureRequest.class, "mLogicalCameraSettings", CameraMetadataNative.class);
        field(BlackLevelPattern.class, "mCfaOffsets", int[].class);
        field(surfacePlane(), "mBuffer", ByteBuffer.class);

        method(CameraMetadataNative.class, "setBase", CameraCharacteristics.Key.class, Object.class);
        method(CameraMetadataNative.class, "set", CaptureResult.Key.class, Object.class);
        method(CameraMetadataNative.class, "set", CaptureRequest.Key.class, Object.class);

        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("FAIL " + f);
            throw new AssertionError(failures.size() + " missing reflection target(s)");
        }
        System.out.println("camera reflection check passed");
    }

    /** ImageReader's Plane implementation is package-private and nested twice. */
    private static Class<?> surfacePlane() throws Exception {
        return Class.forName("android.media.ImageReader$SurfaceImage$SurfacePlane");
    }

    private static void field(Class<?> owner, String name, Class<?> type) {
        try {
            Field f = owner.getDeclaredField(name);
            if (!type.isAssignableFrom(f.getType()))
                failures.add(owner.getName() + "." + name + " is a " + f.getType().getName()
                        + ", not a " + type.getName());
            else
                System.out.println("ok   " + owner.getSimpleName() + "." + name);
        } catch (NoSuchFieldException e) {
            failures.add(owner.getName() + "." + name + " does not exist");
        }
    }

    private static void method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            System.out.println("ok   " + owner.getSimpleName() + "." + m.getName() + "("
                    + params[0].getSimpleName() + ", Object)");
        } catch (NoSuchMethodException e) {
            failures.add(owner.getName() + "." + name + "(" + params[0].getName() + ", Object)"
                    + " does not exist");
        }
    }
}
