package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.state.CameraUiState

/**
 * Everything drawn over the preview: the composition grid, the capture progress ring
 * and the countdown. The preview itself is the host's, passed in as a slot.
 */
@Composable
fun ViewfinderOverlay(state: CameraUiState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.gridValue != 0) {
            Canvas(Modifier.fillMaxSize()) { drawGrid(state.gridValue) }
        }

        if (state.captureProgressVisible && state.captureProgressAlpha > 0f) {
            Canvas(
                Modifier
                    .size(com.particlesdevs.photoncamera.composeui.theme.PhotonDimens.captureProgressCircle)
                    .alpha(state.captureProgressAlpha)
            ) {
                val stroke = 6.dp.toPx()
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * state.captureProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        if (state.frameTimerVisible && state.timerCount.isNotEmpty()) {
            Text(state.timerCount, color = Color.White, fontSize = 36.sp)
        }
    }
}

/** The five grids of SurfaceViewOverViewfinder, line for line. */
private fun DrawScope.drawGrid(value: Int) {
    val w = size.width
    val h = size.height
    val paint = Color.White.copy(alpha = 0.6f)
    val width = 1.dp.toPx()
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(paint, Offset(x1, y1), Offset(x2, y2), width)

    when (value) {
        1 -> {
            line(w / 3f, 0f, w / 3f, h); line(2f * w / 3f, 0f, 2f * w / 3f, h)
            line(0f, h / 3f, w, h / 3f); line(0f, 2f * h / 3f, w, 2f * h / 3f)
        }
        2 -> {
            line(w / 4f, 0f, w / 4f, h); line(w / 2f, 0f, w / 2f, h); line(3 * w / 4f, 0f, 3 * w / 4f, h)
            line(0f, h / 4f, w, h / 4f); line(0f, h / 2f, w, h / 2f); line(0f, 3 * h / 4f, w, 3 * h / 4f)
        }
        3 -> {
            val gr = GOLDEN_RATIO
            line(w / (1 + gr), 0f, w / (1 + gr), h); line(gr * w / (1 + gr), 0f, gr * w / (1 + gr), h)
            line(0f, h / (1 + gr), w, h / (1 + gr)); line(0f, gr * h / (1 + gr), w, gr * h / (1 + gr))
        }
        4 -> {
            line(0f, 0f, w, h)
            line(w / 3f, h / 3f, w, 0f)
            line(2f * w / 3f, 2f * h / 3f, 0f, h)
        }
    }
}

private const val GOLDEN_RATIO = 1.618034f
