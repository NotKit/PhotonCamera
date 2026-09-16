/* java.util.concurrent: the executor and latch surface the app uses.  Each
 * executor owns one Kotlin/Native Worker per thread and hands work to it; a
 * "fixed pool" round-robins, which is the property the callers rely on. */
package java.util.concurrent

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.Worker

/**
 * java.util.concurrent.ConcurrentHashMap, delegating to a LinkedHashMap.  It is
 * a class and not a typealias only so that newKeySet() has a companion to live
 * on.  Concurrency: the callers here publish through it, they do not race on it.
 */
class ConcurrentHashMap<K, V> private constructor(
    private val inner: kotlin.collections.LinkedHashMap<K, V>,
) : kotlin.collections.MutableMap<K, V> by inner {
    constructor() : this(kotlin.collections.LinkedHashMap())
    constructor(initialCapacity: Int) : this(kotlin.collections.LinkedHashMap())
    constructor(m: kotlin.collections.Map<K, V>) : this(kotlin.collections.LinkedHashMap(m))

    override fun toString(): String = inner.toString()
    override fun equals(other: Any?): Boolean = inner == other
    override fun hashCode(): Int = inner.hashCode()

    companion object {
        fun <E> newKeySet(): kotlin.collections.MutableSet<E> = LinkedHashSet()
    }
}
typealias CopyOnWriteArrayList<E> = kotlin.collections.ArrayList<E>
typealias ConcurrentLinkedQueue<E> = kotlin.collections.ArrayDeque<E>

class ExecutionException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
class TimeoutException(message: String? = null) : Exception(message)
class RejectedExecutionException(message: String? = null) : RuntimeException(message)

interface Future<V> {
    fun get(): V
    fun isDone(): Boolean
    fun cancel(mayInterrupt: Boolean): Boolean
}

private class WorkerFuture<V>(private val f: kotlin.native.concurrent.Future<V>) : Future<V> {
    override fun get(): V = f.result
    override fun isDone(): Boolean = f.state == kotlin.native.concurrent.FutureState.COMPUTED
    override fun cancel(mayInterrupt: Boolean): Boolean = false
}

interface Executor {
    fun execute(command: java.lang.Runnable)
}

/** The thread factory an executor is given.  Priority and naming are not
 *  portable here, so only the body it wraps survives. */
fun interface ThreadFactory {
    fun newThread(r: java.lang.Runnable): java.lang.Thread
}

open class ExecutorService(threads: Int, name: String) : Executor {
    private val workers = List(threads) { Worker.start(name = "$name-$it") }
    private val next = AtomicInt(0)
    private var shutdown = false

    override fun execute(command: java.lang.Runnable) { submit { command.run() } }

    fun <T> submit(task: () -> T): Future<T> {
        if (shutdown) throw RejectedExecutionException("executor is shut down")
        val w = workers[(next.getAndIncrement() and 0x7fffffff) % workers.size]
        return WorkerFuture(w.execute(kotlin.native.concurrent.TransferMode.SAFE, { task }) { it() })
    }

    fun shutdown() {
        if (shutdown) return
        shutdown = true
        for (w in workers) w.requestTermination(processScheduledJobs = true)
    }

    fun shutdownNow(): kotlin.collections.List<java.lang.Runnable> {
        if (!shutdown) { shutdown = true; for (w in workers) w.requestTermination(processScheduledJobs = false) }
        return emptyList()
    }

    fun isShutdown(): Boolean = shutdown

    /** Termination is requested synchronously above, so this only reports it. */
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
}

object Executors {
    fun newSingleThreadExecutor(): ExecutorService = ExecutorService(1, "single")
    fun newSingleThreadExecutor(factory: ThreadFactory): ExecutorService = ExecutorService(1, "single")
    fun newFixedThreadPool(n: Int, factory: ThreadFactory): ExecutorService = newFixedThreadPool(n)
    fun newFixedThreadPool(n: Int): ExecutorService = ExecutorService(if (n > 0) n else 1, "pool")
    fun newCachedThreadPool(): ExecutorService = ExecutorService(4, "cached")
    fun newWorkStealingPool(): ExecutorService =
        ExecutorService(platform.posix.sysconf(platform.posix._SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1), "steal")
    fun newScheduledThreadPool(n: Int): ExecutorService = ExecutorService(if (n > 0) n else 1, "sched")
}

/** A spin-then-yield latch: no condvar, but correct and never missed. */
class CountDownLatch(count: Int) {
    private val cell = AtomicInt(count)
    fun countDown() { if (cell.value > 0) cell.decrementAndGet() }
    fun getCount(): Long = cell.value.toLong()
    fun await() { while (cell.value > 0) platform.posix.usleep(200u) }
    fun await(timeout: Long, unit: TimeUnit): Boolean {
        var left = unit.toMillis(timeout) * 1000L
        while (cell.value > 0 && left > 0) { platform.posix.usleep(200u); left -= 200 }
        return cell.value <= 0
    }
}

/** A counting semaphore on the same spin-then-yield basis as CountDownLatch. */
class Semaphore(permits: Int) {
    private val cell = AtomicInt(permits)

    fun acquire() {
        while (true) {
            val v = cell.value
            if (v > 0 && cell.compareAndSet(v, v - 1)) return
            platform.posix.usleep(200u)
        }
    }

    fun tryAcquire(): Boolean {
        val v = cell.value
        return v > 0 && cell.compareAndSet(v, v - 1)
    }

    /** Polls like acquire(), giving up after the timeout. */
    fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val limit = unit.toMillis(timeout)
        while (true) {
            if (tryAcquire()) return true
            if (start.elapsedNow().inWholeMilliseconds >= limit) return false
            platform.posix.usleep(200u)
        }
    }

    fun release() { cell.incrementAndGet() }
    fun availablePermits(): Int = cell.value
    fun drainPermits(): Int = cell.getAndSet(0)
}
