/* android.graphics.drawable: a Drawable is something that draws itself into a
 * Canvas.  BitmapDrawable is real -- it wraps a Bitmap this process made.
 *
 * Resources.getDrawable() decodes `assets/drawable/<name>.png` through the
 * natives lane's ImageCodec, so the pipeline's PNG LUTs (R.drawable.lut2,
 * neutral_lut, shadowtex) load.  An XML vector drawable has no renderer here
 * and answers null, as Android does for an id it has not got. */
package android.graphics.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

abstract class Drawable {
    var bounds: Rect = Rect()

    abstract fun draw(canvas: Canvas)
    open fun getIntrinsicWidth(): Int = -1
    open fun getIntrinsicHeight(): Int = -1
    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) { bounds.set(left, top, right, bottom) }
    open fun setAlpha(alpha: Int) {}
    open fun getBounds(): Rect = bounds
}

class BitmapDrawable(private val bitmap: Bitmap) : Drawable() {
    fun getBitmap(): Bitmap = bitmap
    override fun getIntrinsicWidth(): Int = bitmap.getWidth()
    override fun getIntrinsicHeight(): Int = bitmap.getHeight()
    override fun draw(canvas: Canvas) =
        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
}

class ColorDrawable(private val color: Int) : Drawable() {
    fun getColor(): Int = color
    override fun draw(canvas: Canvas) = canvas.drawColor(color)
}
