/* java.util.Collections: the static helpers Kotlin spells as members. */
package java.util

object Collections {
	fun <E> emptyList(): kotlin.collections.List<E> = kotlin.collections.emptyList()
	fun <K, V> emptyMap(): kotlin.collections.Map<K, V> = kotlin.collections.emptyMap()
	fun <E> emptySet(): kotlin.collections.Set<E> = kotlin.collections.emptySet()
	fun <E : Comparable<E>> max(c: kotlin.collections.Collection<E>): E = c.max()
	fun <E : Comparable<E>> min(c: kotlin.collections.Collection<E>): E = c.min()
	// The receiver is the read-only List j2k maps java.util.List onto; the cast
	// is the same one photoncam.knshim makes for add/remove/set.
	@Suppress("UNCHECKED_CAST")
	fun <E> sort(list: kotlin.collections.List<E>?, c: Comparator<in E>) {
		(list as? MutableList<E>)?.sortWith(c)
	}
	@Suppress("UNCHECKED_CAST")
	fun <E : Comparable<E>> sort(list: kotlin.collections.List<E>) = (list as MutableList<E>).sort()
	@Suppress("UNCHECKED_CAST")
	fun <E> reverse(list: kotlin.collections.List<E>) = (list as MutableList<E>).reverse()
	@Suppress("UNCHECKED_CAST")
	fun <E> shuffle(list: kotlin.collections.List<E>) = (list as MutableList<E>).shuffle()
	fun <E> unmodifiableList(list: kotlin.collections.List<E>): kotlin.collections.List<E> = list.toList()
	fun <K, V> unmodifiableMap(m: kotlin.collections.Map<K, V>): kotlin.collections.Map<K, V> = m.toMap()
	fun <E> synchronizedList(list: MutableList<E>): MutableList<E> = list
	fun <K, V> synchronizedMap(m: MutableMap<K, V>): MutableMap<K, V> = m
	fun <E> synchronizedSet(s: MutableSet<E>): MutableSet<E> = s
	fun <E> addAll(c: MutableCollection<E>, vararg items: E): Boolean = c.addAll(items)
	fun <E> singletonList(e: E): kotlin.collections.List<E> = mutableListOf(e)
	fun <E> singleton(e: E): kotlin.collections.Set<E> = mutableSetOf(e)
	fun <K, V> singletonMap(k: K, v: V): kotlin.collections.Map<K, V> = mutableMapOf(k to v)
	fun <E> unmodifiableSet(s: kotlin.collections.Set<E>): kotlin.collections.Set<E> = s.toSet()
	fun <E> unmodifiableCollection(c: kotlin.collections.Collection<E>): kotlin.collections.Collection<E> = c.toList()
}
