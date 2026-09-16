package com.particlesdevs.photoncamera.gallery.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.particlesdevs.photoncamera.composeui.gallery.ThumbnailLoader

/**
 * The gallery's images still come through Glide, so its disk cache and downsampling
 * are unchanged; only the drawing moved to Compose.
 */
class GlideThumbnailLoader(context: Context) : ThumbnailLoader {

    // Bound to the application, not the activity: a request manager tied to an
    // activity refuses to start or clear anything once that activity is destroyed,
    // and the disposal below already releases every target.
    private val appContext = context.applicationContext

    @Composable
    override fun rememberImage(id: String, targetSizePx: Int): ImageBitmap? {
        var bitmap by remember(id, targetSizePx) { mutableStateOf<ImageBitmap?>(null) }
        DisposableEffect(id, targetSizePx) {
            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    bitmap = resource.asImageBitmap()
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    bitmap = null
                }
            }
            Glide.with(appContext)
                .asBitmap()
                .load(id.toUri())
                .override(targetSizePx)
                .into(target)
            onDispose { Glide.with(appContext).clear(target) }
        }
        return bitmap
    }
}
