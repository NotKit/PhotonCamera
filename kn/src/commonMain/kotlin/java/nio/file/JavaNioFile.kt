/* java.nio.file: a Path is a string, Files delegates to java.io. */
package java.nio.file

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

class Path(private val value: String) : kotlin.Comparable<Path> {
    fun toFile(): File = File(value)
    fun toAbsolutePath(): Path = Path(File(value).getAbsolutePath())
    fun getFileName(): Path = Path(File(value).getName())
    fun getParent(): Path? = File(value).getParent()?.let { Path(it) }
    fun resolve(other: String): Path = Path(File(value, other).getPath())
    fun resolve(other: Path): Path = resolve(other.value)
    fun normalize(): Path = Path(File(value).getCanonicalPath())
    fun startsWith(other: String): Boolean = value.startsWith(other)
    override fun compareTo(other: Path): Int = value.compareTo(other.value)
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is Path && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

object Paths {
    fun get(first: String, vararg more: String): Path =
        Path(if (more.isEmpty()) first else first + "/" + more.joinToString("/"))
}

/** Open options.  Only the ones this tree passes are modelled. */
enum class StandardOpenOption { READ, WRITE, CREATE, CREATE_NEW, APPEND, TRUNCATE_EXISTING }

interface OpenOption
object StandardCopyOption { val REPLACE_EXISTING = Any() }

class NoSuchFileException(message: String? = null) : java.io.IOException(message)

object Files {
    fun exists(p: Path): Boolean = p.toFile().exists()
    fun isDirectory(p: Path): Boolean = p.toFile().isDirectory()
    fun size(p: Path): Long = p.toFile().length()
    fun delete(p: Path) { if (!p.toFile().delete()) throw NoSuchFileException(p.toString()) }
    fun deleteIfExists(p: Path): Boolean = p.toFile().delete()

    fun createDirectories(p: Path): Path { p.toFile().mkdirs(); return p }
    fun createDirectory(p: Path): Path { p.toFile().mkdir(); return p }

    fun readAllBytes(p: Path): ByteArray =
        FileInputStream.readAllBytes(p.toString()) ?: throw java.io.FileNotFoundException(p.toString())

    fun readString(p: Path): String = readAllBytes(p).decodeToString()

    fun newOutputStream(p: Path, vararg options: StandardOpenOption): OutputStream =
        FileOutputStream(p.toFile(), options.contains(StandardOpenOption.APPEND))

    fun newInputStream(p: Path): java.io.InputStream = FileInputStream(p.toFile())

    fun write(p: Path, bytes: ByteArray, vararg options: StandardOpenOption): Path {
        newOutputStream(p, *options).use { it.write(bytes) }
        return p
    }

    fun copy(from: Path, to: Path, vararg options: Any?): Path {
        write(to, readAllBytes(from))
        return to
    }

    fun move(from: Path, to: Path, vararg options: Any?): Path {
        from.toFile().renameTo(to.toFile())
        return to
    }
}
