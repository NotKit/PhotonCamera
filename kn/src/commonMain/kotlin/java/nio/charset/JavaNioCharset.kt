/* Copied from fenix-kn's androidshim/JavaNioCharset.kt. */
package java.nio.charset

/** The JDK's: a legal name that no installed provider supports. */
class UnsupportedCharsetException(val charsetName: String) :
	IllegalArgumentException("Unsupported charset: $charsetName")

/** The JDK's: a name that is not a legal charset name at all. */
class IllegalCharsetNameException(val charsetName: String) :
	IllegalArgumentException("Illegal charset name: $charsetName")

sealed class Charset(
	private val canonical: String,
	internal val aliases: Set<String>,
) {
	fun name(): String = canonical

	fun displayName(): String = canonical

	abstract fun decode(bytes: ByteArray): String

	abstract fun encode(text: String): ByteArray

	override fun toString(): String = canonical

	override fun equals(other: Any?): Boolean = other is Charset && other.canonical == canonical

	override fun hashCode(): Int = canonical.hashCode()

	companion object {
		private val ALL: List<Charset> get() = listOf(Utf8, Iso88591, UsAscii)

		/**
		 * The JDK's contract, including both of its throws.  Response.kt:66 is
		 * inside a catch(Exception), so an unsupported name there becomes
		 * upstream's own UTF-8 fallback -- see the header.
		 */
		fun forName(charsetName: String): Charset {
			if (charsetName.isEmpty() || charsetName.any { it !in ' '..'~' }) {
				throw IllegalCharsetNameException(charsetName)
			}
			val key = charsetName.trim().lowercase()
			for (cs in ALL) {
				if (cs.name().lowercase() == key || key in cs.aliases) return cs
			}
			throw UnsupportedCharsetException(charsetName)
		}

		fun isSupported(charsetName: String): Boolean =
			try {
				forName(charsetName)
				true
			} catch (e: IllegalArgumentException) {
				false
			}

		fun defaultCharset(): Charset = Utf8
	}
}

/** Kotlin/Native's own UTF-8 codec: decodeToString / encodeToByteArray. */
internal object Utf8 : Charset("UTF-8", setOf("utf8", "unicode-1-1-utf-8")) {
	override fun decode(bytes: ByteArray): String = bytes.decodeToString()

	override fun encode(text: String): ByteArray = text.encodeToByteArray()
}

internal object Iso88591 : Charset(
	"ISO-8859-1",
	setOf("iso8859-1", "iso_8859-1", "latin1", "l1", "iso-latin-1", "8859_1", "cp819", "ibm819"),
) {
	override fun decode(bytes: ByteArray): String =
		buildString(bytes.size) { for (b in bytes) append((b.toInt() and 0xFF).toChar()) }

	override fun encode(text: String): ByteArray =
		ByteArray(text.length) { i ->
			val c = text[i].code
			if (c <= 0xFF) c.toByte() else '?'.code.toByte()
		}
}

internal object UsAscii : Charset(
	"US-ASCII",
	setOf("ascii", "iso646-us", "ansi_x3.4-1968", "us", "646"),
) {
	override fun decode(bytes: ByteArray): String =
		buildString(bytes.size) { for (b in bytes) append(if (b >= 0) b.toInt().toChar() else '�') }

	override fun encode(text: String): ByteArray =
		ByteArray(text.length) { i ->
			val c = text[i].code
			if (c <= 0x7F) c.toByte() else '?'.code.toByte()
		}
}

/** The JDK's constants, so a caller need not go through forName(). */
object StandardCharsets {
	val UTF_8: Charset = Utf8
	val ISO_8859_1: Charset = Iso88591
	val US_ASCII: Charset = UsAscii
}
