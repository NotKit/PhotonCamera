/* android.widget.Toast: there is no window server toast on this port, so the
 * text goes to the log at INFO.  That is the whole behaviour, and it is real. */
package android.widget

class Toast private constructor(private val text: CharSequence) {
    fun show() { android.util.Log.i("Toast", text.toString()) }
    fun cancel() {}
    fun setDuration(d: Int) {}
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int) {}

    companion object {
        const val LENGTH_SHORT: Int = 0
        const val LENGTH_LONG: Int = 1

        fun makeText(context: android.content.Context?, text: CharSequence?, duration: Int): Toast =
            Toast(text ?: "")

        fun makeText(context: android.content.Context?, resId: Int, duration: Int): Toast =
            Toast(context?.getString(resId) ?: "#$resId")
    }
}

interface Checkable {
    fun setChecked(checked: Boolean)
    fun isChecked(): Boolean
    fun toggle()
}
