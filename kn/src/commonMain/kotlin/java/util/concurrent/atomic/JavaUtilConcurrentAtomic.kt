/* Copied from fenix-kn's androidshim/JavaUtilConcurrentAtomic.kt. */
package java.util.concurrent.atomic

import kotlin.concurrent.AtomicInt

/** java.util.concurrent.atomic.AtomicInteger, on kotlin.concurrent.AtomicInt. */
class AtomicInteger(initialValue: Int = 0) {
	private val cell = AtomicInt(initialValue)

	fun get(): Int = cell.value
	fun set(newValue: Int) { cell.value = newValue }
	fun incrementAndGet(): Int = cell.incrementAndGet()
	fun decrementAndGet(): Int = cell.decrementAndGet()
	fun getAndIncrement(): Int = cell.getAndIncrement()
	fun addAndGet(delta: Int): Int = cell.addAndGet(delta)
	fun compareAndSet(expect: Int, update: Int): Boolean = cell.compareAndSet(expect, update)
	override fun toString(): String = get().toString()
}

/** java.util.concurrent.atomic.AtomicBoolean: an AtomicInt holding 0 or 1. */
class AtomicBoolean(initialValue: Boolean = false) {
	private val cell = AtomicInt(if (initialValue) 1 else 0)

	fun get(): Boolean = cell.value != 0
	fun set(newValue: Boolean) { cell.value = if (newValue) 1 else 0 }
	fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
		cell.compareAndSet(if (expect) 1 else 0, if (update) 1 else 0)
	fun getAndSet(newValue: Boolean): Boolean = cell.getAndSet(if (newValue) 1 else 0) != 0
	override fun toString(): String = get().toString()
}
