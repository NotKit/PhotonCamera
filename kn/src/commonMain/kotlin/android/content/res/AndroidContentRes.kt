/* android.content.res: AssetManager over a directory, Resources over the
 * tables kn/gen-r.py generated from app/src/main/res.
 *
 * getDrawable decodes a bitmap drawable through the natives lane's ImageCodec;
 * getXml and obtainTypedArray are ABSENT (there is no compiled XML here, and a
 * vector drawable has no renderer), so the Compose UI draws its own. */
package android.content.res

import com.particlesdevs.photoncamera.R_ARRAYS
import com.particlesdevs.photoncamera.R_BOOLS
import com.particlesdevs.photoncamera.R_INTEGERS
import com.particlesdevs.photoncamera.R_NAMES
import com.particlesdevs.photoncamera.R_STRINGS
import java.io.File

/** A directory of files, addressed by relative path as Android's assets are. */
class AssetManager(private val root: File) {
    fun open(fileName: String): java.io.InputStream {
        val f = File(root, fileName)
        if (!f.exists()) throw java.io.FileNotFoundException(f.getPath())
        return java.io.FileInputStream(f)
    }

    fun open(fileName: String, accessMode: Int): java.io.InputStream = open(fileName)

    fun list(path: String): Array<String>? =
        (if (path.isEmpty()) root else File(root, path)).list()

    fun getRoot(): File = root

    companion object {
        const val ACCESS_UNKNOWN: Int = 0
        const val ACCESS_RANDOM: Int = 1
        const val ACCESS_STREAMING: Int = 2
        const val ACCESS_BUFFER: Int = 3
    }

    fun close() {}
}

class Resources(private val assets: AssetManager) {
    fun getAssets(): AssetManager = assets

    fun getString(id: Int): String = R_STRINGS[id] ?: throw NotFoundException("String #$id")
    fun getString(id: Int, vararg args: Any?): String = java.lang.formatJava(getString(id), args)
    fun getText(id: Int): CharSequence = getString(id)
    fun getBoolean(id: Int): Boolean = R_BOOLS[id] ?: throw NotFoundException("Bool #$id")
    fun getInteger(id: Int): Int = R_INTEGERS[id] ?: throw NotFoundException("Integer #$id")
    fun getStringArray(id: Int): Array<String> = R_ARRAYS[id] ?: throw NotFoundException("Array #$id")

    fun getIntArray(id: Int): IntArray =
        getStringArray(id).map { it.trim().toIntOrNull() ?: 0 }.toIntArray()

    fun getResourceEntryName(id: Int): String = R_NAMES[id] ?: throw NotFoundException("#$id")
    fun getResourceName(id: Int): String = "com.particlesdevs.photoncamera:${getResourceEntryName(id)}"

    /** A colour resource holds "#AARRGGBB" text; parse it the way aapt would. */
    fun getColor(id: Int): Int = android.graphics.Color.parseColor(
        R_STRINGS[id] ?: throw NotFoundException("Color #$id"),
    )

    /** Raw resources are staged as assets/raw/<name>. */
    fun openRawResource(id: Int): java.io.InputStream {
        val name = getResourceEntryName(id)
        for (ext in RAW_EXTENSIONS) {
            val f = File(assets.getRoot(), "raw/$name$ext")
            if (f.exists()) return java.io.FileInputStream(f)
        }
        throw NotFoundException("Raw resource #$id ($name)")
    }

    /**
     * A drawable resource, decoded from `assets/drawable/<name>.<ext>` by the
     * natives lane's ImageCodec.  aapt2 packs these into the APK on Android;
     * here the host stages them beside the binary.  Null when the file is not
     * there, which is what Android answers for an id it has not got.
     */
    fun getDrawable(id: Int): android.graphics.drawable.Drawable? {
        val name = R_NAMES[id] ?: return null
        for (ext in DRAWABLE_EXTENSIONS) {
            val f = File(assets.getRoot(), "drawable/$name$ext")
            if (!f.exists()) continue
            val decoded = photoncam.natives.ImageCodec.decodeFile(f.getPath()) ?: continue
            val bmp = android.graphics.Bitmap.createBitmap(
                decoded.pixels, decoded.width, decoded.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            return android.graphics.drawable.BitmapDrawable(bmp)
        }
        return null
    }

    fun newTheme(): Theme = Theme()

    /** A theme is a set of applied style ids; nothing here resolves attributes. */
    class Theme {
        private val applied = ArrayList<Int>()
        fun applyStyle(resId: Int, force: Boolean) { applied.add(resId) }
        fun appliedStyles(): List<Int> = applied
    }

    fun getConfiguration(): Configuration = Configuration()
    fun getDisplayMetrics(): android.util.DisplayMetrics = android.util.DisplayMetrics()

    class NotFoundException(message: String) : RuntimeException(message)

    private companion object {
        val RAW_EXTENSIONS = arrayOf("", ".wav", ".bin", ".glsl", ".txt", ".json", ".dat")
        val DRAWABLE_EXTENSIONS = arrayOf(".png", ".jpg", ".jpeg", ".webp", "")
    }
}

class Configuration {
    var orientation: Int = ORIENTATION_PORTRAIT
    var screenWidthDp: Int = 0
    var screenHeightDp: Int = 0
    var densityDpi: Int = 160
    var uiMode: Int = 0

    companion object {
        const val ORIENTATION_UNDEFINED: Int = 0
        const val ORIENTATION_PORTRAIT: Int = 1
        const val ORIENTATION_LANDSCAPE: Int = 2
        const val UI_MODE_NIGHT_MASK: Int = 0x30
        const val UI_MODE_NIGHT_NO: Int = 0x10
        const val UI_MODE_NIGHT_YES: Int = 0x20
    }
}
