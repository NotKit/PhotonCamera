/* android.content: Intent, ComponentName, ContentValues, ContentUris.
 *
 * There is no activity manager in this process, so Context.startActivity has
 * nothing to hand an Intent to; it says so in the log and returns.  Everything
 * an Intent carries -- action, data, type, component, flags, extras -- is real,
 * because the host may well read one back. */
package android.content

import android.net.Uri

class ComponentName {
    private val pkg: String
    private val cls: String

    constructor(packageName: String, className: String) { pkg = packageName; cls = className }
    constructor(context: Context, className: String) : this(context.getPackageName(), className)

    fun getPackageName(): String = pkg
    fun getClassName(): String = cls
    fun getShortClassName(): String = cls.substringAfterLast('.')
    fun flattenToString(): String = "$pkg/$cls"

    override fun toString(): String = "ComponentName{$pkg/$cls}"
    override fun equals(other: Any?): Boolean =
        other is ComponentName && other.pkg == pkg && other.cls == cls
    override fun hashCode(): Int = pkg.hashCode() * 31 + cls.hashCode()
}

class Intent() {
    private var action: String? = null
    private var data: Uri? = null
    private var type: String? = null
    private var component: ComponentName? = null
    private var flags: Int = 0
    private val extras = android.os.Bundle()

    constructor(action: String?) : this() { this.action = action }
    constructor(context: Context?, className: String) : this() {
        component = if (context != null) ComponentName(context, className) else null
    }

    fun getAction(): String? = action
    fun setAction(a: String?): Intent { action = a; return this }
    fun getData(): Uri? = data
    fun setData(d: Uri?): Intent { data = d; return this }
    fun getType(): String? = type
    fun setType(t: String?): Intent { type = t; return this }
    fun setDataAndType(d: Uri?, t: String?): Intent { data = d; type = t; return this }
    fun getComponent(): ComponentName? = component
    fun setComponent(c: ComponentName?): Intent { component = c; return this }
    fun getFlags(): Int = flags
    fun setFlags(f: Int): Intent { flags = f; return this }
    fun addFlags(f: Int): Intent { flags = flags or f; return this }
    fun getExtras(): android.os.Bundle = extras

    fun putExtra(name: String, value: String?): Intent { extras.putString(name, value); return this }
    fun putExtra(name: String, value: Int): Intent { extras.putInt(name, value); return this }
    fun putExtra(name: String, value: Long): Intent { extras.putLong(name, value); return this }
    fun putExtra(name: String, value: Boolean): Intent { extras.putBoolean(name, value); return this }
    fun getStringExtra(name: String): String? = extras.getString(name)
    fun getIntExtra(name: String, def: Int): Int = extras.getInt(name, def)
    fun getBooleanExtra(name: String, def: Boolean): Boolean = extras.getBoolean(name, def)

    override fun toString(): String =
        "Intent{action=$action data=$data component=$component flags=0x${flags.toString(16)}}"

    companion object {
        const val ACTION_VIEW: String = "android.intent.action.VIEW"
        const val ACTION_SEND: String = "android.intent.action.SEND"
        const val ACTION_MAIN: String = "android.intent.action.MAIN"
        const val ACTION_GET_CONTENT: String = "android.intent.action.GET_CONTENT"
        const val FLAG_ACTIVITY_NEW_TASK: Int = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TASK: Int = 0x00008000
        const val FLAG_ACTIVITY_CLEAR_TOP: Int = 0x04000000
        const val FLAG_ACTIVITY_NO_ANIMATION: Int = 0x00010000
        const val FLAG_GRANT_READ_URI_PERMISSION: Int = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION: Int = 0x00000002
    }
}

/** The one thing the app throws out of an intent sender. */
object IntentSender {
    class SendIntentException(message: String? = null) : Exception(message)
}

class ContentValues {
    private val map = LinkedHashMap<String, Any?>()

    fun put(key: String, value: String?) { map[key] = value }
    fun put(key: String, value: Int?) { map[key] = value }
    fun put(key: String, value: Long?) { map[key] = value }
    fun put(key: String, value: Float?) { map[key] = value }
    fun put(key: String, value: Double?) { map[key] = value }
    fun put(key: String, value: Boolean?) { map[key] = value }

    fun get(key: String): Any? = map[key]
    fun getAsString(key: String): String? = map[key]?.toString()
    fun getAsInteger(key: String): Int? = (map[key] as? Number)?.toInt()
    fun getAsLong(key: String): Long? = (map[key] as? Number)?.toLong()
    fun getAsBoolean(key: String): Boolean? = map[key] as? Boolean
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun size(): Int = map.size
    fun keySet(): Set<String> = map.keys
    override fun toString(): String = map.toString()
}

object ContentUris {
    fun withAppendedId(base: Uri, id: Long): Uri = Uri.withAppendedPath(base, id.toString())
    fun parseId(uri: Uri): Long = uri.getLastPathSegment()?.toLongOrNull() ?: -1L
}
