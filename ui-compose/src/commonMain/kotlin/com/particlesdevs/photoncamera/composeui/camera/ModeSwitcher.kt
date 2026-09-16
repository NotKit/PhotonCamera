package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens

/** HorizontalPicker's app:sideItems - two modes either side of the centred one. */
private const val SIDE_ITEMS = 2

/** How far the outermost of the five entries is faded. */
private const val EDGE_ALPHA = 0.35f

/**
 * Replaces HorizontalPicker. Every mode gets a cell a fifth of the viewport wide, so
 * the selected one sits dead centre with two on each side, and a snapping fling moves
 * the selection one cell at a time.
 */
@Composable
fun ModeSwitcher(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val listState = rememberLazyListState()
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val cellWidth = with(density) { (rowWidthPx / (SIDE_ITEMS * 2 + 1)).toDp() }
    val sidePadding = cellWidth * SIDE_ITEMS

    // Scrolling picks a mode: the cell left in the centre is the selected one.
    // Only a scroll that actually happened counts - snapshotFlow emits the current
    // value on subscription, and taking that for a selection would change the mode
    // (and restart the camera) the moment the screen appears.
    LaunchedEffect(listState, labels.size) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (wasScrolling && !scrolling && rowWidthPx > 0) {
                    val centred = listState.firstVisibleItemIndex
                    if (centred != selected && centred in labels.indices) onSelect(centred)
                }
                wasScrolling = scrolling
            }
    }

    // ...and a mode set from elsewhere scrolls the row.
    LaunchedEffect(selected, rowWidthPx) {
        if (rowWidthPx > 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(selected)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().onSizeChanged { rowWidthPx = it.width },
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically,
        flingBehavior = rememberSnapFlingBehavior(listState),
        userScrollEnabled = enabled,
    ) {
        itemsIndexed(labels) { index, label ->
            val isSelected = index == selected
            // The picker dims entries by how far they are from the centre, so the
            // outermost of the five reads as an edge rather than an option.
            val distance = abs(index - selected)
            val alpha by animateFloatAsState(
                if (distance <= 1) 1f else EDGE_ALPHA,
                label = "modeAlpha",
            )
            Box(
                Modifier
                    .width(cellWidth)
                    .padding(PhotonDimens.modeSwitcherPadding)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .clickableNoRipple(enabled = enabled) { onSelect(index) }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = alpha),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
