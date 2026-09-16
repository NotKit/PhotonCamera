package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.state.CameraMode
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import com.particlesdevs.photoncamera.composeui.theme.PhotonDimens

/**
 * roundbutton.xml and unlimitedbutton.xml as one composable: a state list of shapes
 * is just a `when` once the drawing is done in Compose.
 *
 * @param counting the old state_hovered - a countdown is running and a tap cancels it
 * @param activated for the continuous modes, true means "idle, ready to start"
 */
@Composable
fun ShutterButton(
    mode: CameraMode,
    enabled: Boolean,
    activated: Boolean,
    counting: Boolean,
    progress: Float,
    indeterminateProgress: Boolean,
    frameCount: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val continuous = mode == CameraMode.VIDEO || mode == CameraMode.UNLIMITED || mode == CameraMode.RAWVIDEO
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .size(PhotonDimens.shutterButton)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The processing ring, which circular_progress_bar2.xml drew at scale 1.35.
        if (progress > 0f || indeterminateProgress) {
            Canvas(Modifier.size(PhotonDimens.shutterButton)) {
                val stroke = 4.dp.toPx()
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = if (indeterminateProgress) 90f else 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Canvas(Modifier.size(PhotonDimens.shutterButton * 0.75f)) {
            val r = size.minDimension / 2f
            val body = when {
                counting -> Color(0xFFAAAAAA)
                pressed -> accent
                continuous || activated -> Color(0xFFAAAAAA)
                else -> PhotonColors.GreyDis
            }
            drawCircle(body, radius = r, center = center)

            when {
                // The hovered state draws an X: tapping again cancels the countdown.
                counting -> {
                    val inset = r * 0.5f
                    val w = 4.dp.toPx()
                    drawLine(PhotonColors.GreyDis, Offset(center.x - inset, center.y - inset),
                        Offset(center.x + inset, center.y + inset), w, StrokeCap.Round)
                    drawLine(PhotonColors.GreyDis, Offset(center.x + inset, center.y - inset),
                        Offset(center.x - inset, center.y + inset), w, StrokeCap.Round)
                }
                // Unlimited and video: a play triangle when idle, a stop square while running.
                continuous && activated -> {
                    val s = r * 0.62f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(center.x - s * 0.5f, center.y - s)
                        lineTo(center.x + s, center.y)
                        lineTo(center.x - s * 0.5f, center.y + s)
                        close()
                    }
                    drawPath(path, accent)
                }
                continuous && !activated -> {
                    val s = r * 0.62f
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(center.x - s, center.y - s),
                        size = Size(s * 2, s * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                }
            }
        }

        if (frameCount.isNotEmpty()) {
            Text(
                frameCount,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
