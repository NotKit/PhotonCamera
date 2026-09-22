package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.chevron_up
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens
import org.jetbrains.compose.resources.painterResource

/**
 * camera_fragment.xml as one composable.
 *
 * @param viewfinder the live preview. It is the one piece that cannot be common code -
 *        on Android it is the GL SurfaceView the capture session draws into - so the
 *        host passes it in.
 * @param manualBar the manual-mode console, likewise supplied by the host.
 */
@Composable
fun CameraScreen(
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    viewfinder: @Composable BoxScope.() -> Unit = {},
    manualBar: (@Composable BoxScope.() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black)
                if (state.gradientBackground) {
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Black,
                            0.5f to Color.Black,
                            1f to accent,
                            start = Offset(0f, size.height * state.gradientStart),
                            end = Offset(0f, size.height * state.gradientEnd),
                        )
                    )
                }
            }
    ) {
        // The background is full-bleed - gradient_vector is the root view's - but the
        // controls keep clear of the system bars, as the View layout did. Without this
        // the bottom bar slides down into the gradient and the top bar under the status bar.
        Column(Modifier.fillMaxSize().padding(top = state.topInset)) {
            // The top bar sits above the viewfinder box, outside the camera container,
            // and goes invisible while the settings bar is up.
            Box(Modifier.fillMaxWidth().height(PhotonDimens.topBarHeight)) {
                if (!state.settingsBarVisible) {
                    CameraTopBar(state, onEvent)
                }
            }

            // camera_container: the picture and nothing else. Everything below it
            // belongs to layout_bottombar, and that boundary is the line the manual
            // strip, the arrow and the shutter row are all measured from - split them
            // across two lines and the knob floats away from its own arrow.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.viewfinderAspect),
            ) {
                Box(Modifier.matchParentSize()) {
                    viewfinder()
                    ViewfinderOverlay(state)
                    // Swipe.java: tap focuses and puts the settings bar away, a long press
                    // does spot WB, and the two flings trade the settings bar for the
                    // manual panel.
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onEvent(CameraUiEvent.ViewfinderTap(it.x, it.y)) },
                                    onLongPress = { onEvent(CameraUiEvent.ViewfinderLongPress(it.x, it.y)) },
                                )
                            }
                            .pointerInput(Unit) {
                                var dy = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { dy = 0f },
                                    onDragEnd = {
                                        if (dy < -SWIPE_THRESHOLD_PX) onEvent(CameraUiEvent.SwipeUp)
                                        else if (dy > SWIPE_THRESHOLD_PX) onEvent(CameraUiEvent.SwipeDown)
                                    },
                                ) { _, amount -> dy += amount }
                            },
                    )
                    // layout_constraintBottom_toTopOf="@id/manual_mode": the lens
                    // palette sits above the manual console, and both are drawn over
                    // the foot of the picture rather than over the gradient below it.
                    // The console is always in the tree - it finds its own views
                    // through the activity, and hides itself with setPanelVisibility,
                    // which makes it measure zero.
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = ManualBarLine),
                        horizontalAlignment = Alignment.End,
                    ) {
                        AuxButtons(
                            lenses = state.auxLenses,
                            activeId = state.activeCameraId,
                            orientation = state.orientation,
                            visible = !state.settingsBarVisible,
                            enabled = !state.uiLocked,
                            onSelect = { onEvent(CameraUiEvent.SelectAux(it)) },
                            modifier = Modifier
                                .padding(end = PhotonDimens.auxContainerMargin, bottom = 6.dp),
                        )
                        if (manualBar != null) {
                            Box(Modifier.fillMaxWidth()) { manualBar() }
                        }
                    }

                }

                if (!state.settingsBarVisible) {
                    Icon(
                        painterResource(Res.drawable.chevron_up),
                        contentDescription = "Show settings",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .size(PhotonDimens.arrow)
                            .rotate(180f)
                            .clickableNoRipple { onEvent(CameraUiEvent.SetSettingsBarVisible(true)) },
                    )
                }

                SettingsBar(
                    entries = state.settingsBarEntries,
                    visible = state.settingsBarVisible,
                    orientation = state.orientation,
                    enabled = !state.uiLocked,
                    onEvent = onEvent,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.videoRecordingInfo?.let { info ->
                    Text(
                        info,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp)
                            .background(Color(0x88000000))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            // layout_bottombar: it reaches from the foot of the picture to the
            // bottom of the screen and its content is CENTRED in that - the
            // ConstraintLayout pinned the row to both edges, so the buttons float
            // in the space rather than sitting on the screen's edge.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.manualBarAvailable) {
                    val chevron by animateFloatAsState(
                        if (state.manualBarExpanded) 180f else 0f, label = "manualChevron",
                    )
                    Icon(
                        painterResource(Res.drawable.chevron_up),
                        contentDescription = "Manual controls",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            // open_close_manual: its bottom is 10dp BELOW the line
                            // the strip hangs from, so most of the arrow sits over
                            // the strip rather than inside the bar.
                            .offset(y = -(PhotonDimens.arrow - 10.dp + ManualBarLine))
                            .size(PhotonDimens.arrow)
                            .rotate(chevron)
                            .clickableNoRipple { onEvent(CameraUiEvent.ToggleManualBar) },
                    )
                }
                CameraBottomBar(
                    state,
                    onEvent,
                    Modifier
                        .align(Alignment.Center)
                        // A screen too short for both keeps the buttons their own
                        // size and lets them reach up over the picture, which is
                        // what the constraint layout did when the space ran out.
                        .wrapContentHeight(unbounded = true)
                        .padding(top = PhotonDimens.arrow / 2),
                )
            }
        }

    }
}

/**
 * How far above the foot of the picture the manual strip and its arrow hang.
 *
 * In the app that line is the top of layout_bottombar, which dummy_reference_view
 * puts a little above the preview box rather than exactly on it; here the bottom
 * bar starts at the foot of the picture, so the line is carried as this. Measured
 * off the app on a 20:9 panel: the arrow's box ends 9dp above the picture, the
 * strip 19dp.
 */
private val ManualBarLine = 19.dp

/** GestureDetector's SWIPE_THRESHOLD, in the same 100px units. */
private const val SWIPE_THRESHOLD_PX = 100f
