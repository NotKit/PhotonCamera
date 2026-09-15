import libcore.io.Libcore;
import libcore.io.Posix;
import libcore.util.NativeAllocationRegistry;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;

public final class ShimCheck {
    public static void main(String[] args) throws Exception {
        if (!Posix.isAvailable()) throw new AssertionError(Posix.loadError());
        if (Libcore.os.getpid() != ProcessHandle.current().pid()) throw new AssertionError("getpid");
        if (Libcore.os.gettid() <= 0) throw new AssertionError("gettid");

        long before = NativeAllocationRegistry.SelfTest.freeCalls();
        long address = NativeAllocationRegistry.SelfTest.allocate(16);
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
                ShimCheck.class.getClassLoader(), NativeAllocationRegistry.SelfTest.freeFunction(), 16);
        registry.registerNativeAllocation(new Object(), address).run();
        if (NativeAllocationRegistry.SelfTest.freeCalls() != before + 1)
            throw new AssertionError("native free function");

        XmlPullParser parser = new KXmlParser();
        parser.setInput(new StringReader("<root><child>ok</child></root>"));
        if (parser.nextTag() != XmlPullParser.START_TAG || !"root".equals(parser.getName()))
            throw new AssertionError("XML pull parser");
        System.out.println("shim check passed");
    }
}
