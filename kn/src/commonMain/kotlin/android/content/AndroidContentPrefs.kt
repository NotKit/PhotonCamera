/* android.content.SharedPreferences, one file per name under the context's
 * prefs dir.  The on-disk form is one "type:key=value" line per entry, so the
 * stored type survives a round trip -- the settings code depends on getBoolean
 * throwing ClassCastException on a string, and on getAll() handing back a
 * Number for a numeric value. */
package android.content

import java.io.File

interface SharedPreferences {
    fun getAll(): Map<String, Any?>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener?)
    fun unregisterOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener?)

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?)
    }
}

private const val SET_SEP = ""

class FilePreferences(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        val text = java.io.FileInputStream(file).use { it.readBytes().decodeToString() }
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            val eq = line.indexOf('=', colon + 1)
            if (colon < 0 || eq < 0) continue
            val type = line.substring(0, colon)
            val key = decode(line.substring(colon + 1, eq))
            val raw = decode(line.substring(eq + 1))
            map[key] = when (type) {
                "i" -> raw.toIntOrNull() ?: 0
                "l" -> raw.toLongOrNull() ?: 0L
                "f" -> raw.toFloatOrNull() ?: 0f
                "b" -> raw == "true"
                "S" -> if (raw.isEmpty()) LinkedHashSet() else raw.split(SET_SEP).toMutableSet()
                else -> raw
            }
        }
    }

    private fun save() {
        file.getParentFile()?.mkdirs()
        val sb = StringBuilder()
        for ((k, v) in map) {
            val t: String
            val raw: String
            when (v) {
                is Int -> { t = "i"; raw = v.toString() }
                is Long -> { t = "l"; raw = v.toString() }
                is Float -> { t = "f"; raw = v.toString() }
                is Boolean -> { t = "b"; raw = v.toString() }
                is Set<*> -> { t = "S"; raw = v.joinToString(SET_SEP) }
                else -> { t = "s"; raw = v.toString() }
            }
            sb.append(t).append(':').append(encode(k)).append('=').append(encode(raw)).append('\n')
        }
        java.io.FileOutputStream(file).use { it.write(sb.toString().encodeToByteArray()) }
    }

    private fun encode(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e")

    private fun decode(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                sb.append(when (s[i]) { 'n' -> '\n'; 'e' -> '='; else -> s[i] })
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    override fun getAll(): Map<String, Any?> = LinkedHashMap(map)

    /** Android's contract: the wrong stored type is a ClassCastException. */
    private inline fun <reified T> typed(key: String, def: T): T {
        val v = map[key] ?: return def
        if (v !is T) throw ClassCastException("$key is not ${T::class.simpleName}")
        return v
    }

    override fun getString(key: String, defValue: String?): String? {
        val v = map[key] ?: return defValue
        if (v !is String) throw ClassCastException("$key is not a String")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = typed(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = typed(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = typed(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = typed(key, defValue)
    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) { if (l != null && l !in listeners) listeners.add(l) }

    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) { listeners.remove(l) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = ArrayList<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) removed.add(key) else pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values == null) removed.add(key) else pending[key] = LinkedHashSet(values)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }

        override fun commit(): Boolean {
            val changed = ArrayList<String>()
            if (clearAll) { changed.addAll(map.keys); map.clear() }
            for (k in removed) if (map.remove(k) != null) changed.add(k)
            for ((k, v) in pending) if (map.put(k, v) != v) changed.add(k)
            save()
            for (l in ArrayList(listeners)) for (k in changed) {
                l.onSharedPreferenceChanged(this@FilePreferences, k)
            }
            return true
        }

        override fun apply() { commit() }
    }
}
