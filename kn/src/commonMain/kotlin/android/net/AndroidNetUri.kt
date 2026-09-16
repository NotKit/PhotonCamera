/* android.net.Uri.  On this port every URI the app handles is a local path, so
 * a Uri is a parsed scheme/authority/path triple and file:// round-trips
 * exactly.  A content:// URI's getPath() is its path, which is what
 * ContentResolver here opens. */
package android.net

class Uri private constructor(
    private val scheme: String?,
    private val authority: String?,
    private val pathValue: String,
    private val query: String?,
    private val fragment: String?,
) : kotlin.Comparable<Uri> {

    fun getScheme(): String? = scheme
    fun getAuthority(): String? = authority
    fun getHost(): String? = authority?.substringAfterLast('@')?.substringBefore(':')
    fun getPath(): String? = pathValue
    fun getQuery(): String? = query
    fun getFragment(): String? = fragment
    fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    fun getPathSegments(): List<String> = pathValue.split('/').filter { it.isNotEmpty() }
    fun isAbsolute(): Boolean = scheme != null
    fun isRelative(): Boolean = scheme == null

    fun getQueryParameter(key: String): String? = query?.split('&')
        ?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")

    fun buildUpon(): Builder = Builder()
        .scheme(scheme).authority(authority).path(pathValue).query(query).fragment(fragment)

    override fun compareTo(other: Uri): Int = toString().compareTo(other.toString())
    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    override fun toString(): String = buildString {
        if (scheme != null) { append(scheme); append(':') }
        if (authority != null) { append("//"); append(authority) }
        append(pathValue)
        if (query != null) { append('?'); append(query) }
        if (fragment != null) { append('#'); append(fragment) }
    }

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String = ""
        private var query: String? = null
        private var fragment: String? = null

        fun scheme(v: String?): Builder { scheme = v; return this }
        fun authority(v: String?): Builder { authority = v; return this }
        fun path(v: String?): Builder { path = v ?: ""; return this }
        fun appendPath(v: String): Builder { path = if (path.endsWith("/")) path + v else "$path/$v"; return this }
        fun query(v: String?): Builder { query = v; return this }
        fun fragment(v: String?): Builder { fragment = v; return this }
        fun build(): Uri = Uri(scheme, authority, path, query, fragment)
        override fun toString(): String = build().toString()
    }

    companion object {
        val EMPTY: Uri = Uri(null, null, "", null, null)

        fun parse(s: String): Uri {
            var rest = s
            var scheme: String? = null
            val colon = rest.indexOf(':')
            val slash = rest.indexOf('/')
            if (colon > 0 && (slash < 0 || colon < slash)) {
                scheme = rest.substring(0, colon)
                rest = rest.substring(colon + 1)
            }
            var authority: String? = null
            if (rest.startsWith("//")) {
                rest = rest.substring(2)
                val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                authority = if (end < 0) rest else rest.substring(0, end)
                rest = if (end < 0) "" else rest.substring(end)
            }
            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash) }
            var query: String? = null
            val q = rest.indexOf('?')
            if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q) }
            return Uri(scheme, authority, rest, query, fragment)
        }

        fun fromFile(file: java.io.File): Uri = Uri("file", "", file.getAbsolutePath(), null, null)

        fun withAppendedPath(base: Uri, segment: String): Uri = base.buildUpon().appendPath(segment).build()

        fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() || c in "_-!.~'()*") append(c)
                else { append('%'); append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')) }
            }
        }

        fun decode(s: String): String {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
                } else {
                    for (b in c.toString().encodeToByteArray()) out.add(b)
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}
