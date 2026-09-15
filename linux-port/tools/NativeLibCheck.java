// Loads the port's JNI libraries inside the launcher and calls one entry point
// from each, so a pass means System.loadLibrary found them on java.library.path
// and JNI name-based binding resolved.
//
// Run by linux-port/check-native-libs.sh through the launcher's --run-class.
import java.nio.ByteBuffer;

public class NativeLibCheck {
    public static void main(String[] args) throws Exception {
        // The names the app's static initializers use. ncnnMl is the stub build
        // until ncnn is built from source (BRINGUP_NOTES.md); it still has to
        // load, or the ML classes fail at class-init.
        for (String lib : new String[] {"dngCreator", "allocator", "flacRecorder",
                                        "camera2native", "ncnnMl"}) {
            System.loadLibrary(lib);
            System.out.println("loaded lib" + lib + ".so");
        }

        // Allocator is the cheapest round trip through JNI the app has: it
        // allocates off-heap, hands back a direct ByteBuffer and frees it.
        Class<?> allocator = Class.forName("com.particlesdevs.photoncamera.util.Allocator");
        ByteBuffer buffer = (ByteBuffer) allocator.getMethod("allocate", int.class).invoke(null, 4096);
        if (buffer == null || !buffer.isDirect() || buffer.capacity() != 4096) {
            throw new IllegalStateException("Allocator.allocate returned " + buffer);
        }
        long used = (Long) allocator.getMethod("getMemoryCount").invoke(null);
        allocator.getMethod("free", ByteBuffer.class).invoke(null, buffer);
        System.out.println("Allocator round trip ok (" + used + " bytes accounted)");

        // NativeEngine's hidden-API bypass is an ART trick with no meaning on a
        // JVM. It must degrade quietly: dlopen("libart.so") fails, the JNI call
        // logs and returns. A crash here would be a real port bug.
        Class.forName("com.particlesdevs.photoncamera.api.NativeEngine")
                .getMethod("initialize").invoke(null);
        System.out.println("NativeEngine.initialize survived without ART");

        System.out.println("native lib check: passed");
    }
}
