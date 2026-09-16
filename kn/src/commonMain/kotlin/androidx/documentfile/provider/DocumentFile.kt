/* androidx.documentfile.provider.DocumentFile.
 *
 * SAF exists because Android 10 took the filesystem away.  On Linux it never
 * did, so a DocumentFile here IS a java.io.File: every member is the plain
 * filesystem operation, and getUri() is the file:// URI of the same path.
 * That is what makes the storage seam in util/SimpleStorageHelper plain. */
package androidx.documentfile.provider

import android.net.Uri
import java.io.File

class DocumentFile private constructor(private val file: File) {
    fun getName(): String? = file.getName()
    fun getUri(): Uri = Uri.fromFile(file)
    fun getParentFile(): DocumentFile? = file.getParentFile()?.let { DocumentFile(it) }
    fun getType(): String? = if (file.isDirectory()) null else "application/octet-stream"
    fun isFile(): Boolean = file.isFile()
    fun isDirectory(): Boolean = file.isDirectory()
    fun exists(): Boolean = file.exists()
    fun canRead(): Boolean = file.canRead()
    fun canWrite(): Boolean = file.canWrite()
    fun length(): Long = file.length()
    fun lastModified(): Long = file.lastModified()
    fun delete(): Boolean = file.delete()
    fun renameTo(displayName: String): Boolean =
        file.renameTo(File(file.getParent() ?: ".", displayName))

    fun listFiles(): Array<DocumentFile> =
        (file.listFiles() ?: emptyArray()).map { DocumentFile(it) }.toTypedArray()

    fun findFile(displayName: String): DocumentFile? {
        val child = File(file, displayName)
        return if (child.exists()) DocumentFile(child) else null
    }

    fun createFile(mimeType: String, displayName: String): DocumentFile? {
        val child = File(file, displayName)
        child.getParentFile()?.mkdirs()
        if (!child.exists() && !child.createNewFile()) return null
        return DocumentFile(child)
    }

    fun createDirectory(displayName: String): DocumentFile? {
        val child = File(file, displayName)
        return if (child.mkdirs() || child.isDirectory()) DocumentFile(child) else null
    }

    /** The plain path behind this document, which on Linux is all there is. */
    fun toFile(): File = file

    override fun toString(): String = file.getPath()

    companion object {
        fun fromFile(file: File): DocumentFile = DocumentFile(file)
        fun fromSingleUri(context: android.content.Context?, uri: Uri): DocumentFile? =
            uri.getPath()?.let { DocumentFile(File(it)) }
        fun fromTreeUri(context: android.content.Context?, uri: Uri): DocumentFile? =
            fromSingleUri(context, uri)
        fun isDocumentUri(context: android.content.Context?, uri: Uri?): Boolean = uri != null
    }
}
