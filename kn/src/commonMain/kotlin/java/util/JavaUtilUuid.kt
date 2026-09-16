/* Copied from fenix-kn's androidshim/JavaUtilUuid.kt. */
package java.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

@Suppress("MagicNumber")
class UUID(private val mostSigBits: Long, private val leastSigBits: Long) : Comparable<UUID> {

	fun getMostSignificantBits(): Long = mostSigBits
	fun getLeastSignificantBits(): Long = leastSigBits

	fun version(): Int = ((mostSigBits shr 12) and 0x0fL).toInt()
	fun variant(): Int =
		((leastSigBits ushr (64 - (leastSigBits ushr 62).toInt())) and (leastSigBits shr 63)).toInt()

	override fun toString(): String = buildString(36) {
		append(hex(mostSigBits shr 32, 8)); append('-')
		append(hex(mostSigBits shr 16, 4)); append('-')
		append(hex(mostSigBits, 4)); append('-')
		append(hex(leastSigBits shr 48, 4)); append('-')
		append(hex(leastSigBits, 12))
	}

	override fun equals(other: Any?): Boolean =
		other is UUID && other.mostSigBits == mostSigBits && other.leastSigBits == leastSigBits

	override fun hashCode(): Int {
		val hilo = mostSigBits xor leastSigBits
		return (hilo shr 32).toInt() xor hilo.toInt()
	}

	override fun compareTo(other: UUID): Int = when {
		mostSigBits < other.mostSigBits -> -1
		mostSigBits > other.mostSigBits -> 1
		leastSigBits < other.leastSigBits -> -1
		leastSigBits > other.leastSigBits -> 1
		else -> 0
	}

	companion object {
		private const val DIGITS = "0123456789abcdef"

		private fun hex(value: Long, digits: Int): String {
			val sb = StringBuilder(digits)
			for (i in digits - 1 downTo 0) {
				sb.append(DIGITS[((value shr (i * 4)) and 0xfL).toInt()])
			}
			return sb.toString()
		}

		/** RFC 4122 version 4, variant 1 -- java.util.UUID.randomUUID()'s contract. */
		fun randomUUID(): UUID {
			val b = randomBytes(16)
			b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
			b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // variant 1 (10xx)
			var msb = 0L
			var lsb = 0L
			for (i in 0 until 8) msb = (msb shl 8) or (b[i].toLong() and 0xffL)
			for (i in 8 until 16) lsb = (lsb shl 8) or (b[i].toLong() and 0xffL)
			return UUID(msb, lsb)
		}

		fun fromString(name: String): UUID {
			val parts = name.split('-')
			require(parts.size == 5) { "Invalid UUID string: $name" }
			var msb = parts[0].toULong(16).toLong()
			msb = (msb shl 16) or parts[1].toULong(16).toLong()
			msb = (msb shl 16) or parts[2].toULong(16).toLong()
			var lsb = parts[3].toULong(16).toLong()
			lsb = (lsb shl 48) or parts[4].toULong(16).toLong()
			return UUID(msb, lsb)
		}

		@OptIn(ExperimentalForeignApi::class)
		private fun randomBytes(n: Int): ByteArray {
			val out = ByteArray(n)
			val f = fopen("/dev/urandom", "rb")
			if (f != null) {
				val got = out.usePinned { fread(it.addressOf(0), 1u, n.toULong(), f).toInt() }
				fclose(f)
				if (got == n) return out
			}
			// Announced, never silent: a UUID off a clock is not a UUID.
			platform.posix.fprintf(
				platform.posix.stderr,
				"[androidshim] UUID.randomUUID: /dev/urandom unavailable, using clock entropy\n",
			)
			var seed = java.lang.System.currentTimeMillis()
			for (i in 0 until n) {
				seed = seed * 6364136223846793005L + 1442695040888963407L
				out[i] = (seed ushr 33).toByte()
			}
			return out
		}
	}
}
