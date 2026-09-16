/* com.anggrayudi.storage (SimpleStorage), the members util/SimpleStorageHelper
 * names.  SimpleStorage is a wrapper over the Storage Access Framework; on
 * Linux there is no SAF and no scoped storage, so a "storage id" is the
 * external storage root and every path is simply a path.  Access is always
 * granted, which is why getAccessibleAbsolutePaths always answers the root. */
package com.anggrayudi.storage.file

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

object StorageId {
    const val PRIMARY: String = "primary"
    const val DATA: String = "data"
    const val HOME: String = "home"
}

enum class DocumentFileType { ANY, FILE, FOLDER }

object DocumentFileCompat {
    /** SimpleStorage's Kotlin object is reached as INSTANCE from Java. */
    val INSTANCE: DocumentFileCompat get() = this

    private fun root(storageId: String): File =
        android.os.Environment.getExternalStorageDirectory()

    fun fromSimplePath(
        context: Context?,
        storageId: String = StorageId.PRIMARY,
        basePath: String = "",
        documentType: DocumentFileType = DocumentFileType.ANY,
        requiresWriteAccess: Boolean = false,
        considerRawFile: Boolean = true,
    ): DocumentFile? {
        val f = File(root(storageId), basePath)
        if (!f.exists()) return null
        return when (documentType) {
            DocumentFileType.FILE -> if (f.isFile()) DocumentFile.fromFile(f) else null
            DocumentFileType.FOLDER -> if (f.isDirectory()) DocumentFile.fromFile(f) else null
            DocumentFileType.ANY -> DocumentFile.fromFile(f)
        }
    }

    fun fromFullPath(context: Context?, fullPath: String): DocumentFile? {
        val f = File(fullPath)
        return if (f.exists()) DocumentFile.fromFile(f) else null
    }

    fun mkdirs(context: Context?, fullPath: String): DocumentFile? {
        val f = File(fullPath)
        f.mkdirs()
        return if (f.isDirectory()) DocumentFile.fromFile(f) else null
    }

    /** Everything under the external storage root is accessible here. */
    fun getAccessibleAbsolutePaths(context: Context?): Map<String, Set<String>> =
        mapOf(StorageId.PRIMARY to setOf(root(StorageId.PRIMARY).getAbsolutePath()))

    fun getStorageIds(context: Context?): List<String> = listOf(StorageId.PRIMARY)
    fun getRootPath(context: Context?, storageId: String): String = root(storageId).getAbsolutePath()
}

object DocumentFileUtils {
    fun openOutputStream(
        documentFile: DocumentFile,
        context: Context? = null,
        append: Boolean = false,
    ): java.io.OutputStream = java.io.FileOutputStream(documentFile.toFile(), append)

    fun openInputStream(documentFile: DocumentFile, context: Context? = null): java.io.InputStream =
        java.io.FileInputStream(documentFile.toFile())

    fun getAbsolutePath(documentFile: DocumentFile, context: Context? = null): String =
        documentFile.toFile().getAbsolutePath()
}

/** SimpleStorage's "storage id + base path" pair; here it is just a path. */
class FileFullPath(context: Context?, val storageId: String, val basePath: String) {
    val absolutePath: String
        get() = File(android.os.Environment.getExternalStorageDirectory(), basePath).getAbsolutePath()

    override fun toString(): String = absolutePath
}
