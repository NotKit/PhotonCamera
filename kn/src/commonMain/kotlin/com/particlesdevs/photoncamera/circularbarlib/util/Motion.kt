/* com.particlesdevs.photoncamera.circularbarlib.util.Motion, by hand.
 *
 * The Java resolves the M3E motion tokens against the Material theme through
 * MotionUtils; there is no theme here, so these are the tokens' own values
 * from Material 3's motion spec (what the app's theme resolves to on Android).
 * drop.txt says why the Java is not converted. */
package com.particlesdevs.photoncamera.circularbarlib.util

import android.animation.TimeInterpolator
import android.content.Context

object Motion {
    fun durationShort2(context: Context?): Int = 100
    fun durationShort3(context: Context?): Int = 150
    fun durationShort4(context: Context?): Int = 200
    fun durationMedium1(context: Context?): Int = 250
    fun durationMedium2(context: Context?): Int = 300
    fun durationLong1(context: Context?): Int = 450
    fun durationLong2(context: Context?): Int = 500

    /** Two cubic segments: (0.05,0 0.133,0.06 0.167,0.4) then (0.208,0.82 0.25,1 1,1). */
    fun emphasized(context: Context?): TimeInterpolator = EMPHASIZED
    fun emphasizedDecelerate(context: Context?): TimeInterpolator = CubicBezier(0.05f, 0.7f, 0.1f, 1f)
    fun emphasizedAccelerate(context: Context?): TimeInterpolator = CubicBezier(0.3f, 0f, 0.8f, 0.15f)
    fun standard(context: Context?): TimeInterpolator = CubicBezier(0.2f, 0f, 0f, 1f)

    private val EMPHASIZED = TimeInterpolator { t ->
        val split = 0.166666f
        if (t < split) {
            0.4f * FIRST.getInterpolation(t / split)
        } else {
            0.4f + 0.6f * SECOND.getInterpolation((t - split) / (1f - split))
        }
    }
    // The two segments renormalised to the unit square.
    private val FIRST = CubicBezier(0.05f / 0.166666f, 0f, 0.133333f / 0.166666f, 0.06f / 0.4f)
    private val SECOND = CubicBezier(
        (0.208333f - 0.166666f) / 0.833334f, (0.82f - 0.4f) / 0.6f,
        (0.25f - 0.166666f) / 0.833334f, 1f,
    )
}

/** A cubic Bezier easing from (0,0) to (1,1), as PathInterpolator evaluates one. */
private class CubicBezier(
    private val x1: Float, private val y1: Float,
    private val x2: Float, private val y2: Float,
) : TimeInterpolator {
    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        // x(t) is monotonic on [0,1], so bisection finds the t for this input.
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (bezier(mid, x1, x2) < input) lo = mid else hi = mid
        }
        return bezier((lo + hi) / 2f, y1, y2)
    }
}
