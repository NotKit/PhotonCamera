@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.util.concurrent.locks

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.nanosleep
import platform.posix.timespec

/** parkNanos without an unpark: nothing here ever unparks, so it is a sleep,
 *  and Java allows a park to return early or late anyway. */
object LockSupport {
    fun parkNanos(nanos: Long) {
        if (nanos <= 0) return
        memScoped {
            val ts = alloc<timespec>()
            ts.tv_sec = nanos / 1_000_000_000L
            ts.tv_nsec = nanos % 1_000_000_000L
            nanosleep(ts.ptr, null)
        }
    }
}
