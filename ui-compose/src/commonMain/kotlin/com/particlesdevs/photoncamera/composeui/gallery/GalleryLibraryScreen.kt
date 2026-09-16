package com.particlesdevs.photoncamera.composeui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.ic_compare
import com.particlesdevs.photoncamera.composeui.resources.ic_delete
import com.particlesdevs.photoncamera.composeui.resources.ic_settings
import com.particlesdevs.photoncamera.composeui.resources.ic_share
import com.particlesdevs.photoncamera.composeui.state.GalleryItemState
import com.particlesdevs.photoncamera.composeui.state.GalleryLibraryEvent
import com.particlesdevs.photoncamera.composeui.state.GalleryLibraryUiState
import org.jetbrains.compose.resources.painterResource

/**
 * fragment_gallery_image_library.xml: the folder strip down the left, the thumbnail
 * grid, and the action buttons that appear once something is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryLibraryScreen(
    state: GalleryLibraryUiState,
    onEvent: (GalleryLibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectionMode) "${state.selectedCount} selected" else "Gallery",
                        fontSize = 18.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        onEvent(
                            if (state.selectionMode) GalleryLibraryEvent.ClearSelection
                            else GalleryLibraryEvent.Back
                        )
                    }) { Text("←", fontSize = 22.sp) }
                },
                actions = {
                    IconButton(onClick = { onEvent(GalleryLibraryEvent.Settings) }) {
                        Icon(painterResource(Res.drawable.ic_settings), "Gallery settings")
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(state.selectionMode) {
                Column(horizontalAlignment = Alignment.End) {
                    if (state.compareAvailable && state.selectedCount == 2) {
                        SmallFab(GalleryLibraryEvent.Compare, "Compare", onEvent) {
                            Icon(painterResource(Res.drawable.ic_compare), null)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    SmallFab(GalleryLibraryEvent.Delete, "Delete", onEvent) {
                        Icon(painterResource(Res.drawable.ic_delete), null)
                    }
                    Spacer(Modifier.height(12.dp))
                    SmallFab(GalleryLibraryEvent.Share, "Share", onEvent) {
                        Icon(painterResource(Res.drawable.ic_share), null)
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (state.folders.isNotEmpty()) {
                LazyColumn(
                    Modifier.width(70.dp).fillMaxHeight().padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    itemsIndexed(state.folders, key = { index, folder -> "$index:${folder.id}" }) { _, folder ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (folder.selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { onEvent(GalleryLibraryEvent.SelectFolder(folder.id)) }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(folder.label, fontSize = 12.sp, maxLines = 2, color = Color.White)
                        }
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(state.columns),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
            ) {
                // The same file can appear under more than one folder, so the uri
                // alone is not a unique key.
                itemsIndexed(state.items, key = { index, item -> "$index:${item.id}" }) { _, item ->
                    GalleryThumbnail(item, state.selectionMode, onEvent)
                }
            }
        }
    }
}

@Composable
private fun SmallFab(
    event: GalleryLibraryEvent,
    description: String,
    onEvent: (GalleryLibraryEvent) -> Unit,
    icon: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = { onEvent(event) },
        containerColor = MaterialTheme.colorScheme.primary,
    ) { icon() }
}

/** thumbnail_square_image_view.xml: a square crop, its type tag, and a selection ring. */
@Composable
private fun GalleryThumbnail(
    item: GalleryItemState,
    selectionMode: Boolean,
    onEvent: (GalleryLibraryEvent) -> Unit,
) {
    val density = LocalDensity.current
    val targetPx = with(density) { 160.dp.roundToPx() }
    val image = LocalThumbnailLoader.current.rememberImage(item.id, targetPx)

    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFAAAAAA))
            .then(
                if (item.selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else Modifier
            )
            .pointerInput(item.id, selectionMode) {
                detectTapGestures(
                    onTap = {
                        onEvent(
                            if (selectionMode) GalleryLibraryEvent.ToggleSelect(item.id)
                            else GalleryLibraryEvent.Open(item.id)
                        )
                    },
                    onLongPress = { onEvent(GalleryLibraryEvent.ToggleSelect(item.id)) },
                )
            },
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.typeTag.isNotEmpty()) {
            Text(
                item.typeTag,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0x88000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (item.selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Color.White, fontSize = 12.sp) }
        }
    }
}
