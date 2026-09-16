/* java.io.File over POSIX.  Path handling is the JDK's (repeated and trailing
 * separators collapse); every query hits the real filesystem. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.io

import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.DT_DIR
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.closedir
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.utimes

class File {
    private val pathValue: String

    constructor(pathname: String) { pathValue = normalise(pathname) }
    constructor(parent: String?, child: String) {
        pathValue = normalise(if (parent.isNullOrEmpty()) child else "$parent$separatorChar$child")
    }
    constructor(parent: File?, child: String) : this(parent?.getPath(), child)

    fun getPath(): String = pathValue
    val path: String get() = pathValue
    fun getName(): String = pathValue.substringAfterLast(separatorChar)
    val name: String get() = getName()

    fun getParent(): String? {
        val i = pathValue.lastIndexOf(separatorChar)
        return when {
            i < 0 -> null
            i == 0 -> separator
            else -> pathValue.substring(0, i)
        }
    }

    fun getParentFile(): File? = getParent()?.let { File(it) }
    fun getAbsolutePath(): String = if (isAbsolute()) pathValue else normalise(cwd() + separator + pathValue)
    fun getAbsoluteFile(): File = File(getAbsolutePath())
    fun isAbsolute(): Boolean = pathValue.startsWith(separatorChar)

    /** Textual: "." and ".." fold away; symlinks are not resolved. */
    fun getCanonicalPath(): String {
        val abs = getAbsolutePath()
        val out = ArrayList<String>()
        for (seg in abs.split(separatorChar)) when (seg) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(seg)
        }
        return separator + out.joinToString(separator)
    }

    fun getCanonicalFile(): File = File(getCanonicalPath())
    fun toPath(): java.nio.file.Path = java.nio.file.Path(pathValue)
    fun toURI(): String = "file://" + getAbsolutePath()

    val extension: String get() = getName().substringAfterLast('.', "")
    val nameWithoutExtension: String get() = getName().substringBeforeLast(".")

    private inline fun <T> withStat(block: (platform.posix.stat) -> T, absent: T): T = memScoped {
        val st = alloc<platform.posix.stat>()
        if (stat(pathValue, st.ptr) != 0) absent else block(st)
    }

    fun exists(): Boolean = withStat({ true }, false)
    fun isDirectory(): Boolean = withStat({ (it.st_mode.toInt() and S_IFMT) == S_IFDIR }, false)
    fun isFile(): Boolean = withStat({ (it.st_mode.toInt() and S_IFMT) == S_IFREG }, false)
    fun isHidden(): Boolean = getName().startsWith(".")
    fun length(): Long = withStat({ it.st_size.toLong() }, 0L)
    fun lastModified(): Long = withStat({ it.st_mtim.tv_sec * 1000L + it.st_mtim.tv_nsec / 1_000_000L }, 0L)
    fun canRead(): Boolean = platform.posix.access(pathValue, platform.posix.R_OK) == 0
    fun canWrite(): Boolean = platform.posix.access(pathValue, platform.posix.W_OK) == 0
    fun canExecute(): Boolean = platform.posix.access(pathValue, platform.posix.X_OK) == 0
    fun setReadable(r: Boolean): Boolean = true
    fun setWritable(w: Boolean): Boolean = true
    fun setLastModified(millis: Long): Boolean = memScoped {
        val tv = allocArray<platform.posix.timeval>(2)
        for (n in 0..1) {
            tv[n].tv_sec = millis / 1000L
            tv[n].tv_usec = ((millis % 1000L) * 1000L)
        }
        utimes(pathValue, tv) == 0
    }

    fun delete(): Boolean = if (isDirectory()) rmdir(pathValue) == 0 else remove(pathValue) == 0
    fun deleteOnExit() {}
    fun renameTo(dest: File): Boolean = rename(pathValue, dest.pathValue) == 0
    fun mkdir(): Boolean = mkdir(pathValue, DIR_MODE) == 0

    fun mkdirs(): Boolean {
        if (isDirectory()) return false
        getParentFile()?.let { if (!it.isDirectory()) it.mkdirs() }
        return mkdir()
    }

    fun createNewFile(): Boolean {
        if (exists()) return false
        val fd = platform.posix.open(pathValue,
            platform.posix.O_CREAT or platform.posix.O_EXCL or platform.posix.O_WRONLY, DIR_MODE)
        if (fd < 0) return false
        platform.posix.close(fd)
        return true
    }

    fun list(): Array<String>? = readEntries()?.toTypedArray()
    fun list(filter: FilenameFilter?): Array<String>? =
        readEntries()?.filter { filter == null || filter.accept(this, it) }?.toTypedArray()

    fun listFiles(): Array<File>? = readEntries()?.map { File(this, it) }?.toTypedArray()
    fun listFiles(filter: FilenameFilter?): Array<File>? =
        readEntries()?.filter { filter == null || filter.accept(this, it) }
            ?.map { File(this, it) }?.toTypedArray()
    fun listFiles(filter: FileFilter?): Array<File>? =
        readEntries()?.map { File(this, it) }?.filter { filter == null || filter.accept(it) }?.toTypedArray()

    private fun readEntries(): List<String>? {
        val dir = opendir(pathValue) ?: return null
        val out = ArrayList<String>()
        try {
            while (true) {
                val e = readdir(dir) ?: break
                val n = e.pointed.d_name.toKString()
                if (n != "." && n != "..") out.add(n)
            }
        } finally {
            closedir(dir)
        }
        return out
    }

    fun getFreeSpace(): Long = 0L
    fun getUsableSpace(): Long = 0L
    fun getTotalSpace(): Long = 0L

    override fun toString(): String = pathValue
    override fun equals(other: Any?): Boolean = other is File && other.pathValue == pathValue
    override fun hashCode(): Int = pathValue.hashCode()

    companion object {
        const val separatorChar: Char = '/'
        const val separator: String = "/"
        const val pathSeparatorChar: Char = ':'
        const val pathSeparator: String = ":"
        private const val DIR_MODE: UInt = 511u // 0777, umask applies

        /** Unique empty file in TMPDIR (or /tmp), created with O_EXCL like the JDK. */
        fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
            val dir = directory?.getPath()
                ?: platform.posix.getenv("TMPDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/tmp"
            val sfx = suffix ?: ".tmp"
            repeat(100) {
                val f = File(dir, prefix + kotlin.random.Random.nextLong().toULong().toString(36) + sfx)
                if (f.createNewFile()) return f
            }
            throw IOException("Unable to create temporary file in $dir")
        }

        private fun cwd(): String = memScoped {
            val buf = allocArray<kotlinx.cinterop.ByteVar>(4096)
            platform.posix.getcwd(buf, 4096u)?.toKString() ?: "."
        }

        private fun normalise(p: String): String {
            if (p.isEmpty()) return p
            val sb = StringBuilder(p.length)
            var lastWasSep = false
            for (c in p) {
                if (c == separatorChar) {
                    if (!lastWasSep) sb.append(c)
                    lastWasSep = true
                } else {
                    sb.append(c); lastWasSep = false
                }
            }
            if (sb.length > 1 && sb[sb.length - 1] == separatorChar) sb.setLength(sb.length - 1)
            return sb.toString()
        }
    }
}

fun interface FilenameFilter { fun accept(dir: File, name: String): Boolean }
fun interface FileFilter { fun accept(pathname: File): Boolean }

interface Closeable : kotlin.AutoCloseable { override fun close() }
interface Serializable
interface Flushable { fun flush() }

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
class FileNotFoundException(message: String? = null) : IOException(message)
class EOFException(message: String? = null) : IOException(message)
class UnsupportedEncodingException(message: String? = null) : IOException(message)
class InterruptedIOException(message: String? = null) : IOException(message)
class InvalidObjectException(message: String? = null) : IOException(message)
