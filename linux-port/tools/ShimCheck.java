import libcore.io.Libcore;
import libcore.io.Posix;
import libcore.util.NativeAllocationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
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
        JSONObject json = new JSONObject();
        json.put("i", 7).put("s", "a\"b").put("b", true);
        json.put("f", new JSONArray(new float[] {1f, 2.5f}));
        json.put("o", new JSONObject().put("n", JSONObject.NULL));
        String written = json.toString();
        if (!"{\"i\":7,\"s\":\"a\\\"b\",\"b\":true,\"f\":[1,2.5],\"o\":{\"n\":null}}".equals(written))
            throw new AssertionError("JSON writer: " + written);
        JSONObject read = new JSONObject(written);
        if (read.getInt("i") != 7 || !"a\"b".equals(read.getString("s"))
                || !read.getBoolean("b") || read.getJSONArray("f").getDouble(1) != 2.5
                || !read.getJSONObject("o").isNull("n"))
            throw new AssertionError("JSON reader: " + read);

        System.out.println("shim check passed");
    }
}
