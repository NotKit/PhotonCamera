/* android.graphics Canvas/Paint: a software rasteriser over a Bitmap's ARGB
 * pixels.  It is what the debug visualisers in util/Utilities draw with --
 * axis-aligned rectangles, lines, points and circles, filled or stroked, with
 * source-over or additive blending.
 *
 * Path is a polyline, filled by scanline or stroked segment by segment.
 * WHAT IS ABSENT: text (there is no font rasteriser here), curves, gradients,
 * shaders, clipping and transforms.  drawText is therefore not declared, and a
 * call site that wants it is a compile error. */
package android.graphics

class Paint() {
    constructor(flags: Int) : this()
    constructor(src: Paint) : this() {
        color = src.color; strokeWidth = src.strokeWidth
        style = src.style; isAntiAlias = src.isAntiAlias; xfermode = src.xfermode
    }

    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    var color: Int = Color.BLACK
    var strokeWidth: Float = 0f
    var style: Style = Style.FILL
    var isAntiAlias: Boolean = false
    var alpha: Int
        get() = Color.alpha(color)
        set(v) { color = (color and 0x00FFFFFF) or ((v and 0xFF) shl 24) }
    var xfermode: Xfermode? = null

    fun setColor(c: Int) { color = c }
    fun getColor(): Int = color
    fun setARGB(a: Int, r: Int, g: Int, b: Int) { color = Color.argb(a, r, g, b) }
    fun setStyle(s: Style) { style = s }
    fun getStyle(): Style = style
    fun setStrokeWidth(w: Float) { strokeWidth = w }
    fun getStrokeWidth(): Float = strokeWidth
    fun setAntiAlias(on: Boolean) { isAntiAlias = on }
    fun setAlpha(a: Int) { alpha = a }
    fun getAlpha(): Int = alpha
    fun setXfermode(m: Xfermode?): Xfermode? { xfermode = m; return m }
    fun setDither(on: Boolean) {}
    fun setFilterBitmap(on: Boolean) {}

    companion object {
        const val ANTI_ALIAS_FLAG: Int = 1
        const val FILTER_BITMAP_FLAG: Int = 2
        const val DITHER_FLAG: Int = 4
    }
}

open class Xfermode

object PorterDuff {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, ADD, MULTIPLY, SCREEN }
}

class PorterDuffXfermode(val mode: PorterDuff.Mode) : Xfermode()

/** Draws into the bitmap handed to the constructor. */
class Canvas(private val target: Bitmap) {
    fun getWidth(): Int = target.getWidth()
    fun getHeight(): Int = target.getHeight()

    private fun blend(x: Int, y: Int, paint: Paint) {
        if (x < 0 || y < 0 || x >= target.getWidth() || y >= target.getHeight()) return
        val src = paint.color
        val a = Color.alpha(src)
        if (a == 0) return
        val additive = (paint.xfermode as? PorterDuffXfermode)?.mode == PorterDuff.Mode.ADD
        val dst = target.getPixel(x, y)
        val out = when {
            additive -> Color.argb(
                minOf(255, Color.alpha(dst) + a),
                minOf(255, Color.red(dst) + Color.red(src)),
                minOf(255, Color.green(dst) + Color.green(src)),
                minOf(255, Color.blue(dst) + Color.blue(src)),
            )
            a == 255 -> src
            else -> {
                val ia = 255 - a
                Color.argb(
                    minOf(255, a + Color.alpha(dst) * ia / 255),
                    (Color.red(src) * a + Color.red(dst) * ia) / 255,
                    (Color.green(src) * a + Color.green(dst) * ia) / 255,
                    (Color.blue(src) * a + Color.blue(dst) * ia) / 255,
                )
            }
        }
        target.setPixel(x, y, out)
    }

    // Java widens an int argument to float at the call site; Kotlin does not,
    // so these Number overloads do the widening the Java already had.  An
    // all-Float call still picks the Float overload, which is more specific.
    fun drawRect(l: Number, t: Number, r: Number, b: Number, paint: Paint) =
        drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
    fun drawLine(x0: Number, y0: Number, x1: Number, y1: Number, paint: Paint) =
        drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
    fun drawCircle(cx: Number, cy: Number, radius: Number, paint: Paint) =
        drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), paint)
    fun drawPoint(x: Number, y: Number, paint: Paint) =
        drawPoint(x.toFloat(), y.toFloat(), paint)

    fun drawColor(color: Int) = target.eraseColor(color)
    fun drawARGB(a: Int, r: Int, g: Int, b: Int) = target.eraseColor(Color.argb(a, r, g, b))

    fun drawPoint(x: Float, y: Float, paint: Paint) {
        val r = maxOf(1, (paint.strokeWidth / 2f).toInt())
        for (dy in -r..r) for (dx in -r..r) blend(x.toInt() + dx, y.toInt() + dy, paint)
    }

    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        // Bresenham, thickened to strokeWidth by a square brush.
        var x0 = startX.toInt(); var y0 = startY.toInt()
        val x1 = stopX.toInt(); val y1 = stopY.toInt()
        val dx = kotlin.math.abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        val half = maxOf(0, (paint.strokeWidth / 2f).toInt())
        while (true) {
            for (ay in -half..half) for (ax in -half..half) blend(x0 + ax, y0 + ay, paint)
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val l = minOf(left, right).toInt(); val r = maxOf(left, right).toInt()
        val t = minOf(top, bottom).toInt(); val b = maxOf(top, bottom).toInt()
        if (paint.style == Paint.Style.STROKE) {
            drawLine(l.toFloat(), t.toFloat(), r.toFloat(), t.toFloat(), paint)
            drawLine(r.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
            drawLine(r.toFloat(), b.toFloat(), l.toFloat(), b.toFloat(), paint)
            drawLine(l.toFloat(), b.toFloat(), l.toFloat(), t.toFloat(), paint)
            return
        }
        for (y in t until b) for (x in l until r) blend(x, y, paint)
    }

    fun drawRect(rect: Rect, paint: Paint) =
        drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)

    fun drawRect(rect: RectF, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        val r = radius.toInt()
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val stroke = paint.style == Paint.Style.STROKE
        val half = maxOf(1, (paint.strokeWidth / 2f).toInt())
        for (dy in -r..r) for (dx in -r..r) {
            val d = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            val inside = if (stroke) kotlin.math.abs(d - r) <= half else d <= r
            if (inside) blend(x0 + dx, y0 + dy, paint)
        }
    }

    /** A filled path is scanline-filled by the even-odd rule; a stroked one is
     *  drawn as its segments. */
    fun drawPath(path: Path, paint: Paint) {
        val p = path.points
        if (p.size < 4) return
        if (paint.style == Paint.Style.STROKE) {
            var i = 0
            while (i + 3 < p.size) {
                drawLine(p[i], p[i + 1], p[i + 2], p[i + 3], paint)
                i += 2
            }
            return
        }
        var minY = p[1]; var maxY = p[1]
        var i = 1
        while (i < p.size) { minY = minOf(minY, p[i]); maxY = maxOf(maxY, p[i]); i += 2 }
        val n = p.size / 2
        for (y in minY.toInt()..maxY.toInt()) {
            val xs = ArrayList<Float>()
            for (k in 0 until n) {
                val x0 = p[k * 2]; val y0 = p[k * 2 + 1]
                val j = (k + 1) % n
                val x1 = p[j * 2]; val y1 = p[j * 2 + 1]
                if (y0 == y1) continue
                val yf = y.toFloat()
                if ((yf >= minOf(y0, y1)) && (yf < maxOf(y0, y1))) {
                    xs.add(x0 + (yf - y0) / (y1 - y0) * (x1 - x0))
                }
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                for (x in xs[k].toInt()..xs[k + 1].toInt()) blend(x, y, paint)
                k += 2
            }
        }
    }

    /** The affine blit: each destination pixel is sampled through the inverse
     *  of the matrix (nearest neighbour), which is what a scale-and-rotate
     *  thumbnail needs. */
    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return
        val pt = FloatArray(2)
        for (y in 0 until target.getHeight()) for (x in 0 until target.getWidth()) {
            pt[0] = x.toFloat(); pt[1] = y.toFloat()
            inverse.mapPoints(pt)
            val sx = pt[0].toInt(); val sy = pt[1].toInt()
            if (sx in 0 until bitmap.getWidth() && sy in 0 until bitmap.getHeight()) {
                target.setPixel(x, y, bitmap.getPixel(sx, sy))
            }
        }
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        val sw = src?.width() ?: bitmap.getWidth()
        val sh = src?.height() ?: bitmap.getHeight()
        val sx0 = src?.left ?: 0
        val sy0 = src?.top ?: 0
        for (y in dst.top until dst.bottom) for (x in dst.left until dst.right) {
            val u = sx0 + (x - dst.left) * sw / maxOf(1, dst.width())
            val v = sy0 + (y - dst.top) * sh / maxOf(1, dst.height())
            if (u in 0 until bitmap.getWidth() && v in 0 until bitmap.getHeight()) {
                target.setPixel(x, y, bitmap.getPixel(u, v))
            }
        }
    }

    /** Only the untransformed blit: src is drawn with its top-left at (x, y). */
    fun drawBitmap(bitmap: Bitmap, x: Float, y: Float, paint: Paint?) {
        for (sy in 0 until bitmap.getHeight()) for (sx in 0 until bitmap.getWidth()) {
            val dx = x.toInt() + sx
            val dy = y.toInt() + sy
            if (dx in 0 until target.getWidth() && dy in 0 until target.getHeight()) {
                target.setPixel(dx, dy, bitmap.getPixel(sx, sy))
            }
        }
    }
}

/**
 * android.graphics.Path, as far as the debug graphs draw one: a polyline built
 * with moveTo/lineTo.  Curves (quadTo, cubicTo, arcTo) are ABSENT.
 */
class Path {
    internal val points = ArrayList<Float>()

    fun reset() = points.clear()
    fun moveTo(x: Float, y: Float) { points.clear(); points.add(x); points.add(y) }
    fun lineTo(x: Float, y: Float) { points.add(x); points.add(y) }
    fun moveTo(x: Number, y: Number) = moveTo(x.toFloat(), y.toFloat())
    fun lineTo(x: Number, y: Number) = lineTo(x.toFloat(), y.toFloat())
    fun close() {
        if (points.size >= 4) { points.add(points[0]); points.add(points[1]) }
    }
    fun isEmpty(): Boolean = points.isEmpty()
}
