/* The Java spellings j2k imports by name from java.lang (see j2k/rules/
 * jdk-stubs.py EXT_TRIGGERS): statics on types Kotlin owns, and Object's
 * members.  An explicit import beats photoncam.knshim's star import, so these
 * win wherever j2k emitted the trigger. */
@file:Suppress("NOTHING_TO_INLINE", "unused", "EXTENSION_SHADOWED_BY_MEMBER")

package java.lang

import kotlin.reflect.KClass

fun kotlin.Long.Companion.parseLong(s: String): kotlin.Long = s.toLong()
fun kotlin.Long.Companion.parseLong(s: String, radix: Int): kotlin.Long = s.toLong(radix)
fun kotlin.Float.Companion.parseFloat(s: String): kotlin.Float = s.toFloat()
fun kotlin.Double.Companion.parseDouble(s: String): kotlin.Double = s.toDouble()
fun kotlin.Boolean.Companion.parseBoolean(s: String?): kotlin.Boolean =
    s != null && s.equals("true", ignoreCase = true)

fun kotlin.Long.Companion.valueOf(s: String): kotlin.Long = s.toLong()
fun kotlin.Long.Companion.valueOf(v: kotlin.Long): kotlin.Long = v
fun kotlin.Float.Companion.valueOf(s: String): kotlin.Float = s.toFloat()
fun kotlin.Float.Companion.valueOf(v: kotlin.Float): kotlin.Float = v
fun kotlin.Double.Companion.valueOf(s: String): kotlin.Double = s.toDouble()
fun kotlin.Double.Companion.valueOf(v: kotlin.Double): kotlin.Double = v
fun kotlin.Boolean.Companion.valueOf(s: String?): kotlin.Boolean = parseBoolean(s)
fun kotlin.Boolean.Companion.valueOf(v: kotlin.Boolean): kotlin.Boolean = v
fun Char.Companion.valueOf(v: Char): Char = v
fun String.Companion.valueOf(v: Any?): String = v?.toString() ?: "null"
fun String.Companion.valueOf(v: CharArray): String = v.concatToString()

fun String.Companion.format(fmt: String, vararg args: Any?): String = formatJava(fmt, args)
fun String.Companion.format(locale: java.util.Locale?, fmt: String, vararg args: Any?): String =
    formatJava(fmt, args)

/** Object's members that Kotlin spells differently, or not at all. */
fun Any.getClass(): KClass<*> = this::class
fun Any.wait() {}
fun Any.wait(millis: kotlin.Long) { Thread.sleep(millis) }
fun Any.notify() {}
fun Any.notifyAll() {}
fun Any.finalize() {}

fun IntArray.clone(): IntArray = copyOf()
fun ByteArray.clone(): ByteArray = copyOf()
fun ShortArray.clone(): ShortArray = copyOf()
fun LongArray.clone(): LongArray = copyOf()
fun FloatArray.clone(): FloatArray = copyOf()
fun DoubleArray.clone(): DoubleArray = copyOf()
fun CharArray.clone(): CharArray = copyOf()
fun BooleanArray.clone(): BooleanArray = copyOf()
fun <T> Array<T>.clone(): Array<T> = copyOf()

/** java.lang.Class members, on the KClass that `Foo::class` produces. */
val KClass<*>.TYPE: KClass<*> get() = this
fun KClass<*>.getName(): String = qualifiedName ?: simpleName ?: "?"
fun KClass<*>.getSimpleName(): String = simpleName ?: "?"
fun KClass<*>.getCanonicalName(): String = qualifiedName ?: simpleName ?: "?"

/**
 * printf(3) as java.util.Formatter spells it, over the conversions this tree
 * uses: %s %d %f %e %x %X %o %c %b %n %%, with flags '-' '0' '+' ' ' ',',
 * a width and a precision.  An index ("%1$s") is not supported.
 */
internal fun formatJava(fmt: String, args: Array<out Any?>): String {
    val out = StringBuilder(fmt.length + 16)
    var i = 0
    var argi = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        if (i >= fmt.length) break
        if (fmt[i] == '%') { out.append('%'); i++; continue }
        if (fmt[i] == 'n') { out.append('\n'); i++; continue }
        var left = false; var zero = false; var plus = false; var space = false; var group = false
        loop@ while (i < fmt.length) {
            when (fmt[i]) {
                '-' -> left = true
                '0' -> zero = true
                '+' -> plus = true
                ' ' -> space = true
                ',' -> group = true
                else -> break@loop
            }
            i++
        }
        var width = 0
        while (i < fmt.length && fmt[i].isDigit()) { width = width * 10 + (fmt[i] - '0'); i++ }
        var prec = -1
        if (i < fmt.length && fmt[i] == '.') {
            i++; prec = 0
            while (i < fmt.length && fmt[i].isDigit()) { prec = prec * 10 + (fmt[i] - '0'); i++ }
        }
        // Length modifiers Java accepts but ignores.
        while (i < fmt.length && (fmt[i] == 'l' || fmt[i] == 'h')) i++
        if (i >= fmt.length) break
        val conv = fmt[i]; i++
        val arg = if (argi < args.size) args[argi++] else null
        var s = when (conv) {
            's', 'S' -> (arg?.toString() ?: "null").let { if (prec >= 0) it.take(prec) else it }
                .let { if (conv == 'S') it.uppercase() else it }
            'd' -> {
                val v = (arg as? Number)?.toLong() ?: 0L
                var t = kotlin.math.abs(v).toString()
                if (group) t = groupDigits(t)
                sign(v < 0, plus, space) + t
            }
            'f', 'F' -> {
                val v = (arg as? Number)?.toDouble() ?: 0.0
                var t = fixed(kotlin.math.abs(v), if (prec < 0) 6 else prec)
                if (group) t = groupDigits(t.substringBefore('.')) +
                    (if (t.contains('.')) "." + t.substringAfter('.') else "")
                sign(v < 0, plus, space) + t
            }
            'e', 'E' -> {
                val v = (arg as? Number)?.toDouble() ?: 0.0
                sci(v, if (prec < 0) 6 else prec).let { if (conv == 'E') it.uppercase() else it }
            }
            'x' -> ((arg as? Number)?.toLong() ?: 0L).toULong().toString(16)
            'X' -> ((arg as? Number)?.toLong() ?: 0L).toULong().toString(16).uppercase()
            'o' -> ((arg as? Number)?.toLong() ?: 0L).toULong().toString(8)
            'c' -> (arg as? Char)?.toString() ?: ((arg as? Number)?.toInt()?.toChar()?.toString() ?: "")
            'b', 'B' -> (arg != null && arg != false).toString()
            else -> arg?.toString() ?: ""
        }
        if (s.length < width) {
            s = when {
                left -> s + " ".repeat(width - s.length)
                zero && (conv == 'd' || conv == 'f' || conv == 'F' || conv == 'x' || conv == 'X') -> {
                    val neg = s.startsWith('-') || s.startsWith('+')
                    if (neg) s[0] + "0".repeat(width - s.length) + s.substring(1)
                    else "0".repeat(width - s.length) + s
                }
                else -> " ".repeat(width - s.length) + s
            }
        }
        out.append(s)
    }
    return out.toString()
}

private fun sign(neg: kotlin.Boolean, plus: kotlin.Boolean, space: kotlin.Boolean): String =
    if (neg) "-" else if (plus) "+" else if (space) " " else ""

private fun groupDigits(d: String): String {
    val sb = StringBuilder()
    for ((n, ch) in d.withIndex()) {
        if (n > 0 && (d.length - n) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

/** Half-up decimal rendering with an exact digit count, as %f produces. */
private fun fixed(v: kotlin.Double, digits: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return "Infinity"
    if (digits == 0) return kotlin.math.floor(v + 0.5).toLong().toString()
    var scale = 1.0
    repeat(digits) { scale *= 10.0 }
    val scaled = kotlin.math.floor(v * scale + 0.5)
    val whole = kotlin.math.floor(scaled / scale).toLong()
    val frac = (scaled - whole * scale).toLong()
    return "$whole." + frac.toString().padStart(digits, '0')
}

private fun sci(v: kotlin.Double, digits: Int): String {
    if (v == 0.0) return fixed(0.0, digits) + "e+00"
    val neg = v < 0
    var a = kotlin.math.abs(v)
    var exp = 0
    while (a >= 10.0) { a /= 10.0; exp++ }
    while (a < 1.0) { a *= 10.0; exp-- }
    val e = (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
    return (if (neg) "-" else "") + fixed(a, digits) + "e" + e
}
