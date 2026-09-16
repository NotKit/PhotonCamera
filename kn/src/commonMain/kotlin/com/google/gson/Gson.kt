/* com.google.gson, the subset this app uses: the JSON tree (JsonElement and
 * friends), a parser, a writer with Gson's pretty-printing, and toJson/fromJson
 * over maps, lists and primitives.
 *
 * WHAT IS ABSENT, AND WHY: fromJson(x, SomeClass.class) and toJson(someObject)
 * for an arbitrary class.  Gson does that by reflecting over the fields, and
 * Kotlin/Native has no reflection at all, so it cannot be made real here.  Both
 * throw UnsupportedOperationException naming the class, and the fix is in the
 * Java: build the JsonObject explicitly, as DynamicNoiseStore now does.
 * @SerializedName is kept as an annotation so the Java still reads right. */
package com.google.gson

import java.io.Reader
import java.io.Writer

sealed class JsonElement {
    open fun isJsonObject(): Boolean = this is JsonObject
    open fun isJsonArray(): Boolean = this is JsonArray
    open fun isJsonPrimitive(): Boolean = this is JsonPrimitive
    open fun isJsonNull(): Boolean = this is JsonNull

    fun getAsJsonObject(): JsonObject = this as? JsonObject
        ?: throw IllegalStateException("not a JSON object: $this")
    fun getAsJsonArray(): JsonArray = this as? JsonArray
        ?: throw IllegalStateException("not a JSON array: $this")
    fun getAsJsonPrimitive(): JsonPrimitive = this as? JsonPrimitive
        ?: throw IllegalStateException("not a JSON primitive: $this")

    open fun getAsString(): String = getAsJsonPrimitive().getAsString()
    open fun getAsInt(): Int = getAsJsonPrimitive().getAsInt()
    open fun getAsLong(): Long = getAsJsonPrimitive().getAsLong()
    open fun getAsFloat(): Float = getAsJsonPrimitive().getAsFloat()
    open fun getAsDouble(): Double = getAsJsonPrimitive().getAsDouble()
    open fun getAsBoolean(): Boolean = getAsJsonPrimitive().getAsBoolean()

    override fun toString(): String = JsonWriter.write(this, indent = null)
}

object JsonNull : JsonElement()

class JsonPrimitive private constructor(private val value: Any) : JsonElement() {
    constructor(v: String) : this(v as Any)
    constructor(v: Number) : this(v as Any)
    constructor(v: Boolean) : this(v as Any)

    fun isString(): Boolean = value is String
    fun isNumber(): Boolean = value is Number
    fun isBoolean(): Boolean = value is Boolean

    override fun getAsString(): String = value.toString()
    override fun getAsInt(): Int = (value as? Number)?.toInt() ?: getAsString().toInt()
    override fun getAsLong(): Long = (value as? Number)?.toLong() ?: getAsString().toLong()
    override fun getAsFloat(): Float = (value as? Number)?.toFloat() ?: getAsString().toFloat()
    override fun getAsDouble(): Double = (value as? Number)?.toDouble() ?: getAsString().toDouble()
    override fun getAsBoolean(): Boolean = value as? Boolean ?: getAsString().toBoolean()

    /** The raw Kotlin value, for the map conversions below. */
    internal fun raw(): Any = value

    override fun equals(other: Any?): Boolean = other is JsonPrimitive && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

class JsonObject : JsonElement() {
    private val members = LinkedHashMap<String, JsonElement>()

    fun add(property: String, value: JsonElement?) { members[property] = value ?: JsonNull }
    fun addProperty(property: String, value: String?) {
        members[property] = if (value == null) JsonNull else JsonPrimitive(value)
    }
    fun addProperty(property: String, value: Number?) {
        members[property] = if (value == null) JsonNull else JsonPrimitive(value)
    }
    fun addProperty(property: String, value: Boolean?) {
        members[property] = if (value == null) JsonNull else JsonPrimitive(value)
    }

    fun remove(property: String): JsonElement? = members.remove(property)
    fun has(property: String): Boolean = members.containsKey(property)
    fun get(property: String): JsonElement? = members[property]
    fun getAsJsonObject(property: String): JsonObject? = members[property]?.let {
        if (it is JsonObject) it else null
    }
    fun getAsJsonArray(property: String): JsonArray? = members[property]?.let {
        if (it is JsonArray) it else null
    }
    fun getAsJsonPrimitive(property: String): JsonPrimitive? = members[property] as? JsonPrimitive
    fun entrySet(): Set<Map.Entry<String, JsonElement>> = members.entries
    fun keySet(): Set<String> = members.keys
    fun size(): Int = members.size
}

class JsonArray : JsonElement(), Iterable<JsonElement> {
    private val elements = ArrayList<JsonElement>()

    fun add(e: JsonElement?) { elements.add(e ?: JsonNull) }
    fun add(v: String?) { elements.add(if (v == null) JsonNull else JsonPrimitive(v)) }
    fun add(v: Number?) { elements.add(if (v == null) JsonNull else JsonPrimitive(v)) }
    fun get(i: Int): JsonElement = elements[i]
    fun size(): Int = elements.size
    override fun iterator(): Iterator<JsonElement> = elements.iterator()
}

class JsonSyntaxException(message: String) : RuntimeException(message)
class JsonParseException(message: String) : RuntimeException(message)

object JsonParser {
    fun parseString(json: String): JsonElement = JsonReader(json).parse()
    fun parseReader(reader: Reader): JsonElement = parseString(reader.readText())
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun parse(): JsonElement {
        val e = value()
        skipWs()
        return e
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): JsonElement {
        skipWs()
        if (i >= s.length) throw JsonSyntaxException("unexpected end of JSON")
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonPrimitive(string())
            't' -> { expect("true"); JsonPrimitive(true) }
            'f' -> { expect("false"); JsonPrimitive(false) }
            'n' -> { expect("null"); JsonNull }
            else -> number()
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw JsonSyntaxException("expected $word at $i")
        i += word.length
    }

    private fun obj(): JsonObject {
        val o = JsonObject()
        i++ // {
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            skipWs()
            val k = string()
            skipWs()
            if (s[i] != ':') throw JsonSyntaxException("expected ':' at $i")
            i++
            o.add(k, value())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == '}' -> { i++; return o }
                else -> throw JsonSyntaxException("expected ',' or '}' at $i")
            }
        }
    }

    private fun arr(): JsonArray {
        val a = JsonArray()
        i++ // [
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.add(value())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> { i++; return a }
                else -> throw JsonSyntaxException("expected ',' or ']' at $i")
            }
        }
    }

    private fun string(): String {
        if (s[i] != '"') throw JsonSyntaxException("expected '\"' at $i")
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            val c = s[i]
            if (c == '\\') {
                i++
                when (val e = s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        i++ // closing quote
        return sb.toString()
    }

    private fun number(): JsonPrimitive {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val text = s.substring(start, i)
        if (text.isEmpty()) throw JsonSyntaxException("expected a value at $start")
        val asLong = text.toLongOrNull()
        return if (asLong != null) JsonPrimitive(asLong) else JsonPrimitive(text.toDouble())
    }
}

internal object JsonWriter {
    fun write(e: JsonElement, indent: String?): String {
        val sb = StringBuilder()
        emit(sb, e, indent, 0)
        return sb.toString()
    }

    private fun emit(sb: StringBuilder, e: JsonElement, indent: String?, depth: Int) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> when (val v = e.raw()) {
                is String -> quote(sb, v)
                else -> sb.append(v.toString())
            }
            is JsonArray -> {
                if (e.size() == 0) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in e) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, depth + 1)
                    emit(sb, item, indent, depth + 1)
                }
                newline(sb, indent, depth)
                sb.append(']')
            }
            is JsonObject -> {
                if (e.size() == 0) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in e.entrySet()) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, depth + 1)
                    quote(sb, k)
                    sb.append(':')
                    if (indent != null) sb.append(' ')
                    emit(sb, v, indent, depth + 1)
                }
                newline(sb, indent, depth)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        sb.append('\n')
        repeat(depth) { sb.append(indent) }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

/** A type token.  An INTERFACE, because `new TypeToken<X>(){}` converts to
 *  `object : TypeToken<X> {}` with no constructor call.  Only Map and List
 *  shapes are answerable without reflection. */
interface TypeToken<T> : java.lang.reflect.Type {
    fun getType(): java.lang.reflect.Type = this
}

class Gson internal constructor(private val indent: String?) {
    constructor() : this(null)

    fun toJson(src: Any?): String = JsonWriter.write(toTree(src), indent)

    fun toJson(src: Any?, writer: Writer) { writer.write(toJson(src)) }

    fun toJsonTree(src: Any?): JsonElement = toTree(src)

    /** Map and List targets only; see the file header for what is absent. */
    fun <T : Any> fromJson(json: String?, classOfT: kotlin.reflect.KClass<T>): T? {
        if (json.isNullOrEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return fromTree(JsonParser.parseString(json), classOfT) as T?
    }

    fun <T : Any> fromJson(reader: Reader, classOfT: kotlin.reflect.KClass<T>): T? =
        fromJson(reader.readText(), classOfT)

    /** TypeToken overloads: the token names a Map or a List, and only the
     *  outer shape is used -- the values come back as String/Number/Boolean. */
    fun <T> fromJson(json: String?, type: java.lang.reflect.Type): T? {
        if (json.isNullOrEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return toPlain(JsonParser.parseString(json)) as T?
    }

    fun <T> fromJson(element: JsonElement?, type: java.lang.reflect.Type): T? {
        if (element == null || element.isJsonNull()) return null
        @Suppress("UNCHECKED_CAST")
        return toPlain(element) as T?
    }

    private fun fromTree(e: JsonElement, cls: kotlin.reflect.KClass<*>): Any? = when {
        e.isJsonNull() -> null
        cls == JsonObject::class || cls == JsonElement::class || cls == JsonArray::class -> e
        e is JsonObject -> toPlain(e)
        e is JsonArray -> toPlain(e)
        else -> throw UnsupportedOperationException(
            "Gson.fromJson into ${cls.simpleName} needs reflection, which " +
                "Kotlin/Native has not got: build the JsonObject explicitly instead",
        )
    }

    private fun toPlain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> e.raw()
        is JsonArray -> e.map { toPlain(it) }.toMutableList()
        is JsonObject -> LinkedHashMap<String, Any?>().also { m ->
            for ((k, v) in e.entrySet()) m[k] = toPlain(v)
        }
    }

    private fun toTree(src: Any?): JsonElement = when (src) {
        null -> JsonNull
        is JsonElement -> src
        is String -> JsonPrimitive(src)
        is Number -> JsonPrimitive(src)
        is Boolean -> JsonPrimitive(src)
        is Map<*, *> -> JsonObject().also { o ->
            for ((k, v) in src) o.add(k.toString(), toTree(v))
        }
        is Iterable<*> -> JsonArray().also { a -> for (v in src) a.add(toTree(v)) }
        is Array<*> -> JsonArray().also { a -> for (v in src) a.add(toTree(v)) }
        else -> throw UnsupportedOperationException(
            "Gson.toJson of ${src::class.simpleName} needs reflection, which " +
                "Kotlin/Native has not got: build the JsonObject explicitly instead",
        )
    }
}

class GsonBuilder {
    private var indent: String? = null
    fun setPrettyPrinting(): GsonBuilder { indent = "  "; return this }
    fun serializeNulls(): GsonBuilder = this
    fun disableHtmlEscaping(): GsonBuilder = this
    fun create(): Gson = Gson(indent)
}
