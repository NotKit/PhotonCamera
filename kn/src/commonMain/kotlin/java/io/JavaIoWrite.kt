/* java.io's writing half, plus the file-backed streams, over stdio.
 * FileInputStream reads the whole file on construction -- every caller in this
 * tree drains it -- while FileOutputStream/Writer stream through a FILE*. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

class FileInputStream : InputStream {
    private val inner: ByteArrayInputStream

    constructor(file: File) : this(file.getPath())
    constructor(name: String) {
        inner = ByteArrayInputStream(readAllBytes(name) ?: throw FileNotFoundException(name))
    }

    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    override fun available(): Int = inner.available()
    override fun close() = inner.close()

    companion object {
        internal fun readAllBytes(path: String): ByteArray? {
            val fp = fopen(path, "rb") ?: return null
            try {
                val chunks = ArrayList<ByteArray>()
                var total = 0
                while (true) {
                    val buf = ByteArray(64 * 1024)
                    val n = buf.usePinned { fread(it.addressOf(0), 1u, buf.size.toULong(), fp).toInt() }
                    if (n <= 0) break
                    chunks.add(if (n == buf.size) buf else buf.copyOf(n))
                    total += n
                }
                val out = ByteArray(total)
                var o = 0
                for (c in chunks) { c.copyInto(out, o); o += c.size }
                return out
            } finally {
                fclose(fp)
            }
        }
    }
}

abstract class OutputStream : Closeable, Flushable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray) = write(b, 0, b.size)
    open fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt() and 0xFF)
    }
    override fun flush() {}
    override fun close() {}
}

class ByteArrayOutputStream(initial: Int = 32) : OutputStream() {
    private var buf = ByteArray(if (initial > 0) initial else 32)
    private var count = 0

    private fun ensure(extra: Int) {
        if (count + extra <= buf.size) return
        var n = buf.size * 2
        while (n < count + extra) n *= 2
        buf = buf.copyOf(n)
    }

    override fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len); b.copyInto(buf, count, off, off + len); count += len
    }

    fun size(): Int = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    override fun toString(): String = toByteArray().decodeToString()
    fun writeTo(out: OutputStream) = out.write(buf, 0, count)
}

class FileOutputStream : OutputStream {
    private var fp: CPointer<FILE>?

    constructor(file: File, append: Boolean = false) : this(file.getPath(), append)
    constructor(name: String, append: Boolean = false) {
        fp = fopen(name, if (append) "ab" else "wb") ?: throw FileNotFoundException(name)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val f = fp ?: throw IOException("stream closed")
        if (len == 0) return
        b.usePinned { fwrite(it.addressOf(off), 1u, len.toULong(), f) }
    }

    fun getFD(): FileDescriptor = FileDescriptor(fp?.let { platform.posix.fileno(it) } ?: -1)
    override fun flush() { fp?.let { fflush(it) } }
    override fun close() { fp?.let { fclose(it) }; fp = null }
}

abstract class Writer : Closeable, Flushable {
    abstract fun write(str: String)
    open fun write(c: Int) = write(c.toChar().toString())
    open fun write(cbuf: CharArray, off: Int, len: Int) = write(cbuf.concatToString(off, off + len))
    open fun append(csq: CharSequence?): Writer { write(csq?.toString() ?: "null"); return this }
    override fun flush() {}
    override fun close() {}
}

class OutputStreamWriter(
    private val out: OutputStream,
    private val charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8,
) : Writer() {
    override fun write(str: String) = out.write(str.encodeToByteArray())
    override fun flush() = out.flush()
    override fun close() = out.close()
}

class FileWriter(file: File, append: Boolean = false) : Writer() {
    constructor(name: String, append: Boolean = false) : this(File(name), append)
    private val out = FileOutputStream(file, append)
    override fun write(str: String) = out.write(str.encodeToByteArray())
    override fun flush() = out.flush()
    override fun close() = out.close()
}

class StringWriter : Writer() {
    private val sb = StringBuilder()
    override fun write(str: String) { sb.append(str) }
    fun getBuffer(): StringBuilder = sb
    override fun toString(): String = sb.toString()
}

class BufferedWriter(private val inner: Writer, size: Int = 8192) : Writer() {
    override fun write(str: String) = inner.write(str)
    fun newLine() = inner.write("\n")
    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

class PrintWriter : Writer {
    private val inner: Writer
    private val autoFlush: Boolean
    constructor(w: Writer, autoFlush: Boolean = false) { inner = w; this.autoFlush = autoFlush }
    constructor(out: OutputStream, autoFlush: Boolean = false) {
        inner = OutputStreamWriter(out); this.autoFlush = autoFlush
    }
    constructor(file: File) { inner = FileWriter(file); autoFlush = false }
    constructor(name: String) { inner = FileWriter(name); autoFlush = false }

    override fun write(str: String) = inner.write(str)
    fun print(v: Any?) = inner.write(v?.toString() ?: "null")
    fun println(v: Any?) {
        inner.write(v?.toString() ?: "null"); inner.write("\n"); if (autoFlush) inner.flush()
    }
    fun println() { inner.write("\n"); if (autoFlush) inner.flush() }
    fun printf(fmt: String, vararg args: Any?) = inner.write(java.lang.formatJava(fmt, args))
    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

class FileReader(file: File) : Reader() {
    constructor(name: String) : this(File(name))
    private val inner = InputStreamReader(FileInputStream(file))
    override fun read(): Int = inner.read()
    override fun readText(): String = inner.readText()
    override fun close() = inner.close()
}

class StringReader(private val s: String) : Reader() {
    private var pos = 0
    override fun read(): Int = if (pos >= s.length) -1 else s[pos++].code
    override fun readText(): String { val r = s.substring(pos); pos = s.length; return r }
}

/** A real POSIX descriptor; -1 means "none".  The natives take the int. */
class FileDescriptor(private val fd: Int = -1) {
    fun getFd(): Int = fd
    fun valid(): Boolean = fd >= 0
    fun sync() { if (fd >= 0) platform.posix.fsync(fd) }
}

/** java.io.PrintStream, only as far as System.out and System.err need it. */
class PrintStream(private val toStderr: Boolean) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val text = b.decodeToString(off, off + len)
        val fd = if (toStderr) 2 else 1
        text.encodeToByteArray().usePinned {
            platform.posix.write(fd, it.addressOf(0), text.length.toULong())
        }
    }

    fun print(v: Any?) = write((v?.toString() ?: "null").encodeToByteArray())
    fun println(v: Any?) = write(((v?.toString() ?: "null") + "\n").encodeToByteArray())
    fun println() = write("\n".encodeToByteArray())
    fun printf(fmt: String, vararg args: Any?) =
        write(java.lang.formatJava(fmt, args).encodeToByteArray())
    override fun flush() {}
}
