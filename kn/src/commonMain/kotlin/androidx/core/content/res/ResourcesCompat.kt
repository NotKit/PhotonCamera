package androidx.core.content.res

object ResourcesCompat {
    fun getDrawable(
        res: android.content.res.Resources,
        id: Int,
        theme: android.content.res.Resources.Theme?,
    ): android.graphics.drawable.Drawable? = res.getDrawable(id)

    fun getColor(res: android.content.res.Resources, id: Int, theme: Any?): Int = res.getColor(id)
}
