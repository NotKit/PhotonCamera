package com.particlesdevs.photoncamera.composeui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decoding a photo is the host's job: on Android that is Glide, which keeps the
 * gallery's existing caching and downsampling.
 */
interface ThumbnailLoader {
    /** Null until the image is ready; recomposes when it is. */
    @Composable
    fun rememberImage(id: String, targetSizePx: Int): ImageBitmap?
}

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader> {
    error("No ThumbnailLoader provided")
}
