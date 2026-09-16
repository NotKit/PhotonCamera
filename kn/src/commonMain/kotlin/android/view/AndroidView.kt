/* android.view's display half.  The Compose scene is the host lane's, so this
 * is only what the gallery's HDR check and the orientation code read: one
 * display, SDR, rotation 0.  android.view.Surface is the camera lane's. */
package android.view

object WindowManager {
    fun getDefaultDisplay(): Display = Display
}

object Display {
    const val DEFAULT_DISPLAY: Int = 0

    /** The port composites in SDR; when the host gains an HDR path, so does this. */
    fun isHdr(): Boolean = false
    fun getRotation(): Int = 0
    fun getDisplayId(): Int = DEFAULT_DISPLAY
    fun getRefreshRate(): Float = 60f
}

/**
 * android.view.View exists here for ONE reason: the camera screen's button
 * models carry a View.OnClickListener.  It has no drawing, no layout and no
 * hierarchy -- Compose owns all of that -- so only the listener interfaces are
 * declared, and a call site that wants a real View is a compile error.
 */
class View {
    fun interface OnClickListener { fun onClick(v: View?) }
    fun interface OnLongClickListener { fun onLongClick(v: View?): Boolean }
    fun interface OnTouchListener { fun onTouch(v: View?, event: Any?): Boolean }

    companion object {
        const val VISIBLE: Int = 0
        const val INVISIBLE: Int = 4
        const val GONE: Int = 8
    }
}

/** A window's colour mode is the only thing the app sets on one. */
class Window {
    var colorMode: Int = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
    fun setColorMode(mode: Int) { colorMode = mode }
    fun getColorMode(): Int = colorMode
}
