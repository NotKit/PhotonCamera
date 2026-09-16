/* java.lang.System, Thread, Runtime: the process-level members the app names. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.lang

import kotlin.native.concurrent.Worker
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_MONOTONIC
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

object System {
    /** Epoch milliseconds off CLOCK_REALTIME -- the JDK's clock. */
    fun currentTimeMillis(): kotlin.Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME.toInt(), ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

    /** Monotonic nanoseconds; only differences are meaningful, as on the JDK. */
    fun nanoTime(): kotlin.Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC.toInt(), ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }

    fun arraycopy(src: Any?, srcPos: Int, dest: Any?, destPos: Int, length: Int) {
        when {
            src is IntArray && dest is IntArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is ByteArray && dest is ByteArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is ShortArray && dest is ShortArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is LongArray && dest is LongArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is FloatArray && dest is FloatArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is DoubleArray && dest is DoubleArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is CharArray && dest is CharArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is BooleanArray && dest is BooleanArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is Array<*> && dest is Array<*> ->
                @Suppress("UNCHECKED_CAST")
                (src as Array<Any?>).copyInto(dest as Array<Any?>, destPos, srcPos, srcPos + length)
            else -> throw IllegalArgumentException("arraycopy: unsupported array types")
        }
    }

    fun getProperty(key: String): String? = platform.posix.getenv(key)?.toKString()
    fun getProperty(key: String, def: String): String = getProperty(key) ?: def
    fun getenv(key: String): String? = platform.posix.getenv(key)?.toKString()
    fun lineSeparator(): String = "\n"
    fun exit(status: Int): Nothing { platform.posix.exit(status); throw Error("unreachable") }
    fun identityHashCode(o: Any?): Int = o?.hashCode() ?: 0
    fun gc() {}
    fun loadLibrary(name: String) {}

    val out: java.io.PrintStream = java.io.PrintStream(false)
    val err: java.io.PrintStream = java.io.PrintStream(true)
}

/**
 * java.lang.Thread over a Kotlin/Native Worker: start() spawns one OS thread and
 * runs the body once, join() waits for it.  Interruption is not modelled --
 * interrupt() only sets the flag isInterrupted() reads.
 */
open class Thread {
    private val body: Runnable?
    private var worker: Worker? = null
    private var future: kotlin.native.concurrent.Future<Unit>? = null
    private var interrupted = false
    var name: String = "Thread"

    constructor() { body = null }
    constructor(r: Runnable?) { body = r }
    constructor(r: Runnable?, name: String) { body = r; this.name = name }
    constructor(name: String) { body = null; this.name = name }

    open fun run() { body?.run() }

    fun start() {
        val w = Worker.start(name = name)
        worker = w
        future = w.execute(kotlin.native.concurrent.TransferMode.SAFE, { this }) { it.run() }
    }

    fun join() { future?.result; worker?.requestTermination()?.result; worker = null }
    fun join(millis: kotlin.Long) = join()
    fun interrupt() { interrupted = true }
    fun isInterrupted(): kotlin.Boolean = interrupted
    fun isAlive(): kotlin.Boolean = future != null &&
        future!!.state != kotlin.native.concurrent.FutureState.COMPUTED
    fun setName(n: String) { name = n }
    fun getName(): String = name
    fun setPriority(p: Int) {}
    fun setDaemon(d: kotlin.Boolean) {}
    fun getId(): kotlin.Long = worker?.id?.toLong() ?: 0L

    /** EMPTY, and the callers know it: Kotlin/Native's stack trace is opt-in
     *  strings with no class/method/line split, so a StackTraceElement walk
     *  (util/log/Logger's caller-name tag) finds nothing here. */
    fun getStackTrace(): Array<StackTraceElement> = emptyArray()

    companion object {
        const val MIN_PRIORITY: Int = 1
        const val NORM_PRIORITY: Int = 5
        const val MAX_PRIORITY: Int = 10

        fun sleep(millis: kotlin.Long) {
            if (millis <= 0) return
            platform.posix.usleep((millis * 1000L).toUInt())
        }

        fun sleep(millis: kotlin.Long, nanos: Int) = sleep(millis)
        fun currentThread(): Thread = current
        fun yield() { platform.posix.sched_yield() }

        private val current = Thread("main")
    }
}

object Runtime {
    fun getRuntime(): Runtime = this
    fun availableProcessors(): Int =
        platform.posix.sysconf(platform.posix._SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)
    fun maxMemory(): kotlin.Long = kotlin.Long.MAX_VALUE
    fun totalMemory(): kotlin.Long = 0L
    fun freeMemory(): kotlin.Long = 0L
    fun gc() {}
}
