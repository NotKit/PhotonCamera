/* android.os: Build, SystemClock, Environment, Bundle, Parcelable. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.os

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

/** The device identity, off the host.  SDK_INT is 34 -- the app's newest
 *  guarded path -- because every Build.VERSION check here means "modern". */
object Build {
    val BRAND: String = "photoncamera"
    val DEVICE: String = "linux"
    val MODEL: String = "kn"
    val MANUFACTURER: String = "particlesdevs"
    val PRODUCT: String = "photoncamera"
    val HARDWARE: String = "linux"
    val BOARD: String = "linux"
    val ID: String = "kn"
    val DISPLAY: String = "kn"
    val FINGERPRINT: String = "particlesdevs/photoncamera/kn:14/kn/kn:user/release-keys"

    object VERSION {
        const val SDK_INT: Int = 34
        val RELEASE: String = "14"
        val CODENAME: String = "REL"
        val INCREMENTAL: String = "kn"
    }

    object VERSION_CODES {
        const val BASE: Int = 1
        const val KITKAT: Int = 19
        const val LOLLIPOP: Int = 21
        const val M: Int = 23
        const val N: Int = 24
        const val N_MR1: Int = 25
        const val O: Int = 26
        const val O_MR1: Int = 27
        const val P: Int = 28
        const val Q: Int = 29
        const val R: Int = 30
        const val S: Int = 31
        const val S_V2: Int = 32
        const val TIRAMISU: Int = 33
        const val UPSIDE_DOWN_CAKE: Int = 34
    }
}

object SystemClock {
    /** Android's uptimeMillis: monotonic, not wall clock. */
    fun uptimeMillis(): Long = java.lang.System.nanoTime() / 1_000_000L
    fun elapsedRealtime(): Long = uptimeMillis()
    fun elapsedRealtimeNanos(): Long = java.lang.System.nanoTime()
    fun currentThreadTimeMillis(): Long = uptimeMillis()
    fun sleep(ms: Long) = java.lang.Thread.sleep(ms)
}

/**
 * Environment: the external storage root.  On this port that is
 * $PHOTONCAMERA_HOME, else $XDG_PICTURES_DIR, else $HOME.
 */
object Environment {
    const val MEDIA_MOUNTED: String = "mounted"
    const val DIRECTORY_DCIM: String = "DCIM"
    const val DIRECTORY_PICTURES: String = "Pictures"
    const val DIRECTORY_DOWNLOADS: String = "Download"
    const val DIRECTORY_DOCUMENTS: String = "Documents"

    fun getExternalStorageDirectory(): java.io.File = java.io.File(root())
    fun getExternalStoragePublicDirectory(type: String): java.io.File = java.io.File(root(), type)
    fun getDataDirectory(): java.io.File = java.io.File(root())
    fun getExternalStorageState(): String = MEDIA_MOUNTED
    fun isExternalStorageManager(): Boolean = true

    private fun root(): String =
        java.lang.System.getenv("PHOTONCAMERA_HOME")
            ?: java.lang.System.getenv("XDG_PICTURES_DIR")
            ?: java.lang.System.getenv("HOME")
            ?: "."
}

/** A string-keyed value bag.  Not parcelled: this process never crosses a Binder. */
class Bundle() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(src: Bundle) : this() { map.putAll(src.map) }

    fun putInt(key: String, v: Int) { map[key] = v }
    fun putLong(key: String, v: Long) { map[key] = v }
    fun putFloat(key: String, v: Float) { map[key] = v }
    fun putDouble(key: String, v: Double) { map[key] = v }
    fun putBoolean(key: String, v: Boolean) { map[key] = v }
    fun putString(key: String, v: String?) { map[key] = v }
    fun putStringArray(key: String, v: Array<String>?) { map[key] = v }
    fun putIntArray(key: String, v: IntArray?) { map[key] = v }
    fun putSerializable(key: String, v: Any?) { map[key] = v }
    fun putParcelable(key: String, v: Any?) { map[key] = v }
    fun putBundle(key: String, v: Bundle?) { map[key] = v }
    fun putAll(other: Bundle) { map.putAll(other.map) }

    fun getInt(key: String, def: Int = 0): Int = map[key] as? Int ?: def
    fun getLong(key: String, def: Long = 0L): Long = map[key] as? Long ?: def
    fun getFloat(key: String, def: Float = 0f): Float = map[key] as? Float ?: def
    fun getDouble(key: String, def: Double = 0.0): Double = map[key] as? Double ?: def
    fun getBoolean(key: String, def: Boolean = false): Boolean = map[key] as? Boolean ?: def
    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, def: String): String = map[key] as? String ?: def
    fun getStringArray(key: String): Array<String>? = @Suppress("UNCHECKED_CAST") (map[key] as? Array<String>)
    fun getIntArray(key: String): IntArray? = map[key] as? IntArray
    fun getSerializable(key: String): Any? = map[key]
    fun getBundle(key: String): Bundle? = map[key] as? Bundle
    fun get(key: String): Any? = map[key]

    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun keySet(): Set<String> = map.keys
    fun size(): Int = map.size
    fun isEmpty(): Boolean = map.isEmpty()
    fun clear() = map.clear()
    override fun toString(): String = map.toString()
}

/** A marker only: nothing in this binary writes to a Parcel. */
interface Parcelable {
    interface Creator<T>
}

class Process {
    companion object {
        fun myPid(): Int = platform.posix.getpid()
        fun myTid(): Int = platform.posix.getpid()
        fun setThreadPriority(priority: Int) {}
        const val THREAD_PRIORITY_BACKGROUND: Int = 10
        const val THREAD_PRIORITY_FOREGROUND: Int = -2
    }
}

/**
 * android.os.CountDownTimer: a real countdown on the main Looper.  onTick fires
 * every countDownInterval until the total elapses, then onFinish; cancel()
 * stops it.  The Java subclasses it, so both callbacks are abstract.
 */
abstract class CountDownTimer(
    private val millisInFuture: Long,
    private val countDownInterval: Long,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var cancelled = false
    private var tick: java.lang.Runnable? = null

    abstract fun onTick(millisUntilFinished: Long)
    abstract fun onFinish()

    fun start(): CountDownTimer {
        cancelled = false
        endAt = SystemClock.uptimeMillis() + millisInFuture
        val step = object {
            fun run() {
                if (cancelled) return
                val left = endAt - SystemClock.uptimeMillis()
                if (left <= 0) { onFinish(); return }
                onTick(left)
                handler.postDelayed(tick!!, minOf(countDownInterval, left))
            }
        }
        tick = java.lang.Runnable { step.run() }
        handler.postDelayed(tick!!, minOf(countDownInterval, millisInFuture))
        return this
    }

    fun cancel() {
        cancelled = true
        tick?.let { handler.removeCallbacks(it) }
        tick = null
    }
}

/** android.os.StatFs over statfs(2). */
class StatFs(private var path: String) {
    private var blockSize: Long = 0
    private var available: Long = 0
    private var total: Long = 0

    init { restat(path) }

    fun restat(path: String) {
        this.path = path
        memScoped {
            val st = alloc<platform.linux.statfs>()
            if (platform.linux.statfs(path, st.ptr) == 0) {
                blockSize = st.f_bsize.toLong()
                available = st.f_bavail.toLong()
                total = st.f_blocks.toLong()
            }
        }
    }

    fun getBlockSizeLong(): Long = blockSize
    fun getBlockSize(): Int = blockSize.toInt()
    fun getAvailableBlocksLong(): Long = available
    fun getAvailableBlocks(): Int = available.toInt()
    fun getBlockCountLong(): Long = total
    fun getAvailableBytes(): Long = available * blockSize
    fun getTotalBytes(): Long = total * blockSize
    fun getFreeBytes(): Long = available * blockSize
}

/**
 * android.os.Vibrator: there is no vibrator behind this on the desktop, and on
 * the phone it is the host lane's to wire to the Lomiri haptics service.  The
 * effect it is handed is real and inspectable; play() is where the hardware
 * would be, and it only logs.
 */
class VibrationEffect private constructor(
    val predefined: Int,
    val milliseconds: Long,
    val amplitude: Int,
) {
    companion object {
        const val DEFAULT_AMPLITUDE: Int = -1
        const val EFFECT_CLICK: Int = 0
        const val EFFECT_DOUBLE_CLICK: Int = 1
        const val EFFECT_TICK: Int = 2
        const val EFFECT_THUD: Int = 3
        const val EFFECT_POP: Int = 4
        const val EFFECT_HEAVY_CLICK: Int = 5

        fun createPredefined(effectId: Int): VibrationEffect = VibrationEffect(effectId, 0L, DEFAULT_AMPLITUDE)
        fun createOneShot(milliseconds: Long, amplitude: Int): VibrationEffect =
            VibrationEffect(-1, milliseconds, amplitude)
        fun createOneShot(milliseconds: Int, amplitude: Int): VibrationEffect =
            createOneShot(milliseconds.toLong(), amplitude)
        fun createWaveform(timings: LongArray, repeat: Int): VibrationEffect =
            VibrationEffect(-1, timings.sum(), DEFAULT_AMPLITUDE)
    }
}

class Vibrator {
    fun hasVibrator(): Boolean = false
    fun hasAmplitudeControl(): Boolean = false
    fun vibrate(effect: VibrationEffect?) {
        android.util.Log.v("Vibrator", "no haptics on this port: " + effect)
    }
    fun vibrate(milliseconds: Long) {
        android.util.Log.v("Vibrator", "no haptics on this port: " + milliseconds + "ms")
    }
    fun cancel() {}
}
