package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.particlesdevs.photoncamera.composeui.common.uprightIn
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.ic_flip_camera
import com.particlesdevs.photoncamera.composeui.resources.ic_gallery_ring
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens
import org.jetbrains.compose.resources.painterResource

/** layout_bottombuttons.xml + layout_modeswitcher.xml. */
@Composable
fun CameraBottomBar(
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            // layout_bottombuttons' own 5dp margin, and the 16dp the bottombar adds
            // ON TOP of it only - padding both ends pushed the mode chips 24dp down
            // where the View layout leaves 13dp.
            Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 21.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(PhotonDimens.cameraSwitchButton), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(Res.drawable.ic_flip_camera),
                    contentDescription = "Lens Switch Button",
                    tint = Color.White,
                    // match_parent in a camera_switch_button_size box, as
                    // layout_bottombuttons.xml has it - not an inset icon.
                    modifier = Modifier
                        .fillMaxSize()
                        .uprightIn(state.orientation)
                        .clickableNoRipple(enabled = !state.uiLocked) { onEvent(CameraUiEvent.FlipCamera) },
                )
            }

            ShutterButton(
                mode = state.mode,
                enabled = state.shutterEnabled,
                activated = state.shutterActivated,
                counting = state.counting,
                progress = state.processingProgress,
                indeterminateProgress = state.processingIndeterminate,
                frameCount = state.frameCount,
                onClick = { onEvent(CameraUiEvent.Shutter) },
            )

            GalleryButton(state, onEvent)
        }

        ModeSwitcher(
            labels = state.modeLabels,
            selected = state.mode.ordinal,
            enabled = !state.uiLocked,
            onSelect = { onEvent(CameraUiEvent.SelectMode(com.particlesdevs.photoncamera.composeui.state.CameraMode.entries[it])) },
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun GalleryButton(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit) {
    Box(
        Modifier
            .size(PhotonDimens.galleryButton)
            .alpha(if (state.uiLocked) 0.5f else 1f)
            .clickableNoRipple(enabled = !state.uiLocked) { onEvent(CameraUiEvent.OpenGallery) },
        contentAlignment = Alignment.Center,
    ) {
        val thumb = state.galleryThumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(54.dp).clip(CircleShape).uprightIn(state.orientation),
            )
        } else {
            Box(Modifier.size(54.dp).clip(CircleShape).background(PhotonColors.GreyDis))
        }
        Image(
            painterResource(Res.drawable.ic_gallery_ring),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
