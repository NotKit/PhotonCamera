/* java.util.function: the handful of shapes the app names, as fun interfaces. */
package java.util.function

fun interface Function<T, R> { fun apply(t: T): R }
fun interface BiFunction<T, U, R> { fun apply(t: T, u: U): R }
fun interface Consumer<T> { fun accept(t: T) }
fun interface BiConsumer<T, U> { fun accept(t: T, u: U) }
fun interface Supplier<T> { fun get(): T }
fun interface Predicate<T> { fun test(t: T): Boolean }
