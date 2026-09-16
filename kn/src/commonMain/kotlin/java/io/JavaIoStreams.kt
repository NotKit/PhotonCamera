/* java.io's stream half.  Copied from fenix-kn's androidshim/JavaIoStreams.kt
 * (reading: InputStream, ByteArrayInputStream, Reader, InputStreamReader,
 * BufferedReader; decoding is eager -- the reader drains its stream on first
 * read).  The writing half and the file-backed streams are in JavaIoWrite.kt. */
package java.io

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

abstract class InputStream : Closeable {

	/** One byte as 0..255, or -1 at end of stream.  The JDK's contract. */
	abstract fun read(): Int

	open fun read(b: ByteArray): Int = read(b, 0, b.size)

	open fun read(b: ByteArray, off: Int, len: Int): Int {
		if (len == 0) return 0
		var n = 0
		while (n < len) {
			val c = read()
			if (c < 0) break
			b[off + n] = c.toByte()
			n++
		}
		return if (n == 0) -1 else n
	}

	/** The JDK's default: 0, not a guess. */
	open fun available(): Int = 0

	override fun close() = Unit

	/** kotlin.io's readBytes(), declared as a member -- see the header. */
	fun readBytes(): ByteArray {
		val out = ArrayList<Byte>()
		while (true) {
			val c = read()
			if (c < 0) break
			out.add(c.toByte())
		}
		return ByteArray(out.size) { out[it] }
	}

	/** kotlin.io's extension, declared as a member -- see the header. */
	fun reader(charset: Charset = StandardCharsets.UTF_8): Reader =
		InputStreamReader(this, charset)

	/** kotlin.io's extension, declared as a member -- see the header. */
	fun bufferedReader(charset: Charset = StandardCharsets.UTF_8): BufferedReader =
		BufferedReader(InputStreamReader(this, charset))
}

class ByteArrayInputStream(
	private val buf: ByteArray,
	private val offset: Int = 0,
	private val length: Int = buf.size - offset,
) : InputStream() {
	private var pos = offset
	private val end = offset + length

	override fun read(): Int = if (pos >= end) -1 else buf[pos++].toInt() and 0xFF

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		if (pos >= end) return -1
		if (len == 0) return 0
		val n = minOf(len, end - pos)
		buf.copyInto(b, off, pos, pos + n)
		pos += n
		return n
	}

	override fun available(): Int = end - pos
}

abstract class Reader : Closeable {

	/** One char as 0..65535, or -1 at end.  The JDK's contract. */
	abstract fun read(): Int

	open fun read(cbuf: CharArray, off: Int, len: Int): Int {
		if (len == 0) return 0
		var n = 0
		while (n < len) {
			val c = read()
			if (c < 0) break
			cbuf[off + n] = c.toChar()
			n++
		}
		return if (n == 0) -1 else n
	}

	override fun close() = Unit

	/** kotlin.io's extension, declared as a member -- see the header. */
	open fun readText(): String {
		val sb = StringBuilder()
		while (true) {
			val c = read()
			if (c < 0) break
			sb.append(c.toChar())
		}
		return sb.toString()
	}
}

/**
 * EAGER: the stream is drained and decoded on the first read.  See the header.
 */
class InputStreamReader(
	private val stream: InputStream,
	private val charset: Charset = StandardCharsets.UTF_8,
) : Reader() {
	private var decoded: String? = null
	private var pos = 0

	private fun text(): String {
		var t = decoded
		if (t == null) {
			t = charset.decode(stream.readBytes())
			decoded = t
		}
		return t
	}

	override fun read(): Int {
		val t = text()
		return if (pos >= t.length) -1 else t[pos++].code
	}

	override fun readText(): String {
		val t = text()
		val out = if (pos == 0) t else t.substring(pos)
		pos = t.length
		return out
	}

	override fun close() = stream.close()
}

class BufferedReader(
	private val inner: Reader,
	@Suppress("UNUSED_PARAMETER") size: Int = DEFAULT_BUFFER_SIZE,
) : Reader() {

	private var pushback: Int = -2

	private fun next(): Int {
		if (pushback != -2) {
			val c = pushback
			pushback = -2
			return c
		}
		return inner.read()
	}

	override fun read(): Int = next()

	/**
	 * The JDK's: a line is terminated by \n, \r or \r\n, the terminator is not
	 * part of the result, and the end of input is null (never "").
	 */
	fun readLine(): String? {
		val first = next()
		if (first < 0) return null
		val sb = StringBuilder()
		var c = first
		while (c >= 0) {
			val ch = c.toChar()
			if (ch == '\n') break
			if (ch == '\r') {
				val peek = next()
				if (peek >= 0 && peek.toChar() != '\n') pushback = peek
				break
			}
			sb.append(ch)
			c = next()
		}
		return sb.toString()
	}

	fun lines(): List<String> = readLines()

	fun readLines(): List<String> {
		val out = ArrayList<String>()
		while (true) out.add(readLine() ?: return out)
	}

	fun lineSequence(): Sequence<String> = generateSequence { readLine() }

	fun forEachLine(action: (String) -> Unit) {
		while (true) action(readLine() ?: return)
	}

	override fun readText(): String {
		val sb = StringBuilder()
		if (pushback != -2) {
			sb.append(pushback.toChar())
			pushback = -2
		}
		sb.append(inner.readText())
		return sb.toString()
	}

	override fun close() = inner.close()

	private companion object {
		const val DEFAULT_BUFFER_SIZE = 8192
	}
}
