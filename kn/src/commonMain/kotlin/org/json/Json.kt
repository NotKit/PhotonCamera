/* org.json, the writing half: JSONObject and JSONArray built up and
 * serialised, which is all the app does with them (RawVideoProcessor's .mcraw
 * metadata).  Output follows Android's JSONStringer: keys in insertion order,
 * no whitespace, integral doubles written without a fraction, and the same
 * escapes.  Parsing is absent; nothing here reads JSON back. */
package org.json

class JSONException(message: String?) : RuntimeException(message)

class JSONObject {
    private val values = LinkedHashMap<String, Any?>()

    fun put(name: String, value: Any?): JSONObject {
        if (value == null) values.remove(name) else values[name] = checkValue(value)
        return this
    }

    fun put(name: String, value: Boolean): JSONObject = put(name, value as Any)
    fun put(name: String, value: Int): JSONObject = put(name, value as Any)
    fun put(name: String, value: Long): JSONObject = put(name, value as Any)
    fun put(name: String, value: Double): JSONObject = put(name, value as Any)

    fun has(name: String): Boolean = values.containsKey(name)
    fun opt(name: String): Any? = values[name]
    fun length(): Int = values.size

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    internal fun write(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((k, v) in values) {
            if (!first) sb.append(',')
            first = false
            quote(sb, k)
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }
}

class JSONArray {
    private val values = ArrayList<Any?>()

    constructor()

    /** Android accepts any primitive array here, element by element. */
    constructor(array: Any?) {
        when (array) {
            null -> throw JSONException("Not a primitive array: null")
            is FloatArray -> array.forEach { values.add(checkValue(it.toDouble())) }
            is DoubleArray -> array.forEach { values.add(checkValue(it)) }
            is IntArray -> array.forEach { values.add(it) }
            is LongArray -> array.forEach { values.add(it) }
            is ShortArray -> array.forEach { values.add(it.toInt()) }
            is ByteArray -> array.forEach { values.add(it.toInt()) }
            is BooleanArray -> array.forEach { values.add(it) }
            is Array<*> -> array.forEach { values.add(it?.let { v -> checkValue(v) }) }
            is Collection<*> -> array.forEach { values.add(it?.let { v -> checkValue(v) }) }
            else -> throw JSONException("Not a primitive array: $array")
        }
    }

    fun put(value: Any?): JSONArray { values.add(value?.let { checkValue(it) }); return this }
    fun length(): Int = values.size
    fun opt(index: Int): Any? = values.getOrNull(index)

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    internal fun write(sb: StringBuilder) {
        sb.append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            writeValue(sb, v)
        }
        sb.append(']')
    }
}

private fun checkValue(v: Any): Any {
    val d = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        else -> return v
    }
    if (d.isNaN() || d.isInfinite()) throw JSONException("Forbidden numeric value: $d")
    return d
}

private fun writeValue(sb: StringBuilder, v: Any?) {
    when (v) {
        null -> sb.append("null")
        is JSONObject -> v.write(sb)
        is JSONArray -> v.write(sb)
        is Boolean -> sb.append(v)
        is Double -> sb.append(numberToString(v))
        is Float -> sb.append(numberToString(v.toDouble()))
        is Number -> sb.append(v.toString())
        else -> quote(sb, v.toString())
    }
}

/** JSONObject.numberToString: an integral value in long range drops its ".0". */
private fun numberToString(d: Double): String {
    val l = d.toLong()
    return if (d == l.toDouble() && l != Long.MIN_VALUE && l != Long.MAX_VALUE) l.toString() else d.toString()
}

private fun quote(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"', '\\', '/' -> sb.append('\\').append(c)
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code <= 0x1F) {
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
}
