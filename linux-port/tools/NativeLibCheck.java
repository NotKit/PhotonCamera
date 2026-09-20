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
                                        "camera2native", "ncnnMl", "mcraw"}) {
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

        // createF16 lives in rawF16.cpp, a second source file inside
        // liballocator.so. The HDRX path calls it per frame, so a native
        // overlay that forgot the file would fail at merge time, not at load.
        ByteBuffer raw = ByteBuffer.allocateDirect(4 * 4 * 2).order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) raw.putShort((short) (i * 64));
        ByteBuffer f16 = (ByteBuffer) allocator.getMethod("createF16", ByteBuffer.class,
                int.class, int.class, int.class, float[].class)
                .invoke(null, raw, 4, 4, 1023, new float[] {0f, 0f, 0f, 0f});
        if (f16 == null || !f16.isDirect() || f16.capacity() != 32) {
            throw new IllegalStateException("Allocator.createF16 returned " + f16);
        }
        allocator.getMethod("free", ByteBuffer.class).invoke(null, f16);
        System.out.println("Allocator.createF16 round trip ok");

        // NativeEngine's hidden-API bypass is an ART trick with no meaning on a
        // JVM. It must degrade quietly: dlopen("libart.so") fails, the JNI call
        // logs and returns. A crash here would be a real port bug.
        Class.forName("com.particlesdevs.photoncamera.api.NativeEngine")
                .getMethod("initialize").invoke(null);
        System.out.println("NativeEngine.initialize survived without ART");

        System.out.println("native lib check: passed");
    }
}
