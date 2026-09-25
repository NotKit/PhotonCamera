@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET

/** java.io.RandomAccessFile over a posix descriptor.  Multi-byte reads and
 *  writes are big-endian, as DataInput/DataOutput define them. */
class RandomAccessFile(private val path: String, mode: String) : Closeable {
    private var fd: Int

    constructor(file: File, mode: String) : this(file.getPath(), mode)

    init {
        val flags = when (mode) {
            "r" -> O_RDONLY
            "rw", "rws", "rwd" -> O_RDWR or O_CREAT
            else -> throw IllegalArgumentException("Illegal mode \"$mode\"")
        }
        fd = platform.posix.open(path, flags, 0x1a4 /* 0644 */)
        if (fd < 0) throw FileNotFoundException("$path (errno ${platform.posix.errno})")
    }

    private fun check(): Int = if (fd >= 0) fd else throw IOException("Stream Closed")

    fun getFilePointer(): Long = platform.posix.lseek(check(), 0, SEEK_CUR)

    fun seek(pos: Long) {
        if (pos < 0) throw IOException("Negative seek offset")
        if (platform.posix.lseek(check(), pos, SEEK_SET) < 0) throw IOException("seek failed: $path")
    }

    fun length(): Long {
        val here = getFilePointer()
        val end = platform.posix.lseek(check(), 0, SEEK_END)
        platform.posix.lseek(fd, here, SEEK_SET)
        return end
    }

    fun setLength(newLength: Long) {
        if (platform.posix.ftruncate(check(), newLength) != 0) throw IOException("truncate failed: $path")
        if (getFilePointer() > newLength) seek(newLength)
    }

    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = b.usePinned { platform.posix.read(check(), it.addressOf(off), len.toULong()) }
        return if (n <= 0) -1 else n.toInt()
    }

    fun read(b: ByteArray): Int = read(b, 0, b.size)

    fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    fun readFully(b: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = read(b, off + done, len - done)
            if (n < 0) throw EOFException()
            done += n
        }
    }

    fun readFully(b: ByteArray) = readFully(b, 0, b.size)

    fun readInt(): Int {
        val b = ByteArray(4)
        readFully(b)
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    fun readLong(): Long =
        (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)

    fun write(b: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = b.usePinned { platform.posix.write(check(), it.addressOf(off + done), (len - done).toULong()) }
            if (n <= 0) throw IOException("write failed: $path")
            done += n.toInt()
        }
    }

    fun write(b: ByteArray) = write(b, 0, b.size)

    fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    fun writeInt(v: Int) = write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    fun writeLong(v: Long) {
        writeInt((v ushr 32).toInt())
        writeInt(v.toInt())
    }

    override fun close() {
        if (fd >= 0) {
            platform.posix.close(fd)
            fd = -1
        }
    }
}
