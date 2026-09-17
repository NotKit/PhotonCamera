#!/bin/bash
# Post-passes on kn/gen-atlas: the few things j2k cannot know about atlas's
# camera2 Java.  Deterministic rewrites of generated text; nothing here is a
# behaviour change the Java could express instead (atlas is never edited).
set -euo pipefail
GEN="$1"
PY="${PYTHON:-$HOME/UT/kn-toolchain/venv/bin/python}"
"$PY" - "$GEN" <<'PY'
import os, re, sys

GEN = sys.argv[1]

# java.lang.Class is a type token here and nothing else: the marshalling in
# CameraMetadataNative only compares tokens.  photoncam.camera.MetaType is one
# Kotlin/Native can have.
PRIM_ARRAY = {"ByteArray": "Byte", "IntArray": "Int", "LongArray": "Long",
              "FloatArray": "Float", "DoubleArray": "Double"}

# the Key constants are enumerated with getDeclaredFields(); Kotlin/Native has
# no reflection, so Key's constructor registers itself instead
KEYS_BY_NAME = re.compile(
    r"^(\s*)fun keysByName\(\): Map<String, Key<\*>>\? \{\n.*?^\1\}$", re.S | re.M)

# SensorManager: atlas registers an accelerometer through a native hook and a
# compass through android.location, neither of which this port has.  The
# orientation maths around them is AOSP's and is what PhotonCamera uses.
SENSOR_REGISTER = re.compile(
    r"(open fun registerListener\(listener: SensorEventListener\?, sensor: Sensor\?, "
    r"samplingPeriodUs: Int\): Boolean \{).*?\n(    \})", re.S)
SENSOR_NATIVE = re.compile(
    r"    open fun register_accelerometer_listener_native\(.*?\n    \}\n", re.S)


SENSOR_CONSTANTS = """        /* AOSP constants atlas's SensorManager does not carry and the app uses */
        const val SENSOR_DELAY_FASTEST: Int = 0
        const val SENSOR_DELAY_GAME: Int = 1
        const val SENSOR_DELAY_UI: Int = 2
        const val SENSOR_DELAY_NORMAL: Int = 3
        const val AXIS_X: Int = 1
        const val AXIS_Y: Int = 2
        const val AXIS_Z: Int = 3
        const val AXIS_MINUS_X: Int = 129
        const val AXIS_MINUS_Y: Int = 130
        const val AXIS_MINUS_Z: Int = 131
"""


def fix_sensor_manager(text):
    text = SENSOR_REGISTER.sub(
        lambda m: m.group(1) + "\n        /* no sensor source in this port */\n"
                  "        return false\n" + m.group(2), text)
    text = SENSOR_NATIVE.sub("", text)
    wanted = [c for c in SENSOR_CONSTANTS.splitlines()
              if c.strip().startswith("/*")
              or not re.search(r"\b%s\b\s*:" % c.split()[2].rstrip(":"), text)]
    text = text.replace("    companion object {\n",
                        "    companion object {\n" + "\n".join(wanted) + "\n", 1)
    return re.sub(r"import android\.location\.\w+\n", "", text)



# strip_assignment_bang moved to scripts/bang_below.py in round 5, when
# convert.sh needed the same pass over kn/gen: one copy, so the two cannot
# drift.  See that file for the shape and the reasoning.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(GEN)), "scripts"))
from bang_below import strip_assignment_bang


def fix(path, text):
    owner = os.path.basename(path)[:-3]

    if owner == "SensorManager":
        text = fix_sensor_manager(text)

    text = KEYS_BY_NAME.sub(
        lambda m: "%sfun keysByName(): Map<String, Key<*>>? =\n%s    "
                  "photoncam.camera.KeyRegistry.snapshot(\"%s\")" % (m.group(1), m.group(1), owner),
        text)
    text = text.replace("import java.lang.Class\n", "")
    text = re.sub(r"\bClass<[^<>]*>", "photoncam.camera.MetaType", text)
    text = re.sub(r"\bknArrayClass<Array<(\w+)>>\(\)",
                  lambda m: 'photoncam.camera.MetaType.of("%s[]")' % m.group(1), text)
    text = re.sub(r"\bknArrayClass<(\w+)>\(\)",
                  lambda m: 'photoncam.camera.MetaType.of("%s[]")' % PRIM_ARRAY.get(m.group(1), m.group(1)), text)
    text = re.sub(r"\b(\w+)::class\.java", lambda m: 'photoncam.camera.MetaType.of("%s")' % m.group(1), text)

    # a Key registers itself as it is built; first writer wins, so the constants
    # are what keysByName() reports and getKeys()' untyped keys never displace them
    if owner in ("CaptureRequest", "CaptureResult", "CameraCharacteristics"):
        text = text.replace(
            "            this.type = type!!\n        }",
            "            this.type = type!!\n"
            "            photoncam.camera.KeyRegistry.put(\"%s\", name, this)\n        }\n"
            "        /* `new Key<>(name, Foo.class)` in the app: j2k writes the token as\n"
            "         * `Foo::class.java`, and a KClass names the type well enough. */\n"
            "        constructor(name: String?, type: kotlin.reflect.KClass<*>?) :\n"
            "            this(name, photoncam.camera.MetaType.ofClass(type))" % owner, 1)


    # getKeys(): `KEYS_BY_NAME.get(name)` is null for a tag no constant names,
    # and the very next line is the ternary that handles it.  j2k's `!!` on the
    # lookup makes that branch dead and throws on the first unnamed vendor tag
    # instead -- which every real device has.  (r996 strips this shape only when
    # the null test is an `if` STATEMENT; atlas wrote a ternary.)
    text = text.replace("KEYS_BY_NAME!!.get(name!!)!!", "KEYS_BY_NAME!!.get(name!!)")
    text = re.sub(r"(\bkeyForName\(name!!\))!!", r"\1", text)
    # A session with no input has no InputConfiguration, and configureSession
    # takes null for it; j2k's `!!` turns "not reprocessing" into a crash.
    text = text.replace("config!!.getInputConfiguration()!!)", "config!!.getInputConfiguration())")
    text = strip_assignment_bang(text)
    # A request's tag is the app's own opaque object and is usually absent;
    # `!!` on it makes an untagged request -- which is every preview request --
    # throw where AOSP simply carries null.
    text = text.replace("this.tag = tag!!", "this.tag = tag")
    text = text.replace("targets!!, tag!!, reprocess!!", "targets!!, tag, reprocess!!")

    # j2k's generated stub surface is convert.sh's, not this tree's
    text = text.replace("import org.mozilla.gecko.knstub.*\n", "")

    # Java's raw types in instanceof/cast: Kotlin wants the star projection
    text = re.sub(r"\bis ((?:\w+\.)*Key)\)", r"is \1<*>)", text)
    text = re.sub(r"\bis (Range)\)", r"is \1<*>)", text)
    text = re.sub(r"\bis (Pair)\)", r"is \1<*, *>)", text)

    # Int + "string": Kotlin has no widening concat, a String receiver does
    text = re.sub(r'(\(|return )([A-Za-z_]\w*(?:!!)?) \+ "', r'\1"" + \2 + "', text)

    # `logical |= capability == X` lost its precedence in the conversion
    text = text.replace("(logical or capability ==", "(logical || capability ==")

    # j2k renames an equals() parameter to `other` but not its uses
    text = re.sub(r"\bo is Pair\b", "other is Pair", text)
    text = text.replace("(o as Pair<*, *>)", "(other as Pair<*, *>)")

    # Java reassigns the constructor's parameters; Kotlin's are val
    text = text.replace("""    constructor(numerator: Int, denominator: Int) {
        if (denominator < 0) {
            numerator = -numerator
            denominator = -denominator
        }""", """    constructor(numeratorIn: Int, denominatorIn: Int) {
        var numerator: Int = numeratorIn
        var denominator: Int = denominatorIn
        if (denominator < 0) {
            numerator = -numerator
            denominator = -denominator
        }""")


    # `a[i] = a[j] = value`: j2k leaves the chain half-translated
    def unchain(m):
        indent, lvalues, value = m.group(1), m.group(2), m.group(3)
        targets = re.findall(r"\w+!!\[[^\]]+\]", lvalues)
        return "\n".join("%s%s = %s" % (indent, t, value) for t in targets)

    text = re.sub(r"^(\s*)((?:\w+!!\[[^\]]+\] = ){2,})(\S+)$", unchain, text, flags=re.M)

    # An int literal assigned to a float array element: Java widens, Kotlin does not.
    text = re.sub(r"^(\s*\w+!!\[[^\]]+\] = )(-?\d+)$", r"\1\2f", text,
                  flags=re.M) if owner == "SensorManager" else text

    # Kotlin's Map.Entry is what knshim's entrySet() hands back
    text = text.replace("MutableMap.MutableEntry<", "Map.Entry<")

    # Arrays.hashCode has no primitive-array overloads in the java shim
    text = re.sub(r"java\.util\.Arrays\.hashCode\((\w+)!!\)", r"\1!!.contentHashCode()", text)

    # no reflection: a listener's class name is its toString()
    text = text.replace("!!.getClass()!!.getName()!!", "!!.toString()")

    # Throwable.getStackTrace() is an experimental Kotlin/Native API
    if "getStackTrace()" in text:
        text = text.replace('"OVERRIDE_DEPRECATION")\n',
                            '"OVERRIDE_DEPRECATION")\n'
                            "@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)\n", 1)


    # --- one-file shapes j2k cannot know about ---

    # AOSP's camera2 callbacks are abstract classes with no state; j2k writes an
    # anonymous subclass as `object : X {`, which Kotlin only accepts for an
    # interface.  Nothing constructs or extends them any other way.
    def as_interface(text, name):
        m = re.search(r"^(    )abstract class %s \{\n(.*?)\n\1\}$" % name, text, re.S | re.M)
        if not m:
            return text
        body = re.sub(r"^        (?:abstract|open) fun ", "        fun ", m.group(2), flags=re.M)
        return text[:m.start()] + "    interface %s {\n%s\n    }" % (name, body) + text[m.end():]

    if owner in ("CameraCaptureSession", "CameraDevice"):
        for name in ("CaptureCallback", "StateCallback"):
            text = as_interface(text, name)

    if owner == "Range":
        # AOSP's endpoints are final and never null; nullable ones make every
        # `range.getUpper()` in the app a nullable value
        text = text.replace("""    var lower: T? = null
    var upper: T? = null""", """    var lower: T
    var upper: T""")
        text = text.replace("""    fun getLower(): T? {
        return lower
    }""", """    fun getLower(): T {
        return lower
    }""")
        text = text.replace("""    fun getUpper(): T? {
        return upper
    }""", """    fun getUpper(): T {
        return upper
    }""")

    if owner == "Pair":
        # AOSP's Pair fields are final and set from the constructor; nullable
        # ones make every `pair.first * x` in the app a nullable-receiver error
        text = text.replace("""    var first: F? = null
    var second: S? = null""", """    var first: F
    var second: S""")
        text = text.replace("""    constructor(first: F?, second: S?) {
        this.first = first!!
        this.second = second!!
    }""", """    constructor(first: F, second: S) {
        this.first = first
        this.second = second
    }""")

    if owner == "Rational":
        # atlas extends java.lang.Number; Kotlin's Number is the abstract one,
        # so the JDK spelling becomes plain methods and the Kotlin spelling the
        # overrides.  Serialization has no meaning here.
        text = re.sub(r"    override fun (doubleValue|floatValue|intValue|longValue|shortValue)\(",
                      r"    fun \1(", text)
        text = text.replace("    fun toFloat(): Float {", "    override fun toFloat(): Float {")
        text = re.sub(r"    fun readObject\(.*?\n    \}\n", "", text, flags=re.S)
        text = text.replace("import java.io.InvalidObjectException\n", "")
        text = text.replace("    override fun toFloat(): Float {",
                            "    override fun toDouble(): Double = doubleValue()\n"
                            "    override fun toInt(): Int = intValue()\n"
                            "    override fun toLong(): Long = longValue()\n"
                            "    override fun toShort(): Short = shortValue()\n"
                            "    override fun toByte(): Byte = intValue().toByte()\n"
                            "    override fun toFloat(): Float {", 1)

    if owner == "StreamConfigurationMap":
        # the type token lost its type parameter with Class
        text = text.replace("fun <T> isOutputSupportedForClass(", "fun isOutputSupportedForClass(")
        # `map.getOutputSizes(SurfaceTexture.class)`: the token is a KClass here
        text = text.replace("""    fun <T> getOutputSizes(klass: photoncam.camera.MetaType?): Array<Size>? {""",
                            """    fun getOutputSizes(klass: kotlin.reflect.KClass<*>?): Array<Size>? {
        return sizes(ImageFormat.PRIVATE!!, OUTPUT!!)
    }
    fun <T> getOutputSizes(klass: photoncam.camera.MetaType?): Array<Size>? {""")
        text = re.sub(r"(table!!\[i(?: \+ \d+)?\] == )((?:\w+!!\.)?\w+(?:\(\))?)",
                      r"\1(\2).toLong()", text)

    if owner == "CameraExtensionCharacteristics":
        text = re.sub(r"(Capture(?:Request|Result)\.Key)>", r"\1<*>>", text)
        text = text.replace("return Collections.emptyList()", "return ArrayList()")

    if owner == "CameraExtensionSession":
        text = text.replace("    override fun close()\n", "    abstract override fun close()\n")

    if owner == "HandlerExecutor":
        text = text.replace("override fun execute(command: java.lang.Runnable?)",
                            "override fun execute(command: java.lang.Runnable)")
        text = text.replace("handler!!.post(command!!)", "handler!!.post(command)")

    if owner == "CameraCaptureSession":
        # a Java parameter Java reassigns
        text = text.replace("            var id: Int = nextSequenceId++\n            if (burst == null) {",
                            "            var id: Int = nextSequenceId++\n"
                            "            var burst: Burst? = burst\n"
                            "            if (burst == null) {")
        text = text.replace("sequences!!.remove(id!!) else null",
                            "(sequences!!.remove(id!!) as Sequence?) else null")

    if owner == "CameraCharacteristics":
        # java.lang.String(byte[], Charset); the bytes are the HAL's UTF-8
        text = text.replace("String(packed!!, StandardCharsets.UTF_8!!)!!",
                            "packed!!.decodeToString()")

    if owner == "Sensor":
        # Build.VERSION_CODES.JELLY_BEAN_MR1
        text = text.replace("Build.VERSION_CODES!!.JELLY_BEAN_MR1", "17")

    # j2k writes TODO() for a declaration Java left unassigned; that is a crash
    # where Java has a default
    text = re.sub(r": Boolean = TODO\(\)", ": Boolean = false", text)
    text = re.sub(r": Int = TODO\(\)", ": Int = 0", text)
    text = re.sub(r": Long = TODO\(\)", ": Long = 0L", text)
    text = re.sub(r": Float = TODO\(\)", ": Float = 0f", text)
    text = re.sub(r": Double = TODO\(\)", ": Double = 0.0", text)
    text = re.sub(r"(: [\w.]+(?:<[^=]*>)?\?) = TODO\(\)", r"\1 = null", text)
    return text

# Java resolves CameraMetadata's constants through the subclass that inherits
# them (CaptureRequest.CONTROL_AF_MODE_AUTO); a Kotlin companion object inherits
# nothing, so the constants are copied into each subclass's companion.
META = os.path.join(GEN, "android/hardware/camera2/CameraMetadata.kt")
if os.path.exists(META):
    consts = re.findall(r"^        val [A-Z][A-Z0-9_]*: Int = -?\d+$",
                        open(META).read(), re.M)
    for name in ("CaptureRequest", "CaptureResult", "CameraCharacteristics"):
        path = os.path.join(GEN, "android/hardware/camera2/%s.kt" % name)
        text = open(path).read()
        wanted = [c for c in consts
                  if not re.search(r"\b%s\b\s*:" % c.split()[1].rstrip(":"), text)]
        text = text.replace("    companion object {\n",
                            "    companion object {\n" + "\n".join(wanted) + "\n", 1)
        open(path, "w").write(text)


for root, _, files in os.walk(GEN):
    for f in files:
        if not f.endswith(".kt"):
            continue
        p = os.path.join(root, f)
        s = open(p).read()
        t = fix(p, s)
        if t != s:
            open(p, "w").write(t)
PY
