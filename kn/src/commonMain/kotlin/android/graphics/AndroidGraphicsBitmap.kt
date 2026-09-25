/* android.graphics.Bitmap: an IntArray of ARGB_8888 pixels plus width, height
 * and config, as fenix-kn's AndroidGraphics.kt models it.  Config's
 * bytes-per-pixel are atlas' (api-impl/android/graphics/Bitmap.java).
 *
 * compress() writes a real PNG (AndroidGraphicsPng.kt) and answers false for
 * every other format.  DECODING is absent: BitmapFactory is not declared, so a
 * call site that wants to read an image is a compile error until the natives
 * lane exposes a decoder. */
package android.graphics

class Bitmap private constructor(
    private val widthValue: Int,
    private val heightValue: Int,
    private val configValue: Config,
) {
    private var pixels: IntArray? = IntArray(widthValue * heightValue)

    enum class Config(val bytesPerPixel: Int) {
        ALPHA_8(1), RGB_565(2), ARGB_4444(2), ARGB_8888(4), RGBA_F16(8), HARDWARE(4),
    }

    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    val width: Int get() = widthValue
    val height: Int get() = heightValue
    fun getWidth(): Int = widthValue
    fun getHeight(): Int = heightValue

    val config: Config get() = configValue
    fun getConfig(): Config = configValue

    val byteCount: Int get() = rowBytes * heightValue
    val rowBytes: Int get() = widthValue * configValue.bytesPerPixel
    fun getByteCount(): Int = byteCount
    fun getAllocationByteCount(): Int = byteCount
    fun getRowBytes(): Int = rowBytes

    var isRecycled: Boolean = false
        private set

    fun recycle() { pixels = null; isRecycled = true }

    /** Every bitmap here is a writable IntArray; none is backed by a decoder's
     *  read-only buffer or by hardware. */
    fun isMutable(): Boolean = true

    private fun buf(): IntArray = pixels ?: throw IllegalStateException("bitmap is recycled")

    fun getPixel(x: Int, y: Int): Int {
        require(x in 0 until widthValue && y in 0 until heightValue) { "x/y out of bounds" }
        return buf()[y * widthValue + x]
    }

    fun setPixel(x: Int, y: Int, color: Int) {
        require(x in 0 until widthValue && y in 0 until heightValue) { "x/y out of bounds" }
        buf()[y * widthValue + x] = color
    }

    fun getPixels(dst: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val p = buf()
        for (row in 0 until h) p.copyInto(dst, offset + row * stride, (y + row) * widthValue + x,
            (y + row) * widthValue + x + w)
    }

    fun setPixels(src: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val p = buf()
        for (row in 0 until h) src.copyInto(p, (y + row) * widthValue + x,
            offset + row * stride, offset + row * stride + w)
    }

    fun eraseColor(color: Int) = buf().fill(color)

    /** Android copies the bitmap's own memory, and an ARGB_8888 bitmap holds
     *  R,G,B,A in that byte order -- NOT an int in the buffer's endianness.  A
     *  `putInt` here would hand a big-endian reader R as the alpha, which is
     *  what turned every saved JPEG's blue channel into a constant 255. */
    fun copyPixelsToBuffer(dst: java.nio.Buffer) {
        val p = buf()
        when (dst) {
            is java.nio.IntBuffer -> dst.put(p, 0, p.size)
            is java.nio.ByteBuffer -> {
                val chunk = ByteArray(minOf(p.size, CHUNK_PIXELS) * 4)
                var i = 0
                while (i < p.size) {
                    val n = minOf(CHUNK_PIXELS, p.size - i)
                    for (k in 0 until n) {
                        val v = p[i + k]
                        chunk[k * 4] = (v ushr 16).toByte()
                        chunk[k * 4 + 1] = (v ushr 8).toByte()
                        chunk[k * 4 + 2] = v.toByte()
                        chunk[k * 4 + 3] = (v ushr 24).toByte()
                    }
                    dst.put(chunk, 0, n * 4)
                    i += n
                }
            }
            else -> throw IllegalArgumentException("unsupported buffer: " + dst)
        }
    }

    fun copyPixelsFromBuffer(src: java.nio.Buffer) {
        val p = buf()
        when (src) {
            is java.nio.IntBuffer -> src.get(p, 0, p.size)
            is java.nio.ByteBuffer -> {
                val chunk = ByteArray(minOf(p.size, CHUNK_PIXELS) * 4)
                var i = 0
                while (i < p.size) {
                    val n = minOf(CHUNK_PIXELS, p.size - i)
                    src.get(chunk, 0, n * 4)
                    for (k in 0 until n) {
                        val r = chunk[k * 4].toInt() and 0xFF
                        val g = chunk[k * 4 + 1].toInt() and 0xFF
                        val b = chunk[k * 4 + 2].toInt() and 0xFF
                        val a = chunk[k * 4 + 3].toInt() and 0xFF
                        p[i + k] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    i += n
                }
            }
            else -> throw IllegalArgumentException("unsupported buffer: " + src)
        }
    }

    /** PNG is written here (AndroidGraphicsPng.kt); JPEG goes through
     *  photoncam.natives.ImageCodec, which is libjpeg-turbo where there is one.
     *  WEBP still has no encoder and answers false, which is Android's own
     *  "could not encode" contract. */
    fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean {
        when (format) {
            CompressFormat.PNG -> stream.write(encodePng(this))
            CompressFormat.JPEG -> {
                val jpeg = photoncam.natives.ImageCodec.encodeJpeg(
                    buf(), widthValue, heightValue, quality,
                )
                if (jpeg == null) {
                    android.util.Log.e("Bitmap", "compress(JPEG): the encoder failed")
                    return false
                }
                stream.write(jpeg)
            }
            else -> {
                android.util.Log.e("Bitmap", "compress(" + format + "): no WEBP encoder here")
                return false
            }
        }
        return true
    }

    fun copy(config: Config, mutable: Boolean): Bitmap {
        val out = createBitmap(widthValue, heightValue, config)
        buf().copyInto(out.buf())
        return out
    }

    fun sameAs(other: Bitmap?): Boolean = other != null &&
        other.widthValue == widthValue && other.heightValue == heightValue &&
        other.configValue == configValue && (other.pixels?.contentEquals(pixels) ?: (pixels == null))

    companion object {
        /** Pixels per staging block in the ByteBuffer copies; 12 MP at once is
         *  a 48 MB temporary nobody needs. */
        private const val CHUNK_PIXELS: Int = 8192

        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            require(width > 0) { "width must be > 0" }
            require(height > 0) { "height must be > 0" }
            return Bitmap(width, height, config)
        }

        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
            val out = createBitmap(w, h, src.configValue)
            src.getPixels(out.buf(), 0, w, x, y, w, h)
            return out
        }

        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap {
            val out = createBitmap(width, height, config)
            colors.copyInto(out.buf(), 0, 0, width * height)
            return out
        }
    }
}

/**
 * android.graphics.Matrix: the 3x3 affine matrix, row-major as AOSP stores it
 * (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, MPERSP_0..2).
 */
class Matrix() {
    private val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    constructor(src: Matrix) : this() { src.m.copyInto(m) }

    fun reset() { floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).copyInto(m) }
    fun set(src: Matrix?) { src?.m?.copyInto(m) ?: reset() }
    fun getValues(values: FloatArray) = m.copyInto(values)
    fun setValues(values: FloatArray) = values.copyInto(m, 0, 0, 9)
    fun isIdentity(): Boolean = m.contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /** this = this * M, which is what AOSP's postX() does. */
    private fun post(b: FloatArray) = mul(b, m).copyInto(m)
    private fun pre(b: FloatArray) = mul(m, b).copyInto(m)

    private fun translation(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)
    private fun scaling(sx: Float, sy: Float) = floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)

    private fun rotation(deg: Float): FloatArray {
        val r = deg.toDouble() * kotlin.math.PI / 180.0
        val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    private fun around(b: FloatArray, px: Float, py: Float): FloatArray =
        mul(translation(px, py), mul(b, translation(-px, -py)))

    fun setTranslate(dx: Float, dy: Float) { translation(dx, dy).copyInto(m) }
    fun setScale(sx: Float, sy: Float) { scaling(sx, sy).copyInto(m) }
    fun setScale(sx: Float, sy: Float, px: Float, py: Float) { around(scaling(sx, sy), px, py).copyInto(m) }
    fun setRotate(deg: Float) { rotation(deg).copyInto(m) }
    fun setRotate(deg: Float, px: Float, py: Float) { around(rotation(deg), px, py).copyInto(m) }

    fun postTranslate(dx: Float, dy: Float): Boolean { post(translation(dx, dy)); return true }
    fun postScale(sx: Float, sy: Float): Boolean { post(scaling(sx, sy)); return true }
    fun postScale(sx: Float, sy: Float, px: Float, py: Float): Boolean {
        post(around(scaling(sx, sy), px, py)); return true
    }
    fun postRotate(deg: Float): Boolean { post(rotation(deg)); return true }
    fun postRotate(deg: Float, px: Float, py: Float): Boolean { post(around(rotation(deg), px, py)); return true }

    fun preTranslate(dx: Float, dy: Float): Boolean { pre(translation(dx, dy)); return true }
    fun preScale(sx: Float, sy: Float): Boolean { pre(scaling(sx, sy)); return true }
    fun preRotate(deg: Float): Boolean { pre(rotation(deg)); return true }

    enum class ScaleToFit { FILL, START, CENTER, END }

    /** AOSP's setRectToRect, for the four ScaleToFit modes. */
    fun setRectToRect(src: RectF, dst: RectF, stf: ScaleToFit): Boolean {
        if (src.isEmpty()) { reset(); return false }
        if (dst.isEmpty()) { m.fill(0f); m[8] = 1f; return true }
        var sx = dst.width() / src.width()
        var sy = dst.height() / src.height()
        var tx = dst.left - src.left * sx
        var ty = dst.top - src.top * sy
        if (stf != ScaleToFit.FILL) {
            val s = if (stf == ScaleToFit.START || stf == ScaleToFit.END || stf == ScaleToFit.CENTER)
                kotlin.math.min(sx, sy) else sx
            sx = s; sy = s
            tx = dst.left - src.left * s
            ty = dst.top - src.top * s
            val dx = dst.width() - src.width() * s
            val dy = dst.height() - src.height() * s
            when (stf) {
                ScaleToFit.CENTER -> { tx += dx * 0.5f; ty += dy * 0.5f }
                ScaleToFit.END -> { tx += dx; ty += dy }
                else -> Unit
            }
        }
        floatArrayOf(sx, 0f, tx, 0f, sy, ty, 0f, 0f, 1f).copyInto(m)
        return true
    }

    fun mapPoints(pts: FloatArray) {
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            pts[i] = m[0] * x + m[1] * y + m[2]
            pts[i + 1] = m[3] * x + m[4] * y + m[5]
            i += 2
        }
    }

    fun mapRect(r: RectF): Boolean {
        val pts = floatArrayOf(r.left, r.top, r.right, r.bottom)
        mapPoints(pts)
        r.set(minOf(pts[0], pts[2]), minOf(pts[1], pts[3]), maxOf(pts[0], pts[2]), maxOf(pts[1], pts[3]))
        return true
    }

    fun invert(inverse: Matrix): Boolean {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (det == 0f) return false
        val i = FloatArray(9)
        i[0] = (m[4] * m[8] - m[5] * m[7]) / det
        i[1] = (m[2] * m[7] - m[1] * m[8]) / det
        i[2] = (m[1] * m[5] - m[2] * m[4]) / det
        i[3] = (m[5] * m[6] - m[3] * m[8]) / det
        i[4] = (m[0] * m[8] - m[2] * m[6]) / det
        i[5] = (m[2] * m[3] - m[0] * m[5]) / det
        i[6] = (m[3] * m[7] - m[4] * m[6]) / det
        i[7] = (m[1] * m[6] - m[0] * m[7]) / det
        i[8] = (m[0] * m[4] - m[1] * m[3]) / det
        i.copyInto(inverse.m)
        return true
    }

    override fun toString(): String = m.joinToString(", ", "Matrix[", "]")

    companion object {
        const val MSCALE_X: Int = 0
        const val MSKEW_X: Int = 1
        const val MTRANS_X: Int = 2
        const val MSKEW_Y: Int = 3
        const val MSCALE_Y: Int = 4
        const val MTRANS_Y: Int = 5
        const val MPERSP_0: Int = 6
        const val MPERSP_1: Int = 7
        const val MPERSP_2: Int = 8
    }
}
