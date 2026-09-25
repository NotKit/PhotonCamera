@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.nio.channels

import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** java.nio.channels.FileChannel, the positional-read part, over a posix fd. */
class FileChannel private constructor(private var fd: Int, private val path: String) : java.io.Closeable {

    private fun check(): Int = if (fd >= 0) fd else throw java.io.IOException("Channel closed")

    fun size(): Long {
        val here = platform.posix.lseek(check(), 0, platform.posix.SEEK_CUR)
        val end = platform.posix.lseek(fd, 0, platform.posix.SEEK_END)
        platform.posix.lseek(fd, here, platform.posix.SEEK_SET)
        return end
    }

    /** Reads into dst's remaining space from `position`; -1 at end of file. */
    fun read(dst: ByteBuffer, position: Long): Int {
        val want = dst.remaining()
        if (want == 0) return 0
        val n = platform.posix.pread(check(), dst.pointer(), want.toULong(), position)
        if (n < 0) throw java.io.IOException("read failed: $path")
        if (n == 0L) return -1
        dst.position(dst.position() + n.toInt())
        return n.toInt()
    }

    override fun close() {
        if (fd >= 0) {
            platform.posix.close(fd)
            fd = -1
        }
    }

    companion object {
        fun open(path: Path, vararg options: StandardOpenOption): FileChannel {
            val write = options.any { it == StandardOpenOption.WRITE || it == StandardOpenOption.APPEND }
            var flags = if (write) platform.posix.O_RDWR else platform.posix.O_RDONLY
            if (options.any { it == StandardOpenOption.CREATE || it == StandardOpenOption.CREATE_NEW })
                flags = flags or platform.posix.O_CREAT
            if (options.any { it == StandardOpenOption.TRUNCATE_EXISTING }) flags = flags or platform.posix.O_TRUNC
            val name = path.toString()
            val fd = platform.posix.open(name, flags, 0x1a4)
            if (fd < 0) throw java.nio.file.NoSuchFileException(name)
            return FileChannel(fd, name)
        }
    }
}
