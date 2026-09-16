/* androidx.core.*: the Compat shims, which on a port with one SDK level are
 * just the plain call. */
package androidx.core.content

object ContextCompat {
    fun getDrawable(context: android.content.Context, id: Int): android.graphics.drawable.Drawable? =
        context.getResources().getDrawable(id)

    fun getColor(context: android.content.Context, id: Int): Int = context.getResources().getColor(id)
    fun getString(context: android.content.Context, id: Int): String = context.getString(id)
    fun getSystemService(context: android.content.Context, name: String): Any? =
        context.getSystemService(name)
    fun checkSelfPermission(context: android.content.Context, permission: String): Int = 0
    fun getExternalFilesDirs(context: android.content.Context, type: String?): Array<java.io.File?> =
        arrayOf(context.getExternalFilesDir(type))
}
