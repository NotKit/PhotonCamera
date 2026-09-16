package com.particlesdevs.photoncamera.gallery.compose

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.particlesdevs.photoncamera.R
import com.particlesdevs.photoncamera.composeui.gallery.ImageViewerScreen
import com.particlesdevs.photoncamera.composeui.gallery.LocalThumbnailLoader
import com.particlesdevs.photoncamera.composeui.state.ExifRow
import com.particlesdevs.photoncamera.composeui.state.GalleryItemState
import com.particlesdevs.photoncamera.composeui.state.ImageViewerEvent
import com.particlesdevs.photoncamera.composeui.state.ImageViewerUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.gallery.model.GalleryItem
import androidx.viewpager2.widget.ViewPager2

/**
 * The image viewer's chrome: the bars, the filmstrip and the EXIF panel. The photo
 * stays in the fragment's ViewPager2 of subsampling views, which this puts in the
 * screen's image slot.
 */
class ImageViewerHost(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBack()
        fun onDelete()
        fun onShare()
        fun onEdit()
        fun onCompare()
        fun onOpenGrid()
        fun onToggleHdr()
        fun onSelect(index: Int)
        fun onExifVisibilityChanged(visible: Boolean)
    }

    var state by mutableStateOf(ImageViewerUiState())
        private set

    @SuppressLint("InflateParams")
    val pagerView: View =
        LayoutInflater.from(context).inflate(R.layout.viewer_pager, null, false)

    val viewPager: ViewPager2 get() = pagerView as ViewPager2

    fun createView(): View = ComposeView(context).apply {
        setContent {
            PhotonTheme {
                CompositionLocalProvider(LocalThumbnailLoader provides GlideThumbnailLoader(context)) {
                    ImageViewerScreen(
                        state = state,
                        onEvent = ::onEvent,
                        modifier = Modifier.fillMaxSize(),
                        imageSurface = {
                            AndroidView(
                                factory = { pagerView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                }
            }
        }
    }

    fun setItems(items: List<GalleryItem>, index: Int) = update {
        it.copy(
            items = items.map { item ->
                GalleryItemState(
                    GalleryLibraryHost.idOf(item),
                    item.displayName.orEmpty(),
                    item.mediaTypeTag.orEmpty(),
                )
            },
            index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }

    fun setIndex(index: Int) = update { it.copy(index = index) }

    fun setExif(rows: List<ExifRow>, histogram: List<Float>) =
        update { it.copy(exifRows = rows, histogram = histogram) }

    fun setExifVisible(visible: Boolean) = update { it.copy(exifVisible = visible) }

    fun setChromeVisible(visible: Boolean) = update { it.copy(chromeVisible = visible) }

    fun setHdr(available: Boolean, active: Boolean) =
        update { it.copy(hdrAvailable = available, hdrActive = active) }

    fun setScaleLabel(label: String) = update { it.copy(scaleLabel = label) }

    fun setCompareAvailable(available: Boolean) = update { it.copy(compareAvailable = available) }

    private fun update(block: (ImageViewerUiState) -> ImageViewerUiState) {
        state = block(state)
    }

    private fun onEvent(event: ImageViewerEvent) {
        when (event) {
            ImageViewerEvent.Back -> listener.onBack()
            ImageViewerEvent.Delete -> listener.onDelete()
            ImageViewerEvent.Share -> listener.onShare()
            ImageViewerEvent.Edit -> listener.onEdit()
            ImageViewerEvent.Compare -> listener.onCompare()
            ImageViewerEvent.OpenGrid -> listener.onOpenGrid()
            ImageViewerEvent.ToggleHdr -> listener.onToggleHdr()
            ImageViewerEvent.ToggleExif -> {
                setExifVisible(!state.exifVisible)
                listener.onExifVisibilityChanged(state.exifVisible)
            }
            ImageViewerEvent.ToggleChrome -> setChromeVisible(!state.chromeVisible)
            is ImageViewerEvent.Select -> listener.onSelect(event.index)
        }
    }
}
