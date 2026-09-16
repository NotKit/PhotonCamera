/* Bitmap.compress, and why only PNG.
 *
 * PNG is written here in full: a real zlib stream whose deflate blocks are
 * "stored" (uncompressed), which every decoder accepts, with the correct
 * Adler-32 and CRC-32.  The files are bigger than libpng's and byte-exact
 * valid.  That is what the debug dumps in util/Utilities need.
 *
 * JPEG and WEBP need a DCT and an entropy coder, which are a native decoder's
 * job, not a shim's: compress() answers false for them, which is Android's own
 * "could not encode" contract, and says so at ERROR in the log.
 *
 * DECODING IS ABSENT.  BitmapFactory is not declared at all: a call site that
 * wants to read a PNG or JPEG is a compile error until the natives lane
 * exposes a decoder. */
package android.graphics

private fun crc32(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
    var c = 0xFFFFFFFFu
    for (i in from until to) {
        c = c xor (data[i].toUInt() and 0xFFu)
        repeat(8) { c = if ((c and 1u) != 0u) (c shr 1) xor 0xEDB88320u else c shr 1 }
    }
    return (c xor 0xFFFFFFFFu).toInt()
}

private fun adler32(data: ByteArray): Int {
    var a = 1u
    var b = 0u
    for (byte in data) {
        a = (a + (byte.toUInt() and 0xFFu)) % 65521u
        b = (b + a) % 65521u
    }
    return ((b shl 16) or a).toInt()
}

private fun be32(v: Int): ByteArray = byteArrayOf(
    (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
)

private fun chunk(type: String, body: ByteArray): ByteArray {
    val typeBytes = type.encodeToByteArray()
    val payload = typeBytes + body
    return be32(body.size) + payload + be32(crc32(payload))
}

/** zlib with stored deflate blocks: no compression, always decodable. */
private fun zlibStored(raw: ByteArray): ByteArray {
    val out = ArrayList<Byte>(raw.size + raw.size / 65535 * 5 + 16)
    out.add(0x78); out.add(0x01) // CMF/FLG for a 32K window, fastest level
    var off = 0
    while (off < raw.size || raw.isEmpty()) {
        val n = minOf(65535, raw.size - off)
        val last = if (off + n >= raw.size) 1 else 0
        out.add(last.toByte())
        out.add((n and 0xFF).toByte()); out.add((n ushr 8).toByte())
        val inv = n.inv()
        out.add((inv and 0xFF).toByte()); out.add(((inv ushr 8) and 0xFF).toByte())
        for (i in off until off + n) out.add(raw[i])
        off += n
        if (last == 1) break
    }
    for (b in be32(adler32(raw))) out.add(b)
    return out.toByteArray()
}

internal fun encodePng(bitmap: Bitmap): ByteArray {
    val w = bitmap.getWidth()
    val h = bitmap.getHeight()
    // RGBA8 scanlines, each prefixed with filter type 0 (none).
    val raw = ByteArray(h * (1 + w * 4))
    var p = 0
    for (y in 0 until h) {
        raw[p++] = 0
        for (x in 0 until w) {
            val c = bitmap.getPixel(x, y)
            raw[p++] = ((c shr 16) and 0xFF).toByte()
            raw[p++] = ((c shr 8) and 0xFF).toByte()
            raw[p++] = (c and 0xFF).toByte()
            raw[p++] = ((c ushr 24) and 0xFF).toByte()
        }
    }
    val ihdr = be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0) // 8-bit RGBA
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    return signature + chunk("IHDR", ihdr) + chunk("IDAT", zlibStored(raw)) + chunk("IEND", ByteArray(0))
}

