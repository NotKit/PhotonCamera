package com.particlesdevs.photoncamera.composeui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The app's palette, taken from res/values/colors.xml and md_colors.xml. */
object PhotonColors {
    val Accent = Color(0xFF7E57C2)          // md_deep_purple_400
    val AccentDark = Color(0xFF4D2B90)      // md_deep_purple_400_dark
    val Text = Color(0xFFB794F6)            // colorText
    val Primary = Color(0xFF212121)
    val ToolBar = Color(0xFF1C1C1C)
    val Separator = Color(0xFF616161)
    val PanelTransparency = Color(0x40000000)
    val AuxContainer = Color(0x99000000)
    val ControlCol = Color(0x33000000)
    val GreyDis = Color(0xFF555555)
    val LightDis = Color(0xFFCACACA)
    val Light = Color(0xFFF1F1F1)
    val Blue = Color(0xFF6594FF)
    val WhiteThemeBackground = Color(0xFFF0EEE9)
}

/** Dimensions that res/values/dimens.xml pins for the camera screen. */
object PhotonDimens {
    val shutterButton = 80.dp
    val galleryButton = 60.dp
    val cameraSwitchButton = 60.dp
    val focusCircle = 80.dp
    val captureProgressCircle = 150.dp
    val arrow = 35.dp
    val auxContainerMargin = 6.dp
    val modeSwitcherPadding = 8.dp
    val topBarHeight = 48.dp
}

private val DarkColors = darkColorScheme(
    primary = PhotonColors.Accent,
    primaryContainer = PhotonColors.AccentDark,
    secondary = PhotonColors.Blue,
    background = Color.Black,
    surface = PhotonColors.Primary,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColors = lightColorScheme(
    primary = PhotonColors.Accent,
    primaryContainer = PhotonColors.AccentDark,
    secondary = PhotonColors.Blue,
    background = PhotonColors.WhiteThemeBackground,
    surface = PhotonColors.WhiteThemeBackground,
)

@Composable
fun PhotonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
