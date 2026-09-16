package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.particlesdevs.photoncamera.composeui.common.uprightIn
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.ic_settings
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** layout_main_topbar.xml: a row of square toggles on a translucent panel. */
@Composable
fun CameraTopBar(
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PhotonDimens.topBarHeight)
            .background(PhotonColors.PanelTransparency),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.topBar.hdrxVisible) {
            TopBarButton(CameraIcons.hdrx(state.hdrxOn), state.orientation) { onEvent(CameraUiEvent.ToggleHdrx) }
        }
        if (state.topBar.eisVisible) {
            TopBarButton(CameraIcons.eis(state.eisOn), state.orientation) { onEvent(CameraUiEvent.ToggleEis) }
        }
        if (state.topBar.timerVisible) {
            TopBarButton(CameraIcons.timer(state.timerIndex), state.orientation) { onEvent(CameraUiEvent.ToggleTimer) }
        }
        if (state.topBar.quadVisible) {
            TopBarButton(CameraIcons.quad(state.quadOn), state.orientation) { onEvent(CameraUiEvent.ToggleQuad) }
        }
        if (state.topBar.fpsVisible) {
            TopBarButton(CameraIcons.fps(state.fpsMode), state.orientation) { onEvent(CameraUiEvent.ToggleFps) }
        }
        TopBarButton(CameraIcons.grid(state.gridValue), state.orientation) { onEvent(CameraUiEvent.ToggleGrid) }
        if (state.topBar.flashVisible) {
            TopBarButton(CameraIcons.flash(state.flashValue), state.orientation) { onEvent(CameraUiEvent.ToggleFlash) }
        }
        if (state.topBar.settingsVisible) {
            TopBarButton(Res.drawable.ic_settings, state.orientation) { onEvent(CameraUiEvent.OpenSettings) }
        }
    }
}

@Composable
private fun TopBarButton(
    icon: DrawableResource,
    orientation: Int,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(PhotonDimens.topBarHeight).clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(26.dp).uprightIn(orientation),
            tint = tint,
        )
    }
}
