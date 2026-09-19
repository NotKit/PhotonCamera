package com.particlesdevs.photoncamera.composeui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

/**
 * Counter-rotates a control so it stays upright as the device turns, the way
 * CustomBinding.bindViewGroupChildrenRotate did for the View tree.
 *
 * THE ANGLE IS THE ORIENTATION, NOT ITS NEGATION.  Turn the phone clockwise by
 * φ and screen space turns clockwise with it, so an icon has to be turned back
 * by −φ *within* screen space to point at the sky again. CameraFragmentViewModel
 * already signs its value that way: a phone turned clockwise puts the left edge
 * on top and it reports −90, which is the −φ wanted here. Negating it a second
 * time leaves every icon 180° out -- upright in portrait, upside down in either
 * landscape, and right again only when the phone is held upside down.
 */
@Composable
fun Modifier.uprightIn(orientation: Int): Modifier {
    // Take the short way round, so 270 -> 0 does not spin the long way.
    val wrapped = ((orientation % 360) + 360) % 360
    val target = (if (wrapped > 180) wrapped - 360 else wrapped).toFloat()
    val angle by animateFloatAsState(target, label = "orientation")
    return this.rotate(angle)
}
