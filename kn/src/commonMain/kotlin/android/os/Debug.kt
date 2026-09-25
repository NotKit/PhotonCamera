@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.os

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents

/*
 * android.os.Debug's memory counters, read from the same kernel files the
 * framework reads: /proc/self/smaps_rollup for the proportional set.  There is
 * no Dalvik heap here, so everything private is native and dalvik reads 0.
 */
object Debug {
    class MemoryInfo {
        var dalvikPrivateDirty: Int = 0
        var nativePrivateDirty: Int = 0
        var otherPrivateDirty: Int = 0
        internal var totalPss: Int = 0

        /** kB, as on Android. */
        fun getTotalPss(): Int = totalPss
    }

    fun getMemoryInfo(info: MemoryInfo) {
        val rollup = procFields("/proc/self/smaps_rollup")
        info.totalPss = rollup["Pss"]?.toInt() ?: 0
        info.nativePrivateDirty = rollup["Private_Dirty"]?.toInt() ?: 0
        info.dalvikPrivateDirty = 0
        info.otherPrivateDirty = 0
    }

    /** Bytes the process has malloc'd and not freed, from glibc's accounting. */
    fun getNativeHeapAllocatedSize(): Long =
        platform.linux.mallinfo().useContents { uordblks.toLong() and 0xFFFFFFFFL }
}

/** "Key:   123 kB" lines of a /proc file, as kB values. */
fun procFields(path: String): Map<String, Long> {
    val out = HashMap<String, Long>()
    val f = platform.posix.fopen(path, "r") ?: return out
    try {
        memScoped {
            val line = allocArray<ByteVar>(256)
            while (platform.posix.fgets(line, 256, f) != null) {
                val s = line.toKString()
                val colon = s.indexOf(':')
                if (colon <= 0) continue
                val value = s.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
                out[s.substring(0, colon).trim()] = value
            }
        }
    } finally {
        platform.posix.fclose(f)
    }
    return out
}
