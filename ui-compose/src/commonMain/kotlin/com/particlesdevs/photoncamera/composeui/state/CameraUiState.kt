package com.particlesdevs.photoncamera.composeui.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource

/** Mirrors com.particlesdevs.photoncamera.api.CameraMode; the ordinal is the stored preference. */
enum class CameraMode { UNLIMITED, RAWVIDEO, MOTION, PHOTO, NIGHT, VIDEO }

/** Mirrors com.particlesdevs.photoncamera.settings.SettingType. */
enum class SettingType { FLASH, HDRX, QUAD, GRID, FPS_60, TIMER, EIS, RAW, BATTERY_SAVER, BRACKETING, AE_METERING_STD }

@Immutable
data class AuxLens(val cameraId: String, val label: String)

@Immutable
data class SettingsBarOption(val value: Int, val label: String, val icon: DrawableResource)

@Immutable
data class SettingsBarEntry(
    val type: SettingType,
    val title: String,
    val options: List<SettingsBarOption>,
    val selectedValue: Int,
    val visible: Boolean = true,
) {
    val stateLabel: String get() = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
}

@Immutable
data class TopBarState(
    val timerVisible: Boolean = true,
    val quadVisible: Boolean = false,
    val flashVisible: Boolean = true,
    val eisVisible: Boolean = false,
    val fpsVisible: Boolean = false,
    val hdrxVisible: Boolean = false,
    val settingsVisible: Boolean = false,
)

/**
 * Everything the camera screen draws. Held by the host (CameraFragment on Android)
 * and replaced wholesale on every change, so the screen itself stays stateless.
 */
@Immutable
data class CameraUiState(
    val mode: CameraMode = CameraMode.PHOTO,
    val modeLabels: List<String> = emptyList(),
    /** Device orientation in degrees; the controls counter-rotate to stay upright. */
    val orientation: Int = 0,
    val topBar: TopBarState = TopBarState(),
    /**
     * CustomBinding.adjustTopBar: on a display taller than 16:9 the whole UI is pushed
     * down, which is what leaves the gradient showing below the controls.
     */
    val topInset: Dp = 0.dp,
    val settingsBarVisible: Boolean = false,
    val settingsBarEntries: List<SettingsBarEntry> = emptyList(),
    val flashValue: Int = 1,
    val timerIndex: Int = 0,
    val gridValue: Int = 0,
    val quadOn: Boolean = false,
    val eisOn: Boolean = false,
    val hdrxOn: Boolean = true,
    val fpsMode: Int = 0,
    val auxLenses: List<AuxLens> = emptyList(),
    val activeCameraId: String = "0",
    val shutterEnabled: Boolean = true,
    /** Unlimited/video use this to show "recording"; photo modes use it for the countdown. */
    val shutterActivated: Boolean = false,
    val counting: Boolean = false,
    val uiLocked: Boolean = false,
    val captureProgress: Float = 0f,
    val captureProgressAlpha: Float = 0f,
    val captureProgressVisible: Boolean = true,
    val processingProgress: Float = 0f,
    val processingIndeterminate: Boolean = false,
    val frameCount: String = "",
    val timerCount: String = "",
    val frameTimerVisible: Boolean = true,
    val galleryThumbnail: ImageBitmap? = null,
    val videoRecordingInfo: String? = null,
    /** Width/height the viewfinder box is pinned to, as layout_main_viewfinder's dummy view does. */
    val viewfinderAspect: Float = 3f / 4f,
    /**
     * The "Show Gradient Background" theme: res/color/gradient_color.xml is black up to
     * [gradientStart] of the height, then runs to the accent colour at [gradientEnd].
     * Both are fractions of the height, and the end is past the bottom edge, exactly as
     * the vector's 120-unit viewport put it.
     */
    val gradientBackground: Boolean = false,
    val gradientStart: Float = 50f / 120f,
    val gradientEnd: Float = 140f / 120f,
    val manualBarExpanded: Boolean = false,
    val manualBarAvailable: Boolean = true,
)
