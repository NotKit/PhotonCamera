package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.common.uprightIn
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.SettingsBarEntry
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.ic_settings
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import org.jetbrains.compose.resources.painterResource

/**
 * SettingsBarLayout: the floating panel behind the chevron. Each entry is a label and
 * its state on the left, and one 40dp button per value on the right.
 */
@Composable
fun SettingsBar(
    entries: List<SettingsBarEntry>,
    visible: Boolean,
    orientation: Int,
    enabled: Boolean,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 4 },
        exit = fadeOut() + slideOutVertically { it / 4 },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PhotonColors.AuxContainer)
                .verticalScroll(rememberScrollState())
                .alpha(if (enabled) 1f else 0.5f),
        ) {
            entries.filter { it.visible }.forEach { entry ->
                SettingsBarEntryRow(entry, orientation, enabled, onEvent)
            }
            // SettingsBarLayout carried the way into the app's settings.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableNoRipple(enabled = enabled) { onEvent(CameraUiEvent.OpenSettings) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Icon(
                    painterResource(Res.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).uprightIn(orientation),
                )
            }
        }
    }
}

@Composable
private fun SettingsBarEntryRow(
    entry: SettingsBarEntry,
    orientation: Int,
    enabled: Boolean,
    onEvent: (CameraUiEvent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                entry.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
            Text(
                entry.stateLabel,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
            )
        }
        entry.options.forEach { option ->
            val selected = option.value == entry.selectedValue
            Box(
                Modifier
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickableNoRipple(enabled = enabled) {
                        onEvent(CameraUiEvent.SetSetting(entry.type, option.value))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(option.icon),
                    contentDescription = option.label,
                    tint = if (selected) Color.Black else Color.White,
                    modifier = Modifier.size(24.dp).uprightIn(orientation),
                )
            }
        }
    }
}
