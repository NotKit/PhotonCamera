package com.particlesdevs.photoncamera.composeui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.resources.*
import com.particlesdevs.photoncamera.composeui.state.ImageViewerEvent
import com.particlesdevs.photoncamera.composeui.state.ImageViewerUiState
import org.jetbrains.compose.resources.painterResource
import kotlin.math.max

/**
 * fragment_gallery_image_viewer.xml: the filmstrip, the action bars and the EXIF panel.
 *
 * @param imageSurface the photo itself. Like the camera's preview it stays the host's:
 *        on Android it is the pager of subsampling views, which tile very large files
 *        and decode UltraHDR gain maps.
 */
@Composable
fun ImageViewerScreen(
    state: ImageViewerUiState,
    onEvent: (ImageViewerEvent) -> Unit,
    modifier: Modifier = Modifier,
    imageSurface: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        imageSurface()

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onEvent(ImageViewerEvent.Back) }) {
                    Icon(painterResource(Res.drawable.ic_baseline_arrow_back_24), "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.current?.displayName.orEmpty(), color = Color.White, fontSize = 14.sp)
                    if (state.scaleLabel.isNotEmpty()) {
                        Text(state.scaleLabel, color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    }
                }
                IconButton(onClick = { onEvent(ImageViewerEvent.OpenGrid) }) {
                    Icon(painterResource(Res.drawable.ic_photo_library), "Grid", tint = Color.White)
                }
                if (state.compareAvailable) {
                    IconButton(onClick = { onEvent(ImageViewerEvent.Compare) }) {
                        Icon(painterResource(Res.drawable.ic_compare), "Compare", tint = Color.White)
                    }
                }
                if (state.hdrAvailable) {
                    TextButton(onClick = { onEvent(ImageViewerEvent.ToggleHdr) }) {
                        Text(
                            "HDR",
                            color = if (state.hdrActive) MaterialTheme.colorScheme.primary else Color.White,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().background(Color(0x66000000))) {
                Filmstrip(state, onEvent)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { onEvent(ImageViewerEvent.Delete) }) {
                        Icon(painterResource(Res.drawable.ic_delete), "Delete", tint = Color.White)
                    }
                    IconButton(onClick = { onEvent(ImageViewerEvent.ToggleExif) }) {
                        Icon(painterResource(Res.drawable.ic_info), "EXIF", tint = Color.White)
                    }
                    IconButton(onClick = { onEvent(ImageViewerEvent.Edit) }) {
                        Icon(painterResource(Res.drawable.ic_edit), "Edit", tint = Color.White)
                    }
                    IconButton(onClick = { onEvent(ImageViewerEvent.Share) }) {
                        Icon(painterResource(Res.drawable.ic_share), "Share", tint = Color.White)
                    }
                }
            }
        }

        if (state.exifVisible) {
            ExifPanel(state, onEvent, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Filmstrip(state: ImageViewerUiState, onEvent: (ImageViewerEvent) -> Unit) {
    val targetPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        itemsIndexed(state.items, key = { index, item -> "$index:${item.id}" }) { index, item ->
            val image = LocalThumbnailLoader.current.rememberImage(item.id, targetPx)
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF444444))
                    .pointerInput(item.id) {
                        detectTapGestures { onEvent(ImageViewerEvent.Select(index)) }
                    },
            ) {
                if (image != null) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (index == state.index) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

/** exif_dialog.xml: the metadata table and the histogram above it. */
@Composable
private fun ExifPanel(
    state: ImageViewerUiState,
    onEvent: (ImageViewerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
        color = Color(0xF0202020),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Details", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEvent(ImageViewerEvent.ToggleExif) }) { Text("Close") }
            }
            if (state.histogram.isNotEmpty()) {
                Histogram(state.histogram, Modifier.fillMaxWidth().height(90.dp).padding(vertical = 8.dp))
            }
            state.exifRows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(row.label, color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(row.value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
                }
            }
        }
    }
}

/** The gallery's Histogram view: one filled curve over the normalised bins. */
@Composable
private fun Histogram(bins: List<Float>, modifier: Modifier = Modifier) {
    val peak = max(bins.maxOrNull() ?: 1f, 1e-6f)
    Canvas(modifier) {
        val step = size.width / bins.size
        bins.forEachIndexed { index, value ->
            val height = size.height * (value / peak)
            drawRect(
                color = Color.White.copy(alpha = 0.8f),
                topLeft = Offset(index * step, size.height - height),
                size = androidx.compose.ui.geometry.Size(step, height),
            )
        }
    }
}

