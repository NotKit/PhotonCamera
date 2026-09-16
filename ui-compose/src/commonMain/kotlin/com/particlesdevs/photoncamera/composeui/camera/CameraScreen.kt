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

            Box(Modifier.fillMaxWidth().weight(1f)) {
                // The preview keeps the stream's aspect ratio and hugs the top, the way
                // the dummy_reference_view anchored it.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(state.viewfinderAspect)
                        .align(Alignment.TopCenter),
                ) {
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
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
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

            Box(Modifier.fillMaxWidth()) {
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
                            .size(PhotonDimens.arrow)
                            .rotate(chevron)
                            .clickableNoRipple { onEvent(CameraUiEvent.ToggleManualBar) },
                    )
                }
                CameraBottomBar(state, onEvent, Modifier.padding(top = PhotonDimens.arrow / 2))
            }
        }

    }
}

/** GestureDetector's SWIPE_THRESHOLD, in the same 100px units. */
private const val SWIPE_THRESHOLD_PX = 100f
