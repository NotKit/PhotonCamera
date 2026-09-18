/* Hand-written, in the SAME PACKAGE as j2k's generated JavaStdlibShim.kt
 * (gen/photoncam/knshim/JavaStdlibShim.kt), because every converted file has
 * `import photoncam.knshim.*` and a star import beats the default kotlin.*
 * ones.  That is the only place an extension can sit and be visible to the
 * whole corpus without j2k emitting an import for it.
 *
 * Nothing here contradicts the generated file: these are names it does not
 * declare.  If the generator ever grows one of them, drop it here. */
@file:Suppress("NOTHING_TO_INLINE", "unused", "EXTENSION_SHADOWED_BY_MEMBER")

package photoncam.knshim

import kotlin.reflect.KClass

// --- String.split: Java's returns an array and takes a regex; Kotlin's takes
// literal delimiters and returns a List.  j2k types the result Array<String>.
//
// AND JAVA DROPS THE TRAILING EMPTY STRINGS when the limit is zero, which
// Kotlin's Regex.split does not.  That one difference is silent everywhere and
// wrong everywhere: `"buffer histogramRed ".split(" ")` ends in "" here and in
// "histogramRed" on Android, so GLInterface.getLayouts keyed every compute
// buffer under "" and every setBufferCompute missed -- the noise histogram ran
// against unbound buffers with nothing said but "Wrong computeLayout".
// Java's own rule, and now ours: if the pattern never matched, the whole string
// comes back; otherwise the trailing empties go, all of them.
fun String.split(regex: String): Array<String> = javaSplit(regex, 0)

fun String.split(regex: String, limit: Int): Array<String> = javaSplit(regex, limit)

private fun String.javaSplit(regex: String, limit: Int): Array<String> {
    val parts = Regex(regex).split(this, if (limit > 0) limit else 0)
    // limit != 0 keeps them, as Java's does; one part means no match at all,
    // and Java hands that string straight back ("".split(",") is [""]).
    if (limit != 0 || parts.size <= 1) return parts.toTypedArray()
    var n = parts.size
    while (n > 0 && parts[n - 1].isEmpty()) n--
    return parts.subList(0, n).toTypedArray()
}

// --- Foo.class: java.lang.Class is a typealias for KClass, so `.java` is the
// identity.  knArrayClass<T>() is j2k's spelling of `T[].class`.
val KClass<*>.java: KClass<*> get() = this
fun KClass<*>.getName(): String = qualifiedName ?: simpleName ?: "?"
fun KClass<*>.getSimpleName(): String = simpleName ?: "?"
inline fun <reified T : Any> knArrayClass(): KClass<*> = T::class

// --- the boxed statics Kotlin's companions have not got.
fun Int.Companion.compare(a: Int, b: Int): Int = a.compareTo(b)
fun Long.Companion.compare(a: Long, b: Long): Int = a.compareTo(b)
fun Short.Companion.compare(a: Short, b: Short): Int = a.compareTo(b)
fun Float.Companion.compare(a: Float, b: Float): Int = a.compareTo(b)
fun Double.Companion.compare(a: Double, b: Double): Int = a.compareTo(b)
fun Boolean.Companion.compare(a: Boolean, b: Boolean): Int = a.compareTo(b)

fun Float.Companion.isNaN(v: Float): Boolean = v.isNaN()
fun Float.Companion.isInfinite(v: Float): Boolean = v.isInfinite()
fun Float.Companion.isFinite(v: Float): Boolean = v.isFinite()
fun Float.Companion.floatToIntBits(v: Float): Int = v.toRawBits()
fun Float.Companion.floatToRawIntBits(v: Float): Int = v.toRawBits()
fun Float.Companion.intBitsToFloat(bits: Int): Float = Float.fromBits(bits)
val Float.Companion.MIN_NORMAL: Float get() = 1.17549435E-38f
fun Double.Companion.isNaN(v: Double): Boolean = v.isNaN()
fun Double.Companion.isInfinite(v: Double): Boolean = v.isInfinite()
fun Double.Companion.doubleToLongBits(v: Double): Long = v.toRawBits()
fun Double.Companion.longBitsToDouble(bits: Long): Double = Double.fromBits(bits)
fun Short.Companion.parseShort(s: String): Short = s.toShort()
fun Byte.Companion.parseByte(s: String): Byte = s.toByte()
fun Byte.Companion.toUnsignedInt(v: Byte): Int = v.toInt() and 0xFF
fun Short.Companion.toUnsignedInt(v: Short): Int = v.toInt() and 0xFFFF
fun Int.Companion.parseInt(s: String): Int = s.toInt()
fun Int.Companion.parseInt(s: String, radix: Int): Int = s.toInt(radix)
fun Int.Companion.toHexString(v: Int): String = v.toUInt().toString(16)
fun Int.Companion.toBinaryString(v: Int): String = v.toUInt().toString(2)
fun Long.Companion.toHexString(v: Long): String = v.toULong().toString(16)
fun Int.Companion.bitCount(v: Int): Int = v.countOneBits()
fun Int.Companion.toString(v: Int): String = v.toString()
fun Long.Companion.toString(v: Long): String = v.toString()
fun Float.Companion.toString(v: Float): String = v.toString()
fun Double.Companion.toString(v: Double): String = v.toString()
val Int.Companion.BYTES: Int get() = 4
val Long.Companion.BYTES: Int get() = 8
val Float.Companion.BYTES: Int get() = 4
val Double.Companion.BYTES: Int get() = 8
val Short.Companion.BYTES: Int get() = 2
val Byte.Companion.BYTES: Int get() = 1

// --- Iterator.remove(): Java's Iterator has it, Kotlin's read-only one has not.
@Suppress("UNCHECKED_CAST")
fun <T> Iterator<T>.remove() { (this as MutableIterator<T>).remove() }

// --- Map.forEach((k, v) -> ...): Java 8's two-argument form.
fun <K, V> Map<K, V>.forEach(action: (K, V) -> Unit) { for ((k, v) in this) action(k, v) }

// --- Number's intValue()/floatValue()/... are already in the generated
// JavaStdlibShim.kt in this package; declaring them again would collide.

// --- List.sort(Comparator): Java 8's default method, on the read-only List
// j2k maps java.util.List onto.  Same cast the generated shim makes.
@Suppress("UNCHECKED_CAST")
fun <T> List<T>.sort(c: Comparator<in T>) { (this as MutableList<T>).sortWith(c) }
@Suppress("UNCHECKED_CAST")
fun <T : Comparable<T>> List<T>.sort() { (this as MutableList<T>).sort() }
fun <T> Array<T>.sort(c: Comparator<in T>) { sortWith(c) }

// --- new String(bytes, charset) and String.getBytes(charset).
fun knString(bytes: ByteArray, charset: Any? = null): String = bytes.decodeToString()
fun knString(bytes: ByteArray, offset: Int, length: Int, charset: Any? = null): String =
    bytes.decodeToString(offset, offset + length)

// --- String members Kotlin spells differently or not at all.
fun String.compareToIgnoreCase(other: String): Int = compareTo(other, ignoreCase = true)
fun String.replaceAll(regex: String, replacement: String): String =
    Regex(regex).replace(this, replacement)
fun String.matches(regex: String): Boolean = Regex(regex).matches(this)
fun String.concat(other: String): String = this + other

// --- Throwable.printStackTrace(stream): the no-arg one is Kotlin's own.
fun Throwable.printStackTrace(out: Any?) {
    java.lang.System.err.println(stackTraceToString())
}

// --- new String(bytes, charset): a JVM-only constructor; j2k keeps the call shape.
fun String(bytes: ByteArray, charset: java.nio.charset.Charset): String = charset.decode(bytes)
fun String(bytes: ByteArray, charsetName: String): String =
    java.nio.charset.Charset.forName(charsetName).decode(bytes)
