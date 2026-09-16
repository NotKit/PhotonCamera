/* org.apache.commons.io: the FileUtils/IOUtils members the app names. */
package org.apache.commons.io

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object FileUtils {
    fun readFileToByteArray(file: File): ByteArray =
        java.io.FileInputStream(file).use { it.readBytes() }

    fun readFileToString(file: File, charset: java.nio.charset.Charset): String =
        readFileToByteArray(file).decodeToString()

    fun writeByteArrayToFile(file: File, data: ByteArray) {
        file.getParentFile()?.mkdirs()
        java.io.FileOutputStream(file).use { it.write(data) }
    }

    fun writeStringToFile(file: File, data: String, charset: java.nio.charset.Charset) =
        writeByteArrayToFile(file, data.encodeToByteArray())

    fun copyFile(src: File, dst: File) = writeByteArrayToFile(dst, readFileToByteArray(src))

    fun copyInputStreamToFile(src: InputStream, dst: File) =
        writeByteArrayToFile(dst, src.readBytes())

    fun forceMkdir(dir: File) { dir.mkdirs() }

    /** Apache's contract: recursive, and an unreadable directory is an IOException. */
    fun deleteDirectory(dir: File) {
        if (!dir.exists()) return
        for (child in dir.listFiles() ?: throw java.io.IOException("cannot list " + dir.getPath())) {
            if (child.isDirectory()) deleteDirectory(child) else child.delete()
        }
        if (!dir.delete()) throw java.io.IOException("cannot delete " + dir.getPath())
    }
    fun deleteQuietly(file: File?): Boolean = file != null && file.delete()
    fun sizeOf(file: File): Long = file.length()
    fun getExtension(path: String?): String = FilenameUtils.getExtension(path)

    /** Apache's SI-less form: 1 KB is 1024 bytes, one unit, no decimals. */
    fun byteCountToDisplaySize(size: Long): String {
        val units = arrayOf("bytes", "KB", "MB", "GB", "TB")
        var v = size
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return "$v ${units[u]}"
    }
}

object IOUtils {
    fun toByteArray(input: InputStream): ByteArray = input.readBytes()
    fun toString(input: InputStream, charset: java.nio.charset.Charset): String =
        input.readBytes().decodeToString()
    fun copy(input: InputStream, output: OutputStream): Int {
        val bytes = input.readBytes()
        output.write(bytes)
        return bytes.size
    }
    fun closeQuietly(c: kotlin.AutoCloseable?) { try { c?.close() } catch (e: Exception) { } }
}

object FilenameUtils {
    fun getExtension(path: String?): String = path?.substringAfterLast('.', "") ?: ""
    fun getBaseName(path: String?): String =
        path?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
    fun getName(path: String?): String = path?.substringAfterLast('/') ?: ""
    fun removeExtension(path: String?): String = path?.substringBeforeLast('.') ?: ""
}
