package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens

/** HorizontalPicker's app:sideItems - two modes either side of the centred one. */
private const val SIDE_ITEMS = 2

/** How far the outermost of the five entries is faded. */
private const val EDGE_ALPHA = 0.35f

/** What the picker measured its cells against: the label, drawn bold. */
private val ModeTextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)

/**
 * The entry left sitting in the middle of the row.
 *
 * NOT firstVisibleItemIndex: that is only the centred one while the cells are
 * exactly a fifth of the row wide, and a cell sized to its label is wider. The
 * scroll the row makes to show the current mode then reported a DIFFERENT mode,
 * which selected it - and restarted the camera - before anyone had touched it.
 */
private fun LazyListState.centredItem(): Int? {
    val info = layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
}

/**
 * Replaces HorizontalPicker. Every mode gets a cell wide enough for its own label
 * - a fifth of the viewport when that is already enough - the selected one sits
 * dead centre, and a snapping fling moves the selection one cell at a time.
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
    val measurer = rememberTextMeasurer()
    // HorizontalPicker.calculateItemSize with computeRequiredItemWidth: a cell is
    // a fifth of the row, or the widest label plus 12dp when that is wider. A
    // narrower cell is what ellipsised "RAW ..." and "Moti..." here.
    val cellWidthPx = remember(labels, rowWidthPx, measurer, density) {
        val widest = labels.maxOf { measurer.measure(it, ModeTextStyle).size.width }
        maxOf(rowWidthPx / (SIDE_ITEMS * 2 + 1), widest + with(density) { 12.dp.roundToPx() })
    }
    val cellWidth = with(density) { cellWidthPx.toDp() }
    // The centred cell is the selected one, so the row carries half of what is
    // left of it as padding either side - which is cellWidth * sideItems only
    // while the cells exactly fill the row.
    val sidePadding = with(density) { ((rowWidthPx - cellWidthPx) / 2).coerceAtLeast(0).toDp() }

    // Scrolling picks a mode: the cell left in the centre is the selected one.
    // Only a scroll that actually happened counts - snapshotFlow emits the current
    // value on subscription, and taking that for a selection would change the mode
    // (and restart the camera) the moment the screen appears.
    //
    // `selected` is read through rememberUpdatedState because this coroutine is
    // keyed on the list and not on it: a captured `selected` goes stale on the
    // first change, and then the "did it actually move" guard below compares the
    // new centre against the OLD selection, calls onSelect again, and the mode is
    // applied and the camera restarted twice for one swipe. The second restart
    // lands while the first is still closing the device.
    val currentSelected by rememberUpdatedState(selected)
    LaunchedEffect(listState, labels.size) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (wasScrolling && !scrolling && rowWidthPx > 0) {
                    val centred = listState.centredItem()
                    if (centred != null && centred != currentSelected) onSelect(centred)
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
            // The bubble's own margin, from the draw loop: an eighth of the air
            // the label leaves in its cell, on each side.
            val margin = with(density) {
                val text = measurer.measure(label, ModeTextStyle).size.width
                (((cellWidthPx - text) / 8).coerceAtLeast(0)).toDp()
            }
            Box(
                Modifier
                    .width(cellWidth)
                    .padding(horizontal = margin, vertical = PhotonDimens.modeSwitcherPadding)
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
                    textAlign = TextAlign.Center,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
