/* android.database.Cursor over an in-memory row set.  Nothing here is SQLite:
 * the only provider in this process is MediaStore, which is a directory walk. */
package android.database

interface Cursor : kotlin.AutoCloseable {
    fun getCount(): Int
    fun getPosition(): Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun moveToPosition(position: Int): Boolean
    fun isAfterLast(): Boolean
    fun getColumnCount(): Int
    fun getColumnName(index: Int): String
    fun getColumnNames(): Array<String>
    fun getColumnIndex(name: String): Int
    fun getColumnIndexOrThrow(name: String): Int
    fun getString(index: Int): String?
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun getFloat(index: Int): Float
    fun getDouble(index: Int): Double
    fun isNull(index: Int): Boolean
    override fun close()
}

class CursorIndexOutOfBoundsException(message: String) : RuntimeException(message)

/** A Cursor over a fixed list of rows, one value per projection column. */
class MatrixCursor(private val columns: Array<String>) : Cursor {
    private val rows = ArrayList<Array<Any?>>()
    private var pos = -1

    fun addRow(values: Array<Any?>) { rows.add(values) }

    override fun getCount(): Int = rows.size
    override fun getPosition(): Int = pos
    override fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    override fun moveToNext(): Boolean { pos++; return pos < rows.size }
    override fun moveToPosition(position: Int): Boolean { pos = position; return position in rows.indices }
    override fun isAfterLast(): Boolean = pos >= rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(index: Int): String = columns[index]
    override fun getColumnNames(): Array<String> = columns

    override fun getColumnIndex(name: String): Int = columns.indexOfFirst { it == name }

    override fun getColumnIndexOrThrow(name: String): Int {
        val i = getColumnIndex(name)
        if (i < 0) throw IllegalArgumentException("column '$name' does not exist")
        return i
    }

    private fun value(index: Int): Any? {
        if (pos !in rows.indices) throw CursorIndexOutOfBoundsException("position $pos of ${rows.size}")
        return rows[pos][index]
    }

    override fun getString(index: Int): String? = value(index)?.toString()
    override fun getInt(index: Int): Int = (value(index) as? Number)?.toInt() ?: 0
    override fun getLong(index: Int): Long = (value(index) as? Number)?.toLong() ?: 0L
    override fun getFloat(index: Int): Float = (value(index) as? Number)?.toFloat() ?: 0f
    override fun getDouble(index: Int): Double = (value(index) as? Number)?.toDouble() ?: 0.0
    override fun isNull(index: Int): Boolean = value(index) == null
    override fun close() { pos = rows.size }
}
