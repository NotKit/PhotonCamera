/* java.util: the collection spellings Kotlin already owns under another name,
 * and the static helpers it does not have. */
@file:Suppress("unused")

package java.util

typealias ArrayList<E> = kotlin.collections.ArrayList<E>
typealias HashMap<K, V> = kotlin.collections.HashMap<K, V>
typealias LinkedHashMap<K, V> = kotlin.collections.LinkedHashMap<K, V>
typealias HashSet<E> = kotlin.collections.HashSet<E>
typealias LinkedHashSet<E> = kotlin.collections.LinkedHashSet<E>
// Read-only, deliberately: j2k maps Java's List/Map/Set onto Kotlin's
// read-only interfaces and gets mutation from photoncam.knshim's extensions.
typealias List<E> = kotlin.collections.List<E>
typealias Map<K, V> = kotlin.collections.Map<K, V>
typealias Set<E> = kotlin.collections.Set<E>
typealias Collection<E> = kotlin.collections.Collection<E>
typealias Iterator<E> = kotlin.collections.Iterator<E>
typealias Comparator<T> = kotlin.Comparator<T>
typealias NoSuchElementException = kotlin.NoSuchElementException
typealias ConcurrentModificationException = kotlin.ConcurrentModificationException

/** java.util.LinkedList / ArrayDeque / Queue, all onto Kotlin's ArrayDeque. */
typealias LinkedList<E> = kotlin.collections.ArrayDeque<E>
typealias ArrayDeque<E> = kotlin.collections.ArrayDeque<E>
typealias Queue<E> = kotlin.collections.ArrayDeque<E>
typealias Deque<E> = kotlin.collections.ArrayDeque<E>

object Arrays {
    fun toString(a: IntArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: LongArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: ShortArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: ByteArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: FloatArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: DoubleArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: BooleanArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: CharArray?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun toString(a: Array<*>?): String = a?.joinToString(", ", "[", "]") ?: "null"
    fun deepToString(a: Array<*>?): String = a?.joinToString(", ", "[", "]") {
        if (it is Array<*>) deepToString(it) else it.toString()
    } ?: "null"

    fun <T> asList(vararg items: T): kotlin.collections.MutableList<T> = items.toMutableList()

    fun sort(a: IntArray) = a.sort()
    fun sort(a: LongArray) = a.sort()
    fun sort(a: FloatArray) = a.sort()
    fun sort(a: DoubleArray) = a.sort()
    fun sort(a: ShortArray) = a.sort()
    fun sort(a: ByteArray) = a.sort()
    fun sort(a: CharArray) = a.sort()
    fun <T : kotlin.Comparable<T>> sort(a: Array<T>) = a.sort()
    fun <T> sort(a: Array<T>, c: kotlin.Comparator<in T>) = a.sortWith(c)
    fun sort(a: IntArray, from: Int, to: Int) = a.sort(from, to)
    fun sort(a: FloatArray, from: Int, to: Int) = a.sort(from, to)
    fun sort(a: DoubleArray, from: Int, to: Int) = a.sort(from, to)

    fun copyOf(a: IntArray, n: Int): IntArray = a.copyOf(n)
    fun copyOf(a: FloatArray, n: Int): FloatArray = a.copyOf(n)
    fun copyOf(a: ByteArray, n: Int): ByteArray = a.copyOf(n)
    fun copyOf(a: DoubleArray, n: Int): DoubleArray = a.copyOf(n)
    fun <T> copyOf(a: Array<T>, n: Int): Array<T?> = a.copyOf(n)

    fun copyOfRange(a: IntArray, from: Int, to: Int): IntArray = a.copyOfRange(from, to)
    fun copyOfRange(a: LongArray, from: Int, to: Int): LongArray = a.copyOfRange(from, to)
    fun copyOfRange(a: ShortArray, from: Int, to: Int): ShortArray = a.copyOfRange(from, to)
    fun copyOfRange(a: ByteArray, from: Int, to: Int): ByteArray = a.copyOfRange(from, to)
    fun copyOfRange(a: FloatArray, from: Int, to: Int): FloatArray = a.copyOfRange(from, to)
    fun copyOfRange(a: DoubleArray, from: Int, to: Int): DoubleArray = a.copyOfRange(from, to)
    fun copyOfRange(a: CharArray, from: Int, to: Int): CharArray = a.copyOfRange(from, to)
    fun <T> copyOfRange(a: Array<T>, from: Int, to: Int): Array<T> = a.copyOfRange(from, to)

    fun fill(a: IntArray, v: Int) = a.fill(v)
    fun fill(a: LongArray, v: Long) = a.fill(v)
    fun fill(a: FloatArray, v: Float) = a.fill(v)
    fun fill(a: DoubleArray, v: Double) = a.fill(v)
    fun fill(a: ByteArray, v: Byte) = a.fill(v)
    fun fill(a: ShortArray, v: Short) = a.fill(v)
    fun fill(a: BooleanArray, v: Boolean) = a.fill(v)
    fun <T> fill(a: Array<T>, v: T) = a.fill(v)
    fun fill(a: IntArray, from: Int, to: Int, v: Int) = a.fill(v, from, to)
    fun fill(a: FloatArray, from: Int, to: Int, v: Float) = a.fill(v, from, to)

    fun equals(a: IntArray?, b: IntArray?): Boolean = a.contentEquals(b)
    fun equals(a: FloatArray?, b: FloatArray?): Boolean = a.contentEquals(b)
    fun equals(a: ByteArray?, b: ByteArray?): Boolean = a.contentEquals(b)
    fun equals(a: Array<*>?, b: Array<*>?): Boolean = a.contentEquals(b)

    fun hashCode(a: IntArray?): Int = a.contentHashCode()
    fun hashCode(a: Array<*>?): Int = a.contentHashCode()

    fun binarySearch(a: IntArray, key: Int): Int = a.toList().binarySearch(key)
    fun stream(a: IntArray): kotlin.collections.List<Int> = a.toList()
}

/** java.util.Random over kotlin.random. */
class Random(seed: Long? = null) {
    private val r = if (seed == null) kotlin.random.Random.Default else kotlin.random.Random(seed)
    fun nextInt(): Int = r.nextInt()
    fun nextInt(bound: Int): Int = r.nextInt(bound)
    fun nextLong(): Long = r.nextLong()
    fun nextFloat(): Float = r.nextFloat()
    fun nextDouble(): Double = r.nextDouble()
    fun nextBoolean(): Boolean = r.nextBoolean()
    fun nextGaussian(): Double {
        var u: Double; var v: Double; var s: Double
        do { u = r.nextDouble() * 2 - 1; v = r.nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s)
    }
}

object Objects {
    fun equals(a: Any?, b: Any?): Boolean = a == b
    fun hash(vararg values: Any?): Int = values.contentHashCode()
    fun hashCode(o: Any?): Int = o?.hashCode() ?: 0
    fun toString(o: Any?): String = o.toString()
    fun <T : Any> requireNonNull(o: T?): T = o ?: throw NullPointerException()
    fun <T : Any> requireNonNull(o: T?, msg: String): T = o ?: throw NullPointerException(msg)
    fun isNull(o: Any?): Boolean = o == null
    fun nonNull(o: Any?): Boolean = o != null
}

/** java.util.Date: epoch milliseconds, which is all this tree reads off one. */
class Date(private val millis: Long = java.lang.System.currentTimeMillis()) : kotlin.Comparable<Date> {
    fun getTime(): Long = millis
    fun before(other: Date): Boolean = millis < other.millis
    fun after(other: Date): Boolean = millis > other.millis
    override fun compareTo(other: Date): Int = millis.compareTo(other.millis)
    override fun equals(other: Any?): Boolean = other is Date && other.millis == millis
    override fun hashCode(): Int = millis.hashCode()
    override fun toString(): String = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy").format(this)
}

/** A .properties file, read and written in the JDK's key=value form. */
class Properties {
    private val map = LinkedHashMap<String, String>()

    fun getProperty(key: String): String? = map[key]
    fun getProperty(key: String, def: String): String = map[key] ?: def
    fun setProperty(key: String, value: String): String? = map.put(key, value)
    fun stringPropertyNames(): kotlin.collections.MutableSet<String> = map.keys
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String): String? = map.remove(key)
    fun clear() = map.clear()
    fun size(): Int = map.size

    fun load(input: java.io.InputStream) = load(input.bufferedReader().readText())
    fun load(reader: java.io.Reader) = load(reader.readText())

    private fun load(text: String) {
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val i = line.indexOfFirst { it == '=' || it == ':' }
            if (i < 0) map[line] = "" else map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
    }

    fun store(out: java.io.OutputStream, comment: String?) {
        val sb = StringBuilder()
        if (comment != null) sb.append("#").append(comment).append('\n')
        for ((k, v) in map) sb.append(k).append('=').append(v).append('\n')
        out.write(sb.toString().encodeToByteArray())
    }
}

/** java.util.Observable/Observer: the pre-Java-9 observer pair, still used by
 *  the camera UI models.  setChanged/notifyObservers is the JDK's protocol. */
interface Observer {
    fun update(o: Observable?, arg: Any?)
}

open class Observable {
    private val observers = kotlin.collections.ArrayList<Observer>()
    private var changed = false

    fun addObserver(o: Observer?) { if (o != null && o !in observers) observers.add(o) }
    fun deleteObserver(o: Observer?) { observers.remove(o) }
    fun deleteObservers() = observers.clear()
    fun countObservers(): Int = observers.size
    protected fun setChanged() { changed = true }
    protected fun clearChanged() { changed = false }
    fun hasChanged(): Boolean = changed

    open fun notifyObservers() = notifyObservers(null)

    open fun notifyObservers(arg: Any?) {
        if (!changed) return
        changed = false
        for (o in kotlin.collections.ArrayList(observers)) o.update(this, arg)
    }
}

/**
 * java.util.Scanner over a string or a stream, as far as the app parses one:
 * whitespace-delimited tokens by default, a custom delimiter, and nextLine().
 */
class Scanner {
    private val text: String
    private var pos = 0
    private var delimiter = Regex("\\s+")

    constructor(source: String) { text = source }
    constructor(source: java.io.InputStream) { text = source.bufferedReader().readText() }
    constructor(source: java.io.File) { text = java.io.FileInputStream(source).use { it.readBytes() }.decodeToString() }

    fun useDelimiter(pattern: String): Scanner { delimiter = Regex(pattern); return this }
    fun useLocale(locale: Locale): Scanner = this
    fun close() { pos = text.length }

    private fun peekToken(): Pair<String, Int>? {
        var start = pos
        val skip = delimiter.find(text, start)
        if (skip != null && skip.range.first == start) start = skip.range.last + 1
        if (start >= text.length) return null
        val next = delimiter.find(text, start)
        val end = next?.range?.first ?: text.length
        return text.substring(start, end) to end
    }

    fun hasNext(): Boolean = peekToken() != null
    fun hasNextLine(): Boolean = pos < text.length
    fun hasNextInt(): Boolean = peekToken()?.first?.toIntOrNull() != null
    fun hasNextLong(): Boolean = peekToken()?.first?.toLongOrNull() != null
    fun hasNextFloat(): Boolean = peekToken()?.first?.toFloatOrNull() != null
    fun hasNextDouble(): Boolean = peekToken()?.first?.toDoubleOrNull() != null

    fun next(): String {
        val (token, end) = peekToken() ?: throw NoSuchElementException()
        pos = end
        return token
    }

    fun nextInt(): Int = next().toInt()
    fun nextLong(): Long = next().toLong()
    fun nextFloat(): Float = next().toFloat()
    fun nextDouble(): Double = next().toDouble()
    fun nextBoolean(): Boolean = next().toBoolean()

    fun nextLine(): String {
        if (pos >= text.length) throw NoSuchElementException()
        val nl = text.indexOf('\n', pos)
        val line = if (nl < 0) text.substring(pos) else text.substring(pos, nl)
        pos = if (nl < 0) text.length else nl + 1
        return line.removeSuffix("\r")
    }
}
