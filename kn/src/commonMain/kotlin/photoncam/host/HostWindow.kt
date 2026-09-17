/* What the window knows and the app asks for.
 *
 * On Android the panel is a DisplayMetrics off Resources; here the shim's
 * Resources cannot answer (android/util/AndroidUtil.kt) and the Wayland surface
 * is the only thing that does.  MgwlComposeHost fills this in before it sets
 * the content, and CaptureController reads it through PreviewSurface's
 * getDisplaySize().
 */
package photoncam.host

object HostWindow {
	/** the surface in PIXELS, not dp; (0, 0) until the window is configured */
	var widthPx: Int = 0
		private set
	var heightPx: Int = 0
		private set
	var density: Float = 1f
		private set

	fun set(widthPx: Int, heightPx: Int, density: Float) {
		this.widthPx = widthPx
		this.heightPx = heightPx
		this.density = density
	}

	val widthDp: Float get() = if (density > 0f) widthPx / density else widthPx.toFloat()
	val heightDp: Float get() = if (density > 0f) heightPx / density else heightPx.toFloat()
}
