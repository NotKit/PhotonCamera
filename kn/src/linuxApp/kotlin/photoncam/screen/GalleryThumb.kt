package photoncam.screen

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.particlesdevs.photoncamera.util.FileManager
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Glide's `.override(200)` in CameraFragmentViewModel.updateGalleryThumb. */
private const val THUMB_PX = 200

/** DCIM/Camera and DCIM/PhotonCamera/Raw hold what the pipeline writes. */
private val PICTURE_SUFFIXES = listOf(".jpg", ".jpeg", ".png")

/**
 * CameraFragmentViewModel.updateGalleryThumb, which nothing on this port had:
 * the gallery button drew its grey placeholder for the whole life of the app.
 *
 * Glide is what did the decoding there, and its override(200) is the reason the
 * full-size JPEG never stayed in memory. Skia decodes the whole frame, so the
 * copy kept here is the scaled one and the frame it came from is closed at once
 * -- a 50 MP shot is 200 MB of pixels and the button is 54dp across.
 *
 * EXIF orientation is NOT applied: makeFromEncoded does not read it, as Glide
 * did, so a picture taken sideways shows sideways in the button.
 */
internal fun loadGalleryThumb(path: String?): ImageBitmap? = runCatching {
    val file = path?.let { java.io.File(it) }?.takeIf { it.exists() } ?: newestPicture()
    ?: return null
    val bytes = java.io.FileInputStream(file).use { it.readBytes() }
    val full = Image.makeFromEncoded(bytes)
    try {
        val longest = maxOf(full.width, full.height).coerceAtLeast(1)
        val scale = minOf(1f, THUMB_PX.toFloat() / longest)
        val w = (full.width * scale).toInt().coerceAtLeast(1)
        val h = (full.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            surface.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        } finally {
            surface.close()
        }
    } finally {
        full.close()
    }
}.getOrElse { e ->
    println("[pc] gallery thumbnail failed: $e")
    null
}

/** GalleryFileOperations.fetchLatestImage, over the folders FileManager makes. */
private fun newestPicture(): java.io.File? =
    listOfNotNull(FileManager.sDCIM_CAMERA, FileManager.sPHOTON_DIR)
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { f -> f.isFile() && PICTURE_SUFFIXES.any { f.name.lowercase().endsWith(it) } }
        .maxByOrNull { it.lastModified() }
