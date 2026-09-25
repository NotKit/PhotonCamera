/* android.os Looper/Handler/Message/HandlerThread.
 *
 * A Looper owns a real time-ordered message queue guarded by a pthread mutex;
 * loop() drains it on the calling thread and blocks on a condvar in between.
 * A Handler posts into the Looper it was made with.  The main Looper is the
 * one the host's main() prepares and runs; posting to it from a worker is the
 * whole point, so the queue is the only shared state here. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.os

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.Arena
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

private class Lock {
    private val arena = Arena()
    private val m = arena.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }
    fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(m.ptr)
        try { return block() } finally { pthread_mutex_unlock(m.ptr) }
    }
    fun dispose() { pthread_mutex_destroy(m.ptr); arena.clear() }
}

class Message {
    var what: Int = 0
    var arg1: Int = 0
    var arg2: Int = 0
    var obj: Any? = null
    var target: Handler? = null
    internal var callback: java.lang.Runnable? = null
    internal var dueAt: Long = 0L

    fun sendToTarget() { target?.sendMessage(this) }

    companion object {
        fun obtain(): Message = Message()
        fun obtain(h: Handler?): Message = Message().also { it.target = h }
        fun obtain(h: Handler?, what: Int): Message = obtain(h).also { it.what = what }
    }
}

class MessageQueue internal constructor() {
    private val lock = Lock()
    private val items = ArrayList<Message>()
    @kotlin.concurrent.Volatile
    private var quitting = false

    internal fun enqueue(msg: Message) = lock.withLock {
        if (quitting) return@withLock
        var i = items.size
        while (i > 0 && items[i - 1].dueAt > msg.dueAt) i--
        items.add(i, msg)
    }

    internal fun removeCallbacks(h: Handler, cb: java.lang.Runnable?) = lock.withLock {
        items.removeAll { it.target === h && (cb == null || it.callback === cb) }
    }

    internal fun removeMessages(h: Handler, what: Int) = lock.withLock {
        items.removeAll { it.target === h && it.what == what }
    }

    internal fun hasMessages(h: Handler, what: Int): Boolean =
        lock.withLock { items.any { it.target === h && it.what == what } }

    /** The next message that is due now, or null.  Never blocks. */
    internal fun poll(now: Long): Message? = lock.withLock {
        val m = items.firstOrNull() ?: return@withLock null
        if (m.dueAt > now) null else items.removeAt(0)
    }

    internal fun isEmpty(): Boolean = lock.withLock { items.isEmpty() }

    /** Android has two of these and they differ in what they owe the queue:
     *  quit() drops everything still pending, quitSafely() keeps what is
     *  already due and drops only what was timed for later.  Running the one
     *  as the other loses work -- a capture's initProcess sits on this queue. */
    internal fun quit(safe: Boolean) = lock.withLock {
        quitting = true
        if (safe) {
            val now = SystemClock.uptimeMillis()
            items.removeAll { it.dueAt > now }
        } else {
            items.clear()
        }
    }

    internal fun isQuitting(): Boolean = quitting
}

class Looper internal constructor() {
    val queue: MessageQueue = MessageQueue()

    fun quit() = queue.quit(safe = false)
    fun quitSafely() = queue.quit(safe = true)
    fun getQueue(): MessageQueue = queue
    fun getThread(): java.lang.Thread = java.lang.Thread.currentThread()

    /** Drain until quit().  Sleeps 200 us between empty passes -- no condvar,
     *  but a pending message is never missed and the idle cost is negligible. */
    fun loop() {
        bindToCurrentThread(this)
        while (true) {
            val msg = queue.poll(SystemClock.uptimeMillis())
            if (msg == null) {
                // Only once there is nothing left to run: a quitSafely() leaves
                // its due messages behind on purpose, and leaving here on the
                // quitting flag alone would throw them away again.
                if (queue.isQuitting()) return
                platform.posix.usleep(200u)
                continue
            }
            val cb = msg.callback
            if (cb != null) cb.run() else msg.target?.handleMessage(msg)
        }
    }

    /** One non-blocking pass, for a host that owns its own frame loop. */
    fun pump() {
        bindToCurrentThread(this)
        while (true) {
            val msg = queue.poll(SystemClock.uptimeMillis()) ?: return
            val cb = msg.callback
            if (cb != null) cb.run() else msg.target?.handleMessage(msg)
        }
    }

    companion object {
        private val main = Looper()
        private val perThread = HashMap<Long, Looper>()
        private val lock = Lock()

        fun getMainLooper(): Looper = main
        fun prepare() { setForCurrentThread(Looper()) }
        fun prepareMainLooper() { setForCurrentThread(main) }
        fun myLooper(): Looper? = lock.withLock { perThread[threadId()] }
        fun loop() { (myLooper() ?: main).loop() }

        private fun setForCurrentThread(l: Looper) { lock.withLock { perThread[threadId()] = l } }

        /* The thread that drives a looper is its thread, as on Android, where
         * the main thread's looper is bound before anything runs.  The host
         * pumps the main looper from its own frame loop without ever calling
         * prepareMainLooper, so myLooper() answered null there -- and code
         * that re-posts itself until it is on the main looper spun forever. */
        internal fun bindToCurrentThread(l: Looper) {
            val id = threadId()
            lock.withLock { if (perThread[id] !== l) perThread[id] = l }
        }
        private fun threadId(): Long = platform.posix.pthread_self().toLong()
    }
}

open class Handler {
    private val looper: Looper
    private val callback: ((Message) -> Boolean)?

    constructor() : this(Looper.myLooper() ?: Looper.getMainLooper(), null)
    constructor(looper: Looper?) : this(looper ?: Looper.getMainLooper(), null)
    constructor(looper: Looper?, callback: ((Message) -> Boolean)?) {
        this.looper = looper ?: Looper.getMainLooper()
        this.callback = callback
    }

    open fun handleMessage(msg: Message?) { if (msg != null) callback?.invoke(msg) }

    fun getLooper(): Looper = looper

    fun post(r: java.lang.Runnable): Boolean = postDelayed(r, 0L)

    fun postDelayed(r: java.lang.Runnable, delayMillis: Long): Boolean {
        val m = Message.obtain(this)
        m.callback = r
        m.dueAt = SystemClock.uptimeMillis() + delayMillis
        looper.queue.enqueue(m)
        return true
    }

    fun postAtTime(r: java.lang.Runnable, uptimeMillis: Long): Boolean {
        val m = Message.obtain(this)
        m.callback = r
        m.dueAt = uptimeMillis
        looper.queue.enqueue(m)
        return true
    }

    fun removeCallbacks(r: java.lang.Runnable) = looper.queue.removeCallbacks(this, r)
    fun removeCallbacksAndMessages(token: Any?) = looper.queue.removeCallbacks(this, null)
    fun removeMessages(what: Int) = looper.queue.removeMessages(this, what)
    fun hasMessages(what: Int): Boolean = looper.queue.hasMessages(this, what)

    fun sendMessage(msg: Message): Boolean = sendMessageDelayed(msg, 0L)

    fun sendMessageDelayed(msg: Message, delayMillis: Long): Boolean {
        msg.target = this
        msg.dueAt = SystemClock.uptimeMillis() + delayMillis
        looper.queue.enqueue(msg)
        return true
    }

    fun sendEmptyMessage(what: Int): Boolean = sendMessage(Message.obtain(this, what))
    fun sendEmptyMessageDelayed(what: Int, delay: Long): Boolean =
        sendMessageDelayed(Message.obtain(this, what), delay)

    fun obtainMessage(): Message = Message.obtain(this)
    fun obtainMessage(what: Int): Message = Message.obtain(this, what)
    fun obtainMessage(what: Int, obj: Any?): Message = Message.obtain(this, what).also { it.obj = obj }
}

/** A thread that prepares a Looper and runs it until quit(). */
class HandlerThread(private val threadName: String) {
    private var looper: Looper? = null
    private var worker: Worker? = null
    private val ready = AtomicInt(0)

    fun start() {
        val l = Looper()
        looper = l
        val w = Worker.start(name = threadName)
        worker = w
        ready.value = 1
        w.executeAfter(0L) { l.loop() }
    }

    /** Blocks until start() has made the Looper, as Android's does. */
    fun getLooper(): Looper? {
        while (ready.value == 0 && worker != null) platform.posix.usleep(100u)
        return looper
    }

    // Neither of these waits, as on Android: they ask the looper to stop and
    // return.  join() is what waits -- so the worker has to outlive them, and
    // nulling it here made the join that follows a no-op.
    fun quit(): Boolean { looper?.quit(); return true }

    fun quitSafely(): Boolean { looper?.quitSafely(); return true }

    /** Blocks until loop() has returned, which needs a quit first -- as on
     *  Android, a join without one waits forever. */
    fun join() { worker?.requestTermination(processScheduledJobs = true)?.result; worker = null }
    fun getName(): String = threadName
    fun interrupt() {}
    fun isAlive(): Boolean = worker != null
}
