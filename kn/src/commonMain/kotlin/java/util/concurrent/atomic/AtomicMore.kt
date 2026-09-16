/* AtomicLong and AtomicReference, beside fenix-kn's AtomicInteger/AtomicBoolean. */
package java.util.concurrent.atomic

import kotlin.concurrent.AtomicLong as KAtomicLong
import kotlin.concurrent.AtomicReference as KAtomicReference

class AtomicLong(initialValue: Long = 0L) {
    private val cell = KAtomicLong(initialValue)
    fun get(): Long = cell.value
    fun set(v: Long) { cell.value = v }
    fun incrementAndGet(): Long = cell.incrementAndGet()
    fun decrementAndGet(): Long = cell.decrementAndGet()
    fun getAndIncrement(): Long = cell.getAndIncrement()
    fun addAndGet(delta: Long): Long = cell.addAndGet(delta)
    fun getAndSet(v: Long): Long = cell.getAndSet(v)
    fun compareAndSet(expect: Long, update: Long): Boolean = cell.compareAndSet(expect, update)
    override fun toString(): String = get().toString()
}

class AtomicReference<V>(initialValue: V? = null) {
    private val cell = KAtomicReference<V?>(initialValue)
    fun get(): V? = cell.value
    fun set(v: V?) { cell.value = v }
    fun getAndSet(v: V?): V? = cell.getAndSet(v)
    fun compareAndSet(expect: V?, update: V?): Boolean = cell.compareAndSet(expect, update)
    override fun toString(): String = get().toString()
}
