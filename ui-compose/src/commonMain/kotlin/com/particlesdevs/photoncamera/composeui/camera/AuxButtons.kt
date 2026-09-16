package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.common.uprightIn
import com.particlesdevs.photoncamera.composeui.state.AuxLens
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors

/**
 * AuxButtonsLayout: the lens palette. Hidden when there is nothing to choose between,
 * which is what updateVisibility() did with a child count of one.
 */
@Composable
fun AuxButtons(
    lenses: List<AuxLens>,
    activeId: String,
    orientation: Int,
    visible: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && lenses.size > 1,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(PhotonColors.AuxContainer)
                .padding(4.dp)
                .alpha(if (enabled) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            lenses.forEach { lens ->
                val selected = lens.cameraId == activeId
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickableNoRipple(enabled = enabled) { onSelect(lens.cameraId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        lens.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.uprightIn(orientation),
                    )
                }
            }
        }
    }
}
