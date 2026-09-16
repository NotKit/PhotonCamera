/* android.os.ParcelFileDescriptor: a real POSIX file descriptor.  Nothing is
 * parcelled -- the point of it here is detachFd(), which hands the raw fd to
 * the natives so they can write a DNG without going back through Java. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.os

class ParcelFileDescriptor private constructor(private var fd: Int) : kotlin.AutoCloseable {
    fun getFd(): Int = fd

    /** Hands the fd to the caller and stops owning it, as Android's does. */
    fun detachFd(): Int {
        val out = fd
        fd = -1
        return out
    }

    fun dup(): ParcelFileDescriptor = ParcelFileDescriptor(platform.posix.dup(fd))
    fun getFileDescriptor(): java.io.FileDescriptor = java.io.FileDescriptor(fd)
    fun getStatSize(): Long = -1L
    override fun close() { if (fd >= 0) platform.posix.close(fd); fd = -1 }

    companion object {
        const val MODE_READ_ONLY: Int = 0x10000000
        const val MODE_WRITE_ONLY: Int = 0x20000000
        const val MODE_READ_WRITE: Int = 0x30000000
        const val MODE_CREATE: Int = 0x08000000
        const val MODE_TRUNCATE: Int = 0x02000000
        const val MODE_APPEND: Int = 0x04000000

        /** Android's mode strings: "r", "w", "wa", "rw", "rwt". */
        fun open(file: java.io.File, mode: String): ParcelFileDescriptor {
            var flags = when {
                mode.startsWith("rw") -> platform.posix.O_RDWR or platform.posix.O_CREAT
                mode.startsWith("w") -> platform.posix.O_WRONLY or platform.posix.O_CREAT
                else -> platform.posix.O_RDONLY
            }
            if (mode.contains("a")) flags = flags or platform.posix.O_APPEND
            if (mode.contains("t") || mode == "w") flags = flags or platform.posix.O_TRUNC
            val fd = platform.posix.open(file.getPath(), flags, 438u) // 0666
            if (fd < 0) throw java.io.FileNotFoundException(file.getPath())
            return ParcelFileDescriptor(fd)
        }

        fun adoptFd(fd: Int): ParcelFileDescriptor = ParcelFileDescriptor(fd)
        fun dup(fd: java.io.FileDescriptor): ParcelFileDescriptor =
            ParcelFileDescriptor(platform.posix.dup(fd.getFd()))
    }
}
