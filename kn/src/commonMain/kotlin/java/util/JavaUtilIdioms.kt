/* The Java spellings j2k imports by name from java.util (jdk-stubs.py
 * EXT_TRIGGERS): Map.Entry accessors and the Java 8 Map defaults. */
@file:Suppress("NOTHING_TO_INLINE", "unused", "EXTENSION_SHADOWED_BY_MEMBER")

package java.util

fun <K, V> kotlin.collections.Map.Entry<K, V>.getKey(): K = key
fun <K, V> kotlin.collections.Map.Entry<K, V>.getValue(): V = value

// The receiver is the read-only Map j2k maps java.util.Map onto; the cast is
// the same one photoncam.knshim makes for put/remove/clear.
@Suppress("UNCHECKED_CAST")
fun <K, V> kotlin.collections.Map<K, V>.computeIfAbsent(k: K, f: (K) -> V): V {
    this[k]?.let { return it }
    val made = f(k)
    (this as kotlin.collections.MutableMap<K, V>)[k] = made
    return made
}

@Suppress("UNCHECKED_CAST")
fun <K, V> kotlin.collections.Map<K, V>.putIfAbsent(k: K, v: V): V? {
    this[k]?.let { return it }
    (this as kotlin.collections.MutableMap<K, V>)[k] = v
    return null
}

fun <K, V> kotlin.collections.Map<K, V>.getOrDefault(k: K, def: V): V = this[k] ?: def
