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

	/** Told when the surface is configured again, which on this port happens
	 *  AFTER the app has already laid itself out: the window is created at the
	 *  size Main asks for and the shell then resizes it to the panel. */
	var onChange: (() -> Unit)? = null

	fun set(widthPx: Int, heightPx: Int, density: Float) {
		val changed = widthPx != this.widthPx || heightPx != this.heightPx ||
			density != this.density
		this.widthPx = widthPx
		this.heightPx = heightPx
		this.density = density
		if (changed) onChange?.invoke()
	}

	val widthDp: Float get() = if (density > 0f) widthPx / density else widthPx.toFloat()
	val heightDp: Float get() = if (density > 0f) heightPx / density else heightPx.toFloat()
}
