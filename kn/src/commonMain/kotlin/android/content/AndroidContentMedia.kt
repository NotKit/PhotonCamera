/* ContentResolver's MediaStore half: the media index is the filesystem.
 *
 * query() walks the external storage root for image files and answers the
 * projection out of each file's own name, size and mtime.  The selection
 * grammar is the one the app writes -- "COL LIKE ?" and "COL = ?" joined by OR
 * or AND -- and the sort order is "COL ASC|DESC"; anything else is refused with
 * an IllegalArgumentException rather than quietly ignored.
 *
 * insert() creates the file's directory and returns its file:// URI: the
 * caller then writes through openOutputStream, as it does on Android.
 * delete() removes the file. */
package android.content

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "dng", "heic", "heif", "webp")

private class MediaRow(val file: File, val root: String) {
    val path: String get() = file.getAbsolutePath()
    val bucketPath: String get() = file.getParent() ?: root
    val relativePath: String
        get() = bucketPath.removePrefix(root).trim('/') + "/"

    fun column(name: String): Any? = when (name) {
        MediaStore.MediaColumns._ID -> path.hashCode().toLong() and 0xFFFFFFFFL
        MediaStore.MediaColumns.DATA -> path
        MediaStore.MediaColumns.DISPLAY_NAME -> file.getName()
        MediaStore.MediaColumns.SIZE -> file.length()
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN -> file.lastModified() / 1000L
        MediaStore.MediaColumns.MIME_TYPE -> mimeOf(file.getName())
        MediaStore.MediaColumns.RELATIVE_PATH -> relativePath
        MediaStore.MediaColumns.BUCKET_ID -> bucketPath.hashCode().toLong() and 0xFFFFFFFFL
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME -> bucketPath.substringAfterLast('/')
        MediaStore.MediaColumns.IS_PENDING -> 0
        else -> null
    }
}

internal fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heif"
    "dng" -> "image/x-adobe-dng"
    else -> "application/octet-stream"
}

/** One "COL LIKE ?" or "COL = ?" term, with its argument already substituted. */
private class Term(val column: String, val like: Boolean, val value: String) {
    fun matches(row: MediaRow): Boolean {
        val actual = row.column(column)?.toString() ?: return false
        if (!like) return actual == value
        // SQL LIKE: % is any run, _ is any one character.
        val rx = StringBuilder("^")
        for (c in value) when (c) {
            '%' -> rx.append(".*")
            '_' -> rx.append('.')
            in "\\^$.|?*+()[]{}" -> { rx.append('\\'); rx.append(c) }
            else -> rx.append(c)
        }
        rx.append('$')
        return Regex(rx.toString(), RegexOption.IGNORE_CASE).matches(actual)
    }
}

private class Selection(val terms: List<Term>, val anyOf: Boolean) {
    fun matches(row: MediaRow): Boolean =
        if (anyOf) terms.any { it.matches(row) } else terms.all { it.matches(row) }
}

private fun parseSelection(selection: String?, args: Array<String>?): Selection? {
    if (selection.isNullOrBlank()) return null
    val anyOf = selection.contains(" OR ", ignoreCase = true)
    val parts = selection.split(Regex("\\s+(?:OR|AND)\\s+", RegexOption.IGNORE_CASE))
    val terms = ArrayList<Term>()
    var argIndex = 0
    for (raw in parts) {
        val part = raw.trim().trim('(', ')').trim()
        val m = Regex("^([\\w.]+)\\s*(LIKE|=)\\s*\\?$", RegexOption.IGNORE_CASE).find(part)
            ?: throw IllegalArgumentException("unsupported MediaStore selection: $selection")
        val value = args?.getOrNull(argIndex++)
            ?: throw IllegalArgumentException("missing selection argument for: $part")
        terms.add(Term(m.groupValues[1], m.groupValues[2].equals("LIKE", true), value))
    }
    return Selection(terms, anyOf)
}

private fun walk(dir: File, out: MutableList<File>) {
    val entries = dir.listFiles() ?: return
    for (f in entries) {
        if (f.isDirectory()) walk(f, out)
        else if (f.getName().substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) out.add(f)
    }
}

internal fun mediaQuery(
    projection: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?,
): Cursor {
    val root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
    val files = ArrayList<File>()
    walk(File(root), files)
    var rows = files.map { MediaRow(it, root) }

    parseSelection(selection, selectionArgs)?.let { sel -> rows = rows.filter { sel.matches(it) } }

    if (!sortOrder.isNullOrBlank()) {
        val bits = sortOrder.trim().split(Regex("\\s+"))
        val column = bits[0]
        val descending = bits.size > 1 && bits[1].equals("DESC", ignoreCase = true)
        rows = rows.sortedWith(compareBy { row ->
            when (val v = row.column(column)) {
                is Number -> v.toDouble()
                else -> 0.0
            }
        })
        if (descending) rows = rows.reversed()
    }

    val columns = projection ?: arrayOf(
        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
    )
    val cursor = MatrixCursor(columns)
    for (row in rows) cursor.addRow(Array(columns.size) { row.column(columns[it]) })
    return cursor
}

internal fun mediaInsert(values: ContentValues): Uri? {
    val name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: return null
    val root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
    val relative = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
        ?: values.getAsString(MediaStore.MediaColumns.DATA)
        ?: ""
    val dir = if (relative.startsWith("/")) File(relative) else File(root, relative)
    dir.mkdirs()
    return Uri.fromFile(File(dir, name))
}
