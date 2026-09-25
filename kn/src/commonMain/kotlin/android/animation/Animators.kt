/* android.animation: enough of the framework for the animators the converted
 * app runs off the View tree -- CaptureController's logical-zoom sweep.
 *
 * A ValueAnimator here ticks on the Looper of the thread that started it, one
 * frame every 16 ms, where Android ticks on that Looper's Choreographer.  The
 * value, the interpolation and the listener order are Android's. */
package android.animation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

fun interface TimeInterpolator {
    fun getInterpolation(input: Float): Float
}

abstract class Animator {
    interface AnimatorListener {
        fun onAnimationStart(animation: Animator?) {}
        fun onAnimationEnd(animation: Animator?) {}
        fun onAnimationCancel(animation: Animator?) {}
        fun onAnimationRepeat(animation: Animator?) {}
    }

    protected val listeners = ArrayList<AnimatorListener>()

    fun addListener(listener: AnimatorListener) { listeners.add(listener) }
    fun removeListener(listener: AnimatorListener) { listeners.remove(listener) }
    fun removeAllListeners() = listeners.clear()

    abstract fun start()
    abstract fun cancel()
    abstract fun end()
    abstract fun isRunning(): Boolean
}

/** An interface here, not a class: j2k writes `object : AnimatorListenerAdapter {`. */
interface AnimatorListenerAdapter : Animator.AnimatorListener

class ValueAnimator private constructor(private val from: Float, private val to: Float) : Animator() {
    fun interface AnimatorUpdateListener {
        fun onAnimationUpdate(animation: ValueAnimator)
    }

    private val updateListeners = ArrayList<AnimatorUpdateListener>()
    private var durationMs = 300L
    private var interpolator: TimeInterpolator? = null
    private var handler: Handler? = null
    private var startedAt = 0L
    private var running = false
    private var fraction = 0f

    fun setDuration(duration: Long): ValueAnimator {
        require(duration >= 0) { "Animators cannot have negative duration: $duration" }
        durationMs = duration
        return this
    }

    fun getDuration(): Long = durationMs

    /** j2k narrows the Java's long argument; the value is the same. */
    fun setDuration(duration: Int): ValueAnimator = setDuration(duration.toLong())

    fun setInterpolator(value: TimeInterpolator?) { interpolator = value }

    fun addUpdateListener(listener: AnimatorUpdateListener) { updateListeners.add(listener) }

    /** Only ofFloat exists, so the value is always a Float. */
    fun getAnimatedValue(): Float = from + (to - from) * fraction

    fun getAnimatedFraction(): Float = fraction

    private val tick = object : java.lang.Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val t = if (durationMs == 0L) 1f else (elapsed.toFloat() / durationMs).coerceAtMost(1f)
            fraction = interpolator?.getInterpolation(t) ?: t
            for (l in ArrayList(updateListeners)) l.onAnimationUpdate(this@ValueAnimator)
            if (t >= 1f) {
                running = false
                for (l in ArrayList(listeners)) l.onAnimationEnd(this@ValueAnimator)
            } else {
                handler?.postDelayed(this, FRAME_MS)
            }
        }
    }

    override fun start() {
        if (running) cancel()
        handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        startedAt = SystemClock.uptimeMillis()
        fraction = 0f
        running = true
        for (l in ArrayList(listeners)) l.onAnimationStart(this)
        handler?.post(tick)
    }

    override fun cancel() {
        if (!running) return
        running = false
        handler?.removeCallbacks(tick)
        for (l in ArrayList(listeners)) l.onAnimationCancel(this)
        for (l in ArrayList(listeners)) l.onAnimationEnd(this)
    }

    /** Jumps to the end value, as Android does. */
    override fun end() {
        if (!running) return
        startedAt = SystemClock.uptimeMillis() - durationMs
        handler?.removeCallbacks(tick)
        tick.run()
    }

    override fun isRunning(): Boolean = running

    companion object {
        private const val FRAME_MS = 16L

        fun ofFloat(vararg values: Float): ValueAnimator = when (values.size) {
            0 -> ValueAnimator(0f, 0f)
            1 -> ValueAnimator(0f, values[0])
            // Intermediate keyframes are not modelled; nothing here passes any.
            else -> ValueAnimator(values[0], values[values.size - 1])
        }
    }
}
