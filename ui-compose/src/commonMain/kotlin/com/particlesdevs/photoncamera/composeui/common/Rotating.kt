package com.particlesdevs.photoncamera.composeui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

/**
 * Counter-rotates a control so it stays upright as the device turns, the way
 * CustomBinding.bindViewGroupChildrenRotate did for the View tree.
 */
@Composable
fun Modifier.uprightIn(orientation: Int): Modifier {
    // Take the short way round, so 270 -> 0 does not spin the long way.
    val target = -(((orientation % 360) + 360) % 360).toFloat()
    val normalised = if (target < -180f) target + 360f else target
    val angle by animateFloatAsState(normalised, label = "orientation")
    return this.rotate(angle)
}
