/* android.util.Log to stderr in logcat's shape, plus the small containers.
 *
 * Range/Rational/Size/SizeF/Pair are the camera lane's, not here. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.util

import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr

object Log {
    const val VERBOSE: Int = 2
    const val DEBUG: Int = 3
    const val INFO: Int = 4
    const val WARN: Int = 5
    const val ERROR: Int = 6
    const val ASSERT: Int = 7

    /** Below this level nothing is printed; $PHOTONCAMERA_LOG raises or lowers it. */
    var minLevel: Int = java.lang.System.getenv("PHOTONCAMERA_LOG")?.toIntOrNull() ?: VERBOSE

    fun v(tag: String?, msg: String?): Int = println(VERBOSE, tag, msg, null)
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = println(VERBOSE, tag, msg, tr)
    fun d(tag: String?, msg: String?): Int = println(DEBUG, tag, msg, null)
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = println(DEBUG, tag, msg, tr)
    fun i(tag: String?, msg: String?): Int = println(INFO, tag, msg, null)
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = println(INFO, tag, msg, tr)
    fun w(tag: String?, msg: String?): Int = println(WARN, tag, msg, null)
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = println(WARN, tag, msg, tr)
    fun w(tag: String?, tr: Throwable?): Int = println(WARN, tag, null, tr)
    fun e(tag: String?, msg: String?): Int = println(ERROR, tag, msg, null)
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = println(ERROR, tag, msg, tr)
    fun wtf(tag: String?, msg: String?): Int = println(ASSERT, tag, msg, null)
    fun isLoggable(tag: String?, level: Int): Boolean = level >= minLevel

    fun getStackTraceString(tr: Throwable?): String =
        if (tr == null) "" else tr.stackTraceToString()

    fun println(priority: Int, tag: String?, msg: String?): Int = println(priority, tag, msg, null)

    private fun println(priority: Int, tag: String?, msg: String?, tr: Throwable?): Int {
        if (priority < minLevel) return 0
        val letter = when (priority) {
            VERBOSE -> "V"; DEBUG -> "D"; INFO -> "I"
            WARN -> "W"; ERROR -> "E"; else -> "A"
        }
        val body = buildString {
            append(msg ?: "")
            if (tr != null) { if (isNotEmpty()) append('\n'); append(tr.stackTraceToString()) }
        }
        fprintf(stderr, "%s\n", "$letter/${tag ?: ""}: $body")
        fflush(stderr)
        return body.length
    }
}

/** android.util.SparseArray: a LinkedHashMap kept in key order, as AOSP's is. */
class SparseArray<E> {
    private val map = LinkedHashMap<Int, E>()

    fun get(key: Int): E? = map[key]
    fun get(key: Int, valueIfKeyNotFound: E): E = map[key] ?: valueIfKeyNotFound
    fun put(key: Int, value: E) { map[key] = value; resort() }
    fun append(key: Int, value: E) { map[key] = value }
    fun remove(key: Int) { map.remove(key) }
    fun delete(key: Int) { map.remove(key) }
    fun clear() = map.clear()
    fun size(): Int = map.size
    fun keyAt(index: Int): Int = map.keys.elementAt(index)
    fun valueAt(index: Int): E = map.values.elementAt(index)
    fun indexOfKey(key: Int): Int = map.keys.indexOf(key)
    fun indexOfValue(value: E): Int = map.values.indexOf(value)
    fun contains(key: Int): Boolean = map.containsKey(key)

    private fun resort() {
        val sorted = map.entries.sortedBy { it.key }.map { it.key to it.value }
        map.clear()
        for ((k, v) in sorted) map[k] = v
    }

    override fun toString(): String = map.toString()
}

class SparseIntArray {
    private val inner = SparseArray<Int>()
    fun get(key: Int): Int = inner.get(key, 0)
    fun get(key: Int, def: Int): Int = inner.get(key, def)
    fun put(key: Int, value: Int) = inner.put(key, value)
    fun append(key: Int, value: Int) = inner.append(key, value)
    fun delete(key: Int) = inner.delete(key)
    fun clear() = inner.clear()
    fun size(): Int = inner.size()
    fun keyAt(index: Int): Int = inner.keyAt(index)
    fun valueAt(index: Int): Int = inner.valueAt(index)
}

/** A least-recently-used map, AOSP's eviction order. */
open class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    open fun sizeOf(key: K, value: V): Int = 1
    open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    fun put(key: K, value: V): V? {
        val prev = map.put(key, value)
        trim()
        return prev
    }

    fun remove(key: K): V? = map.remove(key)
    fun size(): Int = map.values.sumOf { v -> map.keys.first().let { sizeOf(it, v) } }
    fun maxSize(): Int = maxSize
    fun evictAll() { for ((k, v) in LinkedHashMap(map)) { map.remove(k); entryRemoved(true, k, v, null) } }
    fun snapshot(): Map<K, V> = LinkedHashMap(map)

    private fun trim() {
        while (map.size > maxSize) {
            val k = map.keys.first()
            val v = map.remove(k) ?: break
            entryRemoved(true, k, v, null)
        }
    }
}

class DisplayMetrics {
    var widthPixels: Int = 0
    var heightPixels: Int = 0
    var density: Float = 1f
    var densityDpi: Int = 160
    var scaledDensity: Float = 1f
    var xdpi: Float = 160f
    var ydpi: Float = 160f

    companion object {
        const val DENSITY_DEFAULT: Int = 160
    }
}

open class AndroidException(message: String? = null) : Exception(message)
open class AndroidRuntimeException(message: String? = null) : RuntimeException(message)
