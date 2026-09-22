package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.common.uprightIn
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.ic_exposure
import com.particlesdevs.photoncamera.composeui.resources.ic_focus
import com.particlesdevs.photoncamera.composeui.resources.ic_iso
import com.particlesdevs.photoncamera.composeui.resources.ic_saturation
import com.particlesdevs.photoncamera.composeui.resources.ic_shutter
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.ManualBarState
import com.particlesdevs.photoncamera.composeui.state.ManualParam
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * manual_palette.xml: the knob over a row of five parameter tabs.
 *
 * CameraScreen takes this as its `manualBar` slot, where the Android host passes
 * the inflated RelativeLayout instead. Both are driven by the same console -- this
 * one through [ManualBarState], which a ManualUi fills from KnobModel and
 * ManualModeModel.
 */
@Composable
fun ManualPalette(
    state: ManualBarState,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Binding.togglePanelVisibility: 100 ms, and gone from the layout after it.
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(100)) + slideInVertically(tween(100)) { it / 4 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 4 },
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Binding.setKnobVisibility: the dial drops away to a fifth of its
            // size, and the row closes up over it.
            AnimatedVisibility(
                visible = state.knobVisible,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)) +
                    scaleIn(tween(200), initialScale = 0.2f, transformOrigin = KnobOrigin),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)) +
                    scaleOut(tween(200), targetScale = 0.2f, transformOrigin = KnobOrigin),
            ) {
                ManualKnob(
                    knob = state.knob,
                    orientation = state.orientation,
                    onTick = { onEvent(CameraUiEvent.ManualKnobTick(it)) },
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PhotonColors.PanelTransparency)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ManualParamTabs.forEach { (param, icon) ->
                    ParamTab(
                        param = param,
                        icon = icon,
                        value = state.values[param].orEmpty(),
                        selected = state.selected == param,
                        orientation = state.orientation,
                        onEvent = onEvent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** buttons_container's five children, in the order manual_palette.xml lists them. */
private val ManualParamTabs: List<Pair<ManualParam, DrawableResource>> = listOf(
    ManualParam.FOCUS to Res.drawable.ic_focus,
    ManualParam.EXPOSURE to Res.drawable.ic_shutter,
    ManualParam.ISO to Res.drawable.ic_iso,
    ManualParam.EV to Res.drawable.ic_exposure,
    ManualParam.WB to Res.drawable.ic_saturation,
)

private val KnobOrigin = TransformOrigin(0.5f, 0f)

@Composable
private fun ParamTab(
    param: ManualParam,
    icon: DrawableResource,
    value: String,
    selected: Boolean,
    orientation: Int,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // manual_text_color: the accent when this knob is the one on the dial.
    val tint = if (selected) PhotonColors.Accent else Color.White
    Column(
        modifier
            .pointerInput(param) {
                detectTapGestures(
                    onTap = { onEvent(CameraUiEvent.SelectManualParam(param)) },
                    // onParamLongClicked: back to Auto, and the dial with it.
                    onLongPress = { onEvent(CameraUiEvent.ResetManualParam(param)) },
                )
            }
            .padding(top = 5.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.uprightIn(orientation),
        )
        Icon(
            painterResource(icon),
            contentDescription = param.name,
            tint = tint,
            modifier = Modifier.size(24.dp).uprightIn(orientation),
        )
    }
}
