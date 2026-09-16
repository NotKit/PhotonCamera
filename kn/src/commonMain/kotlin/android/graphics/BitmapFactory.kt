/* android.graphics.BitmapFactory: PNG and JPEG decoding.
 *
 * Skia's on Android, photoncam.natives.ImageCodec here (libpng + libjpeg-turbo,
 * or a vendored stb).  The decoder hands back ARGB_8888 as one Int per pixel,
 * which is already what Bitmap holds, so a decode is a copy and no more.
 *
 * Options carries only what the app reads: inJustDecodeBounds, inSampleSize,
 * outWidth/outHeight and outMimeType.  The rest of Android's Options
 * (inPreferredConfig, inDensity, inBitmap...) is ABSENT rather than ignored --
 * a field that silently does nothing is worse than one the compiler names.
 *
 * A resource is looked up by entry name under the asset root, the way
 * Resources.openRawResource looks up `raw/<name>`: `drawable/<name>.png` first,
 * then the asset root itself, which is where the app already ships
 * neutral_lut.png and friends.  Staging the PNGs of res/drawable into assets/drawable
 * is the host lane's packaging step; without it decodeResource answers null and
 * says so, exactly as a missing resource does. */
package android.graphics

import android.content.res.Resources
import java.io.File
import java.io.InputStream
import photoncam.natives.ImageCodec

object BitmapFactory {

    class Options {
        /** Decode dimensions only; the decode returns null and fills out*. */
        var inJustDecodeBounds: Boolean = false

        /** 1, or a power of two: keep one pixel out of every inSampleSize. */
        var inSampleSize: Int = 1

        var outWidth: Int = -1
        var outHeight: Int = -1
        var outMimeType: String? = null
    }

    fun decodeFile(pathName: String?): Bitmap? = decodeFile(pathName, null)

    fun decodeFile(pathName: String?, opts: Options?): Bitmap? {
        if (pathName == null) return null
        if (opts != null && opts.inJustDecodeBounds) {
            val bounds = ImageCodec.probeFile(pathName) ?: return fail(opts, pathName)
            return bounds(opts, bounds.width, bounds.height, pathName)
        }
        val decoded = ImageCodec.decodeFile(pathName) ?: return fail(opts, pathName)
        return finish(decoded, opts, pathName)
    }

    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap? =
        decodeByteArray(data, offset, length, null)

    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int, opts: Options?): Bitmap? {
        if (data == null) return null
        if (opts != null && opts.inJustDecodeBounds) {
            val bounds = ImageCodec.probeBytes(data, offset, length) ?: return fail(opts, null)
            return bounds(opts, bounds.width, bounds.height, null)
        }
        val decoded = ImageCodec.decodeBytes(data, offset, length) ?: return fail(opts, null)
        return finish(decoded, opts, null)
    }

    fun decodeStream(input: InputStream?): Bitmap? = decodeStream(input, null, null)

    /** [outPadding] is a nine-patch concept; there are none here, so it is left alone. */
    fun decodeStream(input: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? {
        if (input == null) return null
        val bytes = input.readBytes()
        return decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun decodeResource(res: Resources?, id: Int): Bitmap? = decodeResource(res, id, null)

    fun decodeResource(res: Resources?, id: Int, opts: Options?): Bitmap? {
        val path = resourcePath(res, id) ?: run {
            android.util.Log.e("BitmapFactory", "no image for resource #$id")
            return fail(opts, null)
        }
        return decodeFile(path, opts)
    }

    /** Where a drawable resource's bytes are, or null if nothing is staged. */
    private fun resourcePath(res: Resources?, id: Int): String? {
        if (res == null) return null
        val name = try {
            res.getResourceEntryName(id)
        } catch (e: Resources.NotFoundException) {
            return null
        }
        val root = res.getAssets().getRoot()
        for (dir in SEARCH_DIRS) {
            for (ext in EXTENSIONS) {
                val f = File(root, if (dir.isEmpty()) "$name$ext" else "$dir/$name$ext")
                if (f.exists()) return f.getPath()
            }
        }
        return null
    }

    /** Down-sample by [Options.inSampleSize], the way Android's decoder does. */
    private fun finish(decoded: ImageCodec.Decoded, opts: Options?, mime: String?): Bitmap {
        val sample = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
        var width = decoded.width
        var height = decoded.height
        var pixels = decoded.pixels

        if (sample > 1) {
            val w = (width + sample - 1) / sample
            val h = (height + sample - 1) / sample
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val srcRow = y * sample * width
                val dstRow = y * w
                for (x in 0 until w) out[dstRow + x] = pixels[srcRow + x * sample]
            }
            width = w
            height = h
            pixels = out
        }

        opts?.outWidth = width
        opts?.outHeight = height
        opts?.outMimeType = mime?.let(::mimeOf)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun bounds(opts: Options, width: Int, height: Int, path: String?): Bitmap? {
        val sample = opts.inSampleSize.coerceAtLeast(1)
        opts.outWidth = (width + sample - 1) / sample
        opts.outHeight = (height + sample - 1) / sample
        opts.outMimeType = path?.let(::mimeOf)
        return null
    }

    /** Android leaves out* at -1 when a decode fails; so does this. */
    private fun fail(opts: Options?, path: String?): Bitmap? {
        opts?.outWidth = -1
        opts?.outHeight = -1
        opts?.outMimeType = null
        return null
    }

    private fun mimeOf(path: String): String? = when {
        path.endsWith(".png", ignoreCase = true) -> "image/png"
        path.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
        path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        else -> null
    }

    private val SEARCH_DIRS = arrayOf("drawable", "raw", "")
    private val EXTENSIONS = arrayOf(".png", ".jpg", ".jpeg", "")
}
