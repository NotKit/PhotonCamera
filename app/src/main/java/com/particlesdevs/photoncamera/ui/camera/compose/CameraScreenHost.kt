package com.particlesdevs.photoncamera.ui.camera.compose

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.particlesdevs.photoncamera.R
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.composeui.camera.CameraScreen
import com.particlesdevs.photoncamera.composeui.state.AuxLens
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.camera.CameraIcons
import com.particlesdevs.photoncamera.composeui.state.SettingsBarEntry
import com.particlesdevs.photoncamera.composeui.state.SettingsBarOption
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel
import com.particlesdevs.photoncamera.composeui.state.SettingType as UiSettingType
import com.particlesdevs.photoncamera.composeui.state.TopBarState
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.settings.TunableInjector
import com.particlesdevs.photoncamera.settings.annotations.Tunable
import com.particlesdevs.photoncamera.ui.camera.CameraUIView
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData
import java.util.Locale
import com.particlesdevs.photoncamera.composeui.state.CameraMode as UiCameraMode

/**
 * Drives the Compose camera screen from the app's existing camera plumbing.
 *
 * It is the CameraUIView the capture pipeline already talks to, so CaptureController
 * and the processing callbacks are unchanged; what used to be a call into a View is
 * now a state update that the screen recomposes from.
 */
class CameraScreenHost(private val context: Context) : CameraUIView {

    var state by mutableStateOf(CameraUiState(modeLabels = modeLabels(context)))
        private set

    /** The inflated viewfinder_stack.xml; the screen shows it in its viewfinder slot. */
    @SuppressLint("InflateParams")
    val viewfinderStack: View =
        LayoutInflater.from(context).inflate(R.layout.viewfinder_stack, null, false)

    /** manual_palette.xml, which circularbarlib finds again through the activity. */
    @SuppressLint("InflateParams")
    val manualPalette: View =
        LayoutInflater.from(context).inflate(R.layout.manual_palette, null, false)

    /** Java-friendly so CameraUIController can pass a method reference. */
    fun interface EventListener {
        fun onEvent(event: CameraUiEvent)
    }

    @Tunable(
        title = "Enable Quad Resolution",
        description = "Show Quad Resolution toggle in camera controls. When off, Quad Res is forced disabled.",
        category = "UI",
        min = 0.0f,
        max = 1.0f,
        defaultValue = 0.0f,
        step = 1.0f,
    )
    @JvmField
    var enableQuadRes: Boolean = false

    private var eventsListener: EventListener? = null
    private var captureMax = 100
    private var captureValue = 0

    fun setEventListener(listener: EventListener) {
        eventsListener = listener
    }

    fun createView(): View = ComposeView(context).apply {
        setContent {
            PhotonTheme {
                CameraScreen(
                    state = state,
                    onEvent = { eventsListener?.onEvent(it) },
                    modifier = Modifier.fillMaxSize(),
                    viewfinder = {
                        AndroidView(
                            factory = {
                                viewfinderStack.also { (it.parent as? ViewGroup)?.removeView(it) }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    manualBar = {
                        AndroidView(
                            factory = {
                                manualPalette.also { (it.parent as? ViewGroup)?.removeView(it) }
                            },
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        )
                    },
                )
            }
        }
    }

    fun update(block: (CameraUiState) -> CameraUiState) {
        state = block(state)
    }

    // ── state the fragment and its view models push in ──────────────────────

    fun setOrientation(orientation: Int) = update { it.copy(orientation = orientation) }

    fun setGalleryThumbnail(bitmap: Bitmap?) =
        update { it.copy(galleryThumbnail = bitmap?.asImageBitmap()) }

    fun setSettingsBarVisible(visible: Boolean) = update { it.copy(settingsBarVisible = visible) }

    fun setManualBarExpanded(expanded: Boolean) = update { it.copy(manualBarExpanded = expanded) }

    fun setFrameCount(text: String?) = update { it.copy(frameCount = text.orEmpty()) }

    fun setTimerCount(text: String?) = update { it.copy(timerCount = text.orEmpty()) }

    fun setCounting(counting: Boolean) = update { it.copy(counting = counting) }

    fun setShutterActivated(activated: Boolean) = update { it.copy(shutterActivated = activated) }

    fun setViewfinderAspect(aspect: Float) = update { it.copy(viewfinderAspect = aspect) }

    /**
     * Maps SettingsBarEntryProvider's models onto the screen's own entries, including
     * the per-mode visibility that CameraUIViewImpl applied with setChildVisibility.
     */
    fun setSettingsBarEntries(models: List<SettingsBarEntryModel>, quadResEnabled: Boolean) {
        val mode = state.mode
        val entries = models.mapNotNull { model ->
            val type = runCatching { UiSettingType.valueOf(model.type.name) }.getOrNull()
                ?: return@mapNotNull null
            val buttons = model.settingsBarButtonModels ?: return@mapNotNull null
            SettingsBarEntry(
                type = type,
                title = context.getString(model.titleStringId),
                options = buttons.map { button ->
                    SettingsBarOption(
                        value = button.buttonValue,
                        label = context.getString(button.buttonStateNameStringId),
                        icon = CameraIcons.forOption(type, button.buttonValue),
                    )
                },
                selectedValue = buttons.firstOrNull { it.isSelected }?.buttonValue ?: 0,
                visible = entryVisible(type, mode, quadResEnabled),
            )
        }
        update { it.copy(settingsBarEntries = entries) }
    }

    private fun entryVisible(
        type: UiSettingType,
        mode: UiCameraMode,
        quadResEnabled: Boolean,
    ): Boolean = when (type) {
        UiSettingType.QUAD -> quadResEnabled
        UiSettingType.HDRX -> false
        UiSettingType.FLASH -> state.topBar.flashVisible
        UiSettingType.TIMER -> mode == UiCameraMode.PHOTO || mode == UiCameraMode.MOTION ||
                mode == UiCameraMode.NIGHT
        UiSettingType.EIS -> mode == UiCameraMode.PHOTO || mode == UiCameraMode.MOTION
        UiSettingType.FPS_60 -> mode != UiCameraMode.NIGHT
        else -> true
    }

    fun setAuxLenses(lenses: List<CameraLensData>, activeId: String) = update {
        it.copy(
            auxLenses = lenses.map { lens -> AuxLens(lens.cameraId, auxLabel(lens.zoomFactor)) },
            activeCameraId = activeId,
        )
    }

    /** Mirrors the preference reads the data-binding layouts did on every invalidate. */
    fun syncFromPreferences() {
        TunableInjector.inject(this)
        if (!enableQuadRes) PreferenceKeys.setQuadBayer(false)
        update {
        it.copy(
            mode = UiCameraMode.entries[PreferenceKeys.getCameraModeOrdinal()
                .coerceIn(0, UiCameraMode.entries.lastIndex)],
            flashValue = PreferenceKeys.getAeMode(),
            timerIndex = PreferenceKeys.getCountdownTimerIndex(),
            gridValue = PreferenceKeys.getGridValue(),
            quadOn = PreferenceKeys.isQuadBayerOn(),
            eisOn = PreferenceKeys.isEisPhotoOn(),
            hdrxOn = PreferenceKeys.isHdrXOn(),
            fpsMode = PreferenceKeys.getFpsMode(),
            gradientBackground = PreferenceKeys.isShowGradientOn(),
            topBar = it.topBar.copy(quadVisible = enableQuadRes),
        )
        }
    }

    /**
     * What the four CameraModeState classes did: pick which top-bar toggles a mode
     * shows and how the viewfinder box is proportioned.
     */
    fun applyMode(mode: CameraMode, quadResEnabled: Boolean, displayAspectRatio: Float) {
        val uiMode = UiCameraMode.entries[mode.ordinal]
        val video = mode == CameraMode.VIDEO
        val continuous = video || mode == CameraMode.UNLIMITED || mode == CameraMode.RAWVIDEO
        val wide = video || (PhotonCamera.getSettings().aspect169 && mode != CameraMode.RAWVIDEO) ||
                mode == CameraMode.RAWVIDEO
        // "3:4" in the layout meant a portrait box; the 16:9 modes use the average of
        // 4:3 and 16:9 on tall displays, exactly as CameraUIViewImpl computed it.
        val aspect = when {
            !wide -> 3f / 4f
            displayAspectRatio <= 16f / 9f -> 3f / 4f
            else -> 1f / (((4f / 3f) + (16f / 9f)) / 2f)
        }
        // CustomBinding.adjustTopBar, verbatim: on a display taller than 16:9 the top
        // bar gets a margin of (dpHeight - dpWidth * 16/9). Upstream assigns that dp
        // figure straight into a pixel margin, and matching the View layout means
        // keeping that, so the number is turned back into Dp here.
        val metrics = context.resources.displayMetrics
        val marginPx = if (displayAspectRatio > 16f / 9f) {
            val dpHeight = metrics.heightPixels / metrics.density
            val dpWidth = metrics.widthPixels / metrics.density
            (dpHeight - dpWidth / 9f * 16f).toInt()
        } else 0
        val topInset = (marginPx / metrics.density).dp

        update {
            it.copy(
                mode = uiMode,
                topInset = topInset,
                viewfinderAspect = aspect,
                gradientStart = if (wide) 70f / 120f else 50f / 120f,
                frameTimerVisible = mode != CameraMode.RAWVIDEO,
                captureProgressVisible = mode != CameraMode.RAWVIDEO,
                // Continuous modes start idle, which unlimitedbutton drew as the play triangle.
                shutterActivated = if (continuous) true else it.shutterActivated,
                topBar = TopBarState(
                    timerVisible = !continuous,
                    quadVisible = quadResEnabled,
                    flashVisible = it.topBar.flashVisible,
                    eisVisible = mode == CameraMode.VIDEO || mode == CameraMode.PHOTO ||
                            mode == CameraMode.MOTION,
                    fpsVisible = mode != CameraMode.NIGHT,
                    hdrxVisible = false,
                    settingsVisible = false,
                ),
            )
        }
    }

    // ── CameraUIView ───────────────────────────────────────────────────────

    override fun activateShutterButton(status: Boolean) =
        update { it.copy(shutterEnabled = status, shutterActivated = status) }

    override fun refresh(processing: Boolean) {
        syncFromPreferences()
        resetCaptureProgressBar()
        if (!processing) {
            activateShutterButton(true)
            setProcessingProgressBarIndeterminate(false)
            lockUIForBurst(false)
        }
    }

    override fun setProcessingProgressBarIndeterminate(indeterminate: Boolean) =
        update { it.copy(processingIndeterminate = indeterminate) }

    override fun resetCaptureProgressBar() {
        captureValue = 0
        update { it.copy(captureProgress = 0f, captureProgressAlpha = 0f) }
    }

    override fun incrementCaptureProgressBar(step: Int) {
        captureValue += step
        update { it.copy(captureProgress = captureValue.toFloat() / captureMax.coerceAtLeast(1)) }
    }

    override fun setCaptureProgressBarOpacity(alpha: Float) =
        update { it.copy(captureProgressAlpha = alpha) }

    override fun setCaptureProgressMax(max: Int) {
        captureMax = max.coerceAtLeast(1)
    }

    override fun showFlashButton(flashAvailable: Boolean) =
        update { it.copy(topBar = it.topBar.copy(flashVisible = flashAvailable)) }

    override fun lockUIForBurst(locked: Boolean) = update { it.copy(uiLocked = locked) }

    @SuppressLint("DefaultLocale")
    override fun updateVideoRecordingInfo(elapsedMs: Long, estimatedBytes: Long, availableBytes: Long) {
        val totalSeconds = elapsedMs / 1000
        val text = String.format(
            Locale.US, "%02d:%02d  %.2f/%.1f GB",
            totalSeconds / 60, totalSeconds % 60,
            estimatedBytes / 1_073_741_824.0, availableBytes / 1_073_741_824.0,
        )
        update { it.copy(videoRecordingInfo = text) }
    }

    override fun setVideoRecordingInfoVisible(visible: Boolean) =
        update { it.copy(videoRecordingInfo = if (visible) it.videoRecordingInfo.orEmpty() else null) }

    override fun destroy() {
        eventsListener = null
    }

    private companion object {
        fun modeLabels(context: Context): List<String> =
            CameraMode.nameIds().map { context.getString(it) }

        /** AuxButtonsLayout.getAuxButtonName. */
        fun auxLabel(zoomFactor: Float): String =
            String.format(Locale.US, "%.1fx", zoomFactor - 0.049).replace(".0", "")
    }
}
