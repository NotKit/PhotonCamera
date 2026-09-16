/* android.graphics: the geometry and colour types, with AOSP's arithmetic.
 * Shapes follow atlas' api-impl under android/graphics. */
package android.graphics

class Point(var x: Int = 0, var y: Int = 0) {
    constructor(src: Point) : this(src.x, src.y)

    fun set(x: Int, y: Int) { this.x = x; this.y = y }
    fun negate() { x = -x; y = -y }
    fun offset(dx: Int, dy: Int) { x += dx; y += dy }
    fun equals(x: Int, y: Int): Boolean = this.x == x && this.y == y

    override fun equals(other: Any?): Boolean = other is Point && other.x == x && other.y == y
    override fun hashCode(): Int = x * 31 + y
    override fun toString(): String = "Point($x, $y)"
}

class PointF(var x: Float = 0f, var y: Float = 0f) {
    constructor(p: Point) : this(p.x.toFloat(), p.y.toFloat())
    constructor(p: PointF) : this(p.x, p.y)

    fun set(x: Float, y: Float) { this.x = x; this.y = y }
    fun set(p: PointF) { x = p.x; y = p.y }
    fun negate() { x = -x; y = -y }
    fun offset(dx: Float, dy: Float) { x += dx; y += dy }
    fun equals(x: Float, y: Float): Boolean = this.x == x && this.y == y
    fun length(): Float = length(x, y)

    override fun equals(other: Any?): Boolean = other is PointF && other.x == x && other.y == y
    override fun hashCode(): Int = x.toRawBits() * 31 + y.toRawBits()
    override fun toString(): String = "PointF($x, $y)"

    companion object {
        fun length(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)
    }
}

class Rect(
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0,
) {
    constructor(r: Rect?) : this(r?.left ?: 0, r?.top ?: 0, r?.right ?: 0, r?.bottom ?: 0)

    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = (left + right) shr 1
    fun centerY(): Int = (top + bottom) shr 1
    fun exactCenterX(): Float = (left + right) * 0.5f
    fun exactCenterY(): Float = (top + bottom) * 0.5f
    fun isEmpty(): Boolean = left >= right || top >= bottom

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun set(src: Rect) = set(src.left, src.top, src.right, src.bottom)
    fun setEmpty() = set(0, 0, 0, 0)
    fun offset(dx: Int, dy: Int) { left += dx; top += dy; right += dx; bottom += dy }
    fun offsetTo(newLeft: Int, newTop: Int) {
        right += newLeft - left; bottom += newTop - top; left = newLeft; top = newTop
    }
    fun inset(dx: Int, dy: Int) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun contains(x: Int, y: Int): Boolean =
        left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun contains(r: Rect): Boolean =
        left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom

    fun intersect(r: Rect): Boolean {
        if (left < r.right && r.left < right && top < r.bottom && r.top < bottom) {
            if (left < r.left) left = r.left
            if (top < r.top) top = r.top
            if (right > r.right) right = r.right
            if (bottom > r.bottom) bottom = r.bottom
            return true
        }
        return false
    }

    fun union(r: Rect) {
        if (r.isEmpty()) return
        if (isEmpty()) { set(r); return }
        if (left > r.left) left = r.left
        if (top > r.top) top = r.top
        if (right < r.right) right = r.right
        if (bottom < r.bottom) bottom = r.bottom
    }

    fun sort() {
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
    }

    /** AOSP's "l,t,r,b" flattened form, and its inverse. */
    fun flattenToString(): String = "$left $top $right $bottom"

    override fun equals(other: Any?): Boolean = other is Rect &&
        other.left == left && other.top == top && other.right == right && other.bottom == bottom
    override fun hashCode(): Int = ((left * 31 + top) * 31 + right) * 31 + bottom
    override fun toString(): String = "Rect($left, $top - $right, $bottom)"

    companion object {
        fun unflattenFromString(s: String?): Rect? {
            val p = s?.trim()?.split(" ") ?: return null
            if (p.size != 4) return null
            return Rect(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt())
        }
    }
}

class RectF(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
) {
    constructor(r: Rect) : this(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    constructor(r: RectF?) : this(r?.left ?: 0f, r?.top ?: 0f, r?.right ?: 0f, r?.bottom ?: 0f)

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
    fun isEmpty(): Boolean = left >= right || top >= bottom

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun set(src: RectF) = set(src.left, src.top, src.right, src.bottom)
    fun set(src: Rect) = set(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat())
    fun setEmpty() = set(0f, 0f, 0f, 0f)
    fun offset(dx: Float, dy: Float) { left += dx; top += dy; right += dx; bottom += dy }
    fun inset(dx: Float, dy: Float) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun contains(x: Float, y: Float): Boolean =
        left < right && top < bottom && x >= left && x < right && y >= top && y < bottom

    fun intersect(r: RectF): Boolean {
        if (left < r.right && r.left < right && top < r.bottom && r.top < bottom) {
            if (left < r.left) left = r.left
            if (top < r.top) top = r.top
            if (right > r.right) right = r.right
            if (bottom > r.bottom) bottom = r.bottom
            return true
        }
        return false
    }

    fun round(dst: Rect) = dst.set(
        kotlin.math.round(left).toInt(), kotlin.math.round(top).toInt(),
        kotlin.math.round(right).toInt(), kotlin.math.round(bottom).toInt(),
    )

    fun sort() {
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
    }

    override fun equals(other: Any?): Boolean = other is RectF &&
        other.left == left && other.top == top && other.right == right && other.bottom == bottom
    override fun hashCode(): Int = ((left.toRawBits() * 31 + top.toRawBits()) * 31 +
        right.toRawBits()) * 31 + bottom.toRawBits()
    override fun toString(): String = "RectF($left, $top - $right, $bottom)"
}

/** ARGB_8888 packing, AOSP's constants and arithmetic. */
object Color {
    const val BLACK: Int = -0x1000000
    const val DKGRAY: Int = -0xbbbbbc
    const val GRAY: Int = -0x777778
    const val LTGRAY: Int = -0x333334
    const val WHITE: Int = -0x1
    const val RED: Int = -0x10000
    const val GREEN: Int = -0xff0100
    const val BLUE: Int = -0xffff01
    const val YELLOW: Int = -0x100
    const val CYAN: Int = -0xff0001
    const val MAGENTA: Int = -0xff01
    const val TRANSPARENT: Int = 0

    fun alpha(color: Int): Int = color ushr 24
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun rgb(r: Float, g: Float, b: Float): Int =
        rgb((r * 255f + 0.5f).toInt(), (g * 255f + 0.5f).toInt(), (b * 255f + 0.5f).toInt())

    fun argb(a: Float, r: Float, g: Float, b: Float): Int = argb(
        (a * 255f + 0.5f).toInt(), (r * 255f + 0.5f).toInt(),
        (g * 255f + 0.5f).toInt(), (b * 255f + 0.5f).toInt(),
    )

    /** "#RRGGBB" or "#AARRGGBB"; named colours are not supported. */
    fun parseColor(s: String): Int {
        require(s.startsWith("#")) { "Unknown color: $s" }
        val v = s.substring(1).toLong(16)
        return if (s.length == 7) (v or 0x00000000FF000000L).toInt() else v.toInt()
    }

    fun colorToHSV(color: Int, hsv: FloatArray) = RGBToHSV(red(color), green(color), blue(color), hsv)

    fun RGBToHSV(r: Int, g: Int, b: Int, hsv: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val d = max - min
        hsv[0] = when {
            d == 0f -> 0f
            max == rf -> (60f * ((gf - bf) / d) + 360f) % 360f
            max == gf -> 60f * ((bf - rf) / d) + 120f
            else -> 60f * ((rf - gf) / d) + 240f
        }
        hsv[1] = if (max == 0f) 0f else d / max
        hsv[2] = max
    }
}

/** android.graphics.ImageFormat: the format codes, which are an ABI. */
object ImageFormat {
    const val UNKNOWN: Int = 0
    const val RGB_565: Int = 4
    const val YV12: Int = 0x32315659
    const val Y8: Int = 0x20203859
    const val Y16: Int = 0x20363159
    const val NV16: Int = 0x10
    const val NV21: Int = 0x11
    const val YUY2: Int = 0x14
    const val JPEG: Int = 0x100
    const val DEPTH_JPEG: Int = 0x69656963
    const val YUV_420_888: Int = 0x23
    const val YUV_422_888: Int = 0x27
    const val YUV_444_888: Int = 0x28
    const val FLEX_RGB_888: Int = 0x29
    const val FLEX_RGBA_8888: Int = 0x2A
    const val RAW_SENSOR: Int = 0x20
    const val RAW_PRIVATE: Int = 0x24
    const val RAW10: Int = 0x25
    const val RAW12: Int = 0x26
    const val DEPTH16: Int = 0x44363159
    const val DEPTH_POINT_CLOUD: Int = 0x101
    const val PRIVATE: Int = 0x22
    const val HEIC: Int = 0x48454946

    fun getBitsPerPixel(format: Int): Int = when (format) {
        RGB_565, NV16, YUY2, Y16, DEPTH16 -> 16
        YV12, NV21, YUV_420_888 -> 12
        Y8 -> 8
        RAW_SENSOR -> 16
        RAW10 -> 10
        RAW12 -> 12
        else -> -1
    }
}

object PixelFormat {
    const val UNKNOWN: Int = 0
    const val TRANSLUCENT: Int = -3
    const val TRANSPARENT: Int = -2
    const val OPAQUE: Int = -1
    const val RGBA_8888: Int = 1
    const val RGBX_8888: Int = 2
    const val RGB_888: Int = 3
    const val RGB_565: Int = 4
}
