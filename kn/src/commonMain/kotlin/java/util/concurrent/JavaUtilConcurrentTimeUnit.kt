/* Copied from fenix-kn's androidshim/JavaUtilConcurrentTimeUnit.kt. */
package java.util.concurrent

enum class TimeUnit(private val toMillisFactorNumerator: Long, private val toMillisFactorDenominator: Long) {
	NANOSECONDS(1L, 1_000_000L),
	MICROSECONDS(1L, 1_000L),
	MILLISECONDS(1L, 1L),
	SECONDS(1_000L, 1L),
	MINUTES(60_000L, 1L),
	HOURS(3_600_000L, 1L),
	DAYS(86_400_000L, 1L),
	;

	/** Java's TimeUnit.toMillis(): truncating, like the JDK's integer division. */
	fun toMillis(duration: Long): Long =
		duration * toMillisFactorNumerator / toMillisFactorDenominator
}
