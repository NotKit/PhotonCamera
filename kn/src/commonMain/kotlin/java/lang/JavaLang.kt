/* java.lang for the Kotlin/Native port: the members the converted app names.
 * Kotlin owns the primitive types, so Java's boxed statics live on the
 * companions; java.lang.Math maps onto kotlin.math. */
@file:Suppress("NOTHING_TO_INLINE", "unused")

package java.lang

import kotlin.math.pow

object Math {
    const val PI: Double = kotlin.math.PI
    const val E: Double = kotlin.math.E

    fun abs(a: Int): Int = if (a < 0) -a else a
    fun abs(a: Long): Long = if (a < 0) -a else a
    fun abs(a: Float): Float = kotlin.math.abs(a)
    fun abs(a: Double): Double = kotlin.math.abs(a)

    fun max(a: Int, b: Int): Int = if (a >= b) a else b
    fun max(a: Long, b: Long): Long = if (a >= b) a else b
    fun max(a: Float, b: Float): Float = kotlin.math.max(a, b)
    fun max(a: Double, b: Double): Double = kotlin.math.max(a, b)
    fun min(a: Int, b: Int): Int = if (a <= b) a else b
    fun min(a: Long, b: Long): Long = if (a <= b) a else b
    fun min(a: Float, b: Float): Float = kotlin.math.min(a, b)
    fun min(a: Double, b: Double): Double = kotlin.math.min(a, b)

    // Java's round: float -> Int, double -> Long.  Java rounds half UP (towards
    // +inf), kotlin's roundToInt rounds half away from zero; they differ only on
    // negative halves, so do it the JDK's way.
    fun round(a: Float): Int = kotlin.math.floor(a.toDouble() + 0.5).toInt()
    fun round(a: Double): Long = kotlin.math.floor(a + 0.5).toLong()

    fun ceil(a: Double): Double = kotlin.math.ceil(a)
    fun floor(a: Double): Double = kotlin.math.floor(a)
    fun rint(a: Double): Double = kotlin.math.round(a)
    fun sqrt(a: Double): Double = kotlin.math.sqrt(a)
    fun cbrt(a: Double): Double = kotlin.math.cbrt(a)
    fun pow(a: Double, b: Double): Double = a.pow(b)
    fun exp(a: Double): Double = kotlin.math.exp(a)
    fun log(a: Double): Double = kotlin.math.ln(a)
    fun log10(a: Double): Double = kotlin.math.log10(a)
    fun log2(a: Double): Double = kotlin.math.log2(a)
    fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
    fun signum(a: Double): Double = kotlin.math.sign(a)
    fun signum(a: Float): Float = kotlin.math.sign(a)

    fun sin(a: Double): Double = kotlin.math.sin(a)
    fun cos(a: Double): Double = kotlin.math.cos(a)
    fun tan(a: Double): Double = kotlin.math.tan(a)
    fun asin(a: Double): Double = kotlin.math.asin(a)
    fun acos(a: Double): Double = kotlin.math.acos(a)
    fun atan(a: Double): Double = kotlin.math.atan(a)
    fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
    fun sinh(a: Double): Double = kotlin.math.sinh(a)
    fun cosh(a: Double): Double = kotlin.math.cosh(a)
    fun tanh(a: Double): Double = kotlin.math.tanh(a)

    fun toRadians(deg: Double): Double = deg / 180.0 * PI
    fun toDegrees(rad: Double): Double = rad * 180.0 / PI

    fun random(): Double = kotlin.random.Random.nextDouble()

    fun floorDiv(x: Int, y: Int): Int = kotlin.math.floor(x.toDouble() / y).toInt()
    fun floorMod(x: Int, y: Int): Int = x - floorDiv(x, y) * y

    /* Java's binary numeric promotion, which Kotlin has not got: a float or an
     * int argument widens to double and the RESULT IS DOUBLE, so a call site
     * that wants a float still writes the cast its Java had.  Keeping that
     * shape is what makes the A/B harness's float rounding match. */
    fun sqrt(a: Float): Double = sqrt(a.toDouble())
    fun sqrt(a: Int): Double = sqrt(a.toDouble())
    fun sqrt(a: Long): Double = sqrt(a.toDouble())
    fun cbrt(a: Float): Double = cbrt(a.toDouble())
    fun cbrt(a: Int): Double = cbrt(a.toDouble())
    fun cbrt(a: Long): Double = cbrt(a.toDouble())
    fun exp(a: Float): Double = exp(a.toDouble())
    fun exp(a: Int): Double = exp(a.toDouble())
    fun exp(a: Long): Double = exp(a.toDouble())
    fun log(a: Float): Double = log(a.toDouble())
    fun log(a: Int): Double = log(a.toDouble())
    fun log(a: Long): Double = log(a.toDouble())
    fun log10(a: Float): Double = log10(a.toDouble())
    fun log10(a: Int): Double = log10(a.toDouble())
    fun log10(a: Long): Double = log10(a.toDouble())
    fun sin(a: Float): Double = sin(a.toDouble())
    fun sin(a: Int): Double = sin(a.toDouble())
    fun sin(a: Long): Double = sin(a.toDouble())
    fun cos(a: Float): Double = cos(a.toDouble())
    fun cos(a: Int): Double = cos(a.toDouble())
    fun cos(a: Long): Double = cos(a.toDouble())
    fun tan(a: Float): Double = tan(a.toDouble())
    fun tan(a: Int): Double = tan(a.toDouble())
    fun tan(a: Long): Double = tan(a.toDouble())
    fun asin(a: Float): Double = asin(a.toDouble())
    fun asin(a: Int): Double = asin(a.toDouble())
    fun asin(a: Long): Double = asin(a.toDouble())
    fun acos(a: Float): Double = acos(a.toDouble())
    fun acos(a: Int): Double = acos(a.toDouble())
    fun acos(a: Long): Double = acos(a.toDouble())
    fun atan(a: Float): Double = atan(a.toDouble())
    fun atan(a: Int): Double = atan(a.toDouble())
    fun atan(a: Long): Double = atan(a.toDouble())
    fun sinh(a: Float): Double = sinh(a.toDouble())
    fun sinh(a: Int): Double = sinh(a.toDouble())
    fun sinh(a: Long): Double = sinh(a.toDouble())
    fun cosh(a: Float): Double = cosh(a.toDouble())
    fun cosh(a: Int): Double = cosh(a.toDouble())
    fun cosh(a: Long): Double = cosh(a.toDouble())
    fun tanh(a: Float): Double = tanh(a.toDouble())
    fun tanh(a: Int): Double = tanh(a.toDouble())
    fun tanh(a: Long): Double = tanh(a.toDouble())
    fun ceil(a: Float): Double = ceil(a.toDouble())
    fun ceil(a: Int): Double = ceil(a.toDouble())
    fun ceil(a: Long): Double = ceil(a.toDouble())
    fun floor(a: Float): Double = floor(a.toDouble())
    fun floor(a: Int): Double = floor(a.toDouble())
    fun floor(a: Long): Double = floor(a.toDouble())
    fun rint(a: Float): Double = rint(a.toDouble())
    fun rint(a: Int): Double = rint(a.toDouble())
    fun rint(a: Long): Double = rint(a.toDouble())
    fun toRadians(a: Float): Double = toRadians(a.toDouble())
    fun toRadians(a: Int): Double = toRadians(a.toDouble())
    fun toRadians(a: Long): Double = toRadians(a.toDouble())
    fun toDegrees(a: Float): Double = toDegrees(a.toDouble())
    fun toDegrees(a: Int): Double = toDegrees(a.toDouble())
    fun toDegrees(a: Long): Double = toDegrees(a.toDouble())
    fun pow(a: Float, b: Float): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Float, b: Double): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Double, b: Float): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Int, b: Int): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Double, b: Int): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Int, b: Double): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Float, b: Int): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Int, b: Float): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Long, b: Long): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Double, b: Long): Double = pow(a.toDouble(), b.toDouble())
    fun pow(a: Long, b: Double): Double = pow(a.toDouble(), b.toDouble())
    fun hypot(x: Float, y: Float): Double = hypot(x.toDouble(), y.toDouble())
    fun hypot(x: Int, y: Int): Double = hypot(x.toDouble(), y.toDouble())
    fun atan2(y: Float, x: Float): Double = atan2(y.toDouble(), x.toDouble())
    fun atan2(y: Int, x: Int): Double = atan2(y.toDouble(), x.toDouble())
    fun signum(a: Int): Float = kotlin.math.sign(a.toFloat())
    fun abs(a: Short): Int = if (a < 0) -a.toInt() else a.toInt()
    fun abs(a: Byte): Int = if (a < 0) -a.toInt() else a.toInt()
    fun round(a: Int): Int = a
    fun round(a: Long): Long = a
    fun min(a: Short, b: Short): Int = min(a.toInt(), b.toInt())
    fun min(a: Short, b: Int): Int = min(a.toInt(), b.toInt())
    fun min(a: Short, b: Long): Long = min(a.toLong(), b.toLong())
    fun min(a: Short, b: Float): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Short, b: Double): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Int, b: Short): Int = min(a.toInt(), b.toInt())
    fun min(a: Int, b: Long): Long = min(a.toLong(), b.toLong())
    fun min(a: Int, b: Float): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Int, b: Double): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Long, b: Short): Long = min(a.toLong(), b.toLong())
    fun min(a: Long, b: Int): Long = min(a.toLong(), b.toLong())
    fun min(a: Long, b: Float): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Long, b: Double): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Float, b: Short): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Float, b: Int): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Float, b: Long): Float = min(a.toFloat(), b.toFloat())
    fun min(a: Float, b: Double): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Double, b: Short): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Double, b: Int): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Double, b: Long): Double = min(a.toDouble(), b.toDouble())
    fun min(a: Double, b: Float): Double = min(a.toDouble(), b.toDouble())
    fun max(a: Short, b: Short): Int = max(a.toInt(), b.toInt())
    fun max(a: Short, b: Int): Int = max(a.toInt(), b.toInt())
    fun max(a: Short, b: Long): Long = max(a.toLong(), b.toLong())
    fun max(a: Short, b: Float): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Short, b: Double): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Int, b: Short): Int = max(a.toInt(), b.toInt())
    fun max(a: Int, b: Long): Long = max(a.toLong(), b.toLong())
    fun max(a: Int, b: Float): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Int, b: Double): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Long, b: Short): Long = max(a.toLong(), b.toLong())
    fun max(a: Long, b: Int): Long = max(a.toLong(), b.toLong())
    fun max(a: Long, b: Float): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Long, b: Double): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Float, b: Short): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Float, b: Int): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Float, b: Long): Float = max(a.toFloat(), b.toFloat())
    fun max(a: Float, b: Double): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Double, b: Short): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Double, b: Int): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Double, b: Long): Double = max(a.toDouble(), b.toDouble())
    fun max(a: Double, b: Float): Double = max(a.toDouble(), b.toDouble())
}


object Integer {
    val TYPE: kotlin.reflect.KClass<*> get() = Int::class
    const val MAX_VALUE: Int = Int.MAX_VALUE
    const val MIN_VALUE: Int = Int.MIN_VALUE
    const val SIZE: Int = 32
    const val BYTES: Int = 4

    fun parseInt(s: String): Int = s.toInt()
    fun parseInt(s: String, radix: Int): Int = s.toInt(radix)
    fun valueOf(s: String): Int = s.toInt()
    fun valueOf(i: Int): Int = i
    fun toString(i: Int): String = i.toString()
    fun toString(i: Int, radix: Int): String = i.toString(radix)
    fun toHexString(i: Int): String = i.toUInt().toString(16)
    fun toBinaryString(i: Int): String = i.toUInt().toString(2)
    fun toOctalString(i: Int): String = i.toUInt().toString(8)
    fun compare(a: Int, b: Int): Int = a.compareTo(b)
    fun max(a: Int, b: Int): Int = if (a >= b) a else b
    fun min(a: Int, b: Int): Int = if (a <= b) a else b
    fun signum(i: Int): Int = if (i > 0) 1 else if (i < 0) -1 else 0
    fun bitCount(i: Int): Int = i.countOneBits()
    fun highestOneBit(i: Int): Int = i.takeHighestOneBit()
    fun numberOfLeadingZeros(i: Int): Int = i.countLeadingZeroBits()
    fun numberOfTrailingZeros(i: Int): Int = i.countTrailingZeroBits()
    fun reverse(i: Int): Int = i.toUInt().toString(2).padStart(32, '0').reversed().toUInt(2).toInt()
    fun decode(s: String): Int = when {
        s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toInt(16)
        s.startsWith("#") -> s.substring(1).toInt(16)
        else -> s.toInt()
    }
}







object Character {
    const val MAX_VALUE: Char = Char.MAX_VALUE
    const val MIN_VALUE: Char = Char.MIN_VALUE
    fun isDigit(c: Char): kotlin.Boolean = c.isDigit()
    fun isLetter(c: Char): kotlin.Boolean = c.isLetter()
    fun isLetterOrDigit(c: Char): kotlin.Boolean = c.isLetterOrDigit()
    fun isWhitespace(c: Char): kotlin.Boolean = c.isWhitespace()
    fun isUpperCase(c: Char): kotlin.Boolean = c.isUpperCase()
    fun isLowerCase(c: Char): kotlin.Boolean = c.isLowerCase()
    fun toUpperCase(c: Char): Char = c.uppercaseChar()
    fun toLowerCase(c: Char): Char = c.lowercaseChar()
    fun toString(c: Char): String = c.toString()
    fun valueOf(c: Char): Char = c
    fun digit(c: Char, radix: Int): Int = c.digitToIntOrNull(radix) ?: -1
    /** '\u0000' outside the radix, as Java does. */
    fun forDigit(digit: Int, radix: Int): Char =
        if (radix !in 2..36 || digit !in 0 until radix) '\u0000' else digit.digitToChar(radix)
}

typealias Void = Unit
typealias AutoCloseable = kotlin.AutoCloseable
/** A fun interface, not a function type: the converted tree writes both
 *  `object : Runnable { override fun run() }` and a bare lambda. */
fun interface Runnable { fun run() }
typealias StringBuffer = kotlin.text.StringBuilder
typealias RuntimeException = kotlin.RuntimeException
class SecurityException(message: String? = null) : RuntimeException(message)
typealias NumberFormatException = kotlin.NumberFormatException
typealias IllegalArgumentException = kotlin.IllegalArgumentException
typealias IllegalStateException = kotlin.IllegalStateException
typealias UnsupportedOperationException = kotlin.UnsupportedOperationException
typealias IndexOutOfBoundsException = kotlin.IndexOutOfBoundsException
typealias ArrayIndexOutOfBoundsException = kotlin.IndexOutOfBoundsException
typealias NullPointerException = kotlin.NullPointerException
typealias ClassCastException = kotlin.ClassCastException
typealias ArithmeticException = kotlin.ArithmeticException
typealias OutOfMemoryError = kotlin.OutOfMemoryError
typealias StackOverflowError = kotlin.Error
typealias UnsatisfiedLinkError = kotlin.Error

class InterruptedException(message: String? = null) : Exception(message)
class ClassNotFoundException(message: String? = null) : Exception(message)
class NoSuchMethodException(message: String? = null) : Exception(message)
class NoSuchFieldException(message: String? = null) : Exception(message)
class IllegalAccessException(message: String? = null) : Exception(message)
class InstantiationException(message: String? = null) : Exception(message)
class CloneNotSupportedException(message: String? = null) : Exception(message)

/** A class token.  Kotlin/Native has no reflection, so this carries a name and
 *  identity only; the tunable/sensor-config walkers that wanted more are seams. */
typealias Class<T> = kotlin.reflect.KClass<*>

class StackTraceElement(
    private val cls: String,
    private val method: String,
    private val file: String?,
    private val line: Int,
) {
    fun getClassName(): String = cls
    fun getMethodName(): String = method
    fun getFileName(): String? = file
    fun getLineNumber(): Int = line
    override fun toString(): String = "$cls.$method($file:$line)"
}

class ThreadLocal<T>(private val initial: (() -> T)? = null) {
    private var value: T? = null
    private var made = false

    fun get(): T? {
        if (!made) { value = initial?.invoke(); made = true }
        return value
    }

    fun set(v: T?) { value = v; made = true }
    fun remove() { value = null; made = false }

    companion object {
        fun <T> withInitial(supplier: () -> T): ThreadLocal<T> = ThreadLocal(supplier)
    }
}
