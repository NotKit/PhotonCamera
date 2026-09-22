/* THE K/N CameraScreenHost.
 *
 * The Android original is app/src/main/java/com/particlesdevs/photoncamera/ui/
 * camera/compose/CameraScreenHost.kt (331 lines).  It is a .kt, so convert.sh
 * never sees it and gen/ has no copy: this file is the port, and it keeps the
 * ORIGINAL'S PACKAGE AND NAME on purpose -- gen/settings/TunableRegistry.kt:22
 * names `ui.camera.compose.CameraScreenHost::class.java` in TUNABLE_CLASSES,
 * and with no such class in the tree that line does not resolve.
 *
 * WHAT THE PORT DROPS, and why each one is a decision and not an omission:
 *   - `viewfinderStack` / `manualPalette` / `createView()`.  All three are
 *     LayoutInflater + ComposeView + AndroidView, and there is no View layer
 *     here at all.  The window composes CameraScreen itself (photoncam.screen)
 *     and passes the viewfinder slot, which is still a placeholder this round.
 *   - `CameraUIView`.  drop.txt drops ui/camera/CameraUIView.java, so the
 *     methods that implemented it are plain methods with the same names and
 *     signatures; whatever ends up calling the capture pipeline back can call
 *     them unchanged.
 *   - `Bitmap.asImageBitmap()`.  The gallery's decode is not this lane's, so
 *     the thumbnail is taken as the ImageBitmap the screen actually wants.
 *   - `context.resources.displayMetrics`.  The shim's DisplayMetrics is empty
 *     (android/util/AndroidUtil.kt:132) and on this port the WINDOW is what
 *     knows the panel, so applyMode takes the figures instead of asking a
 *     Resources that cannot answer.
 *
 * Everything else -- the preference sync, the per-mode top bar, the aspect and
 * gradient arithmetic, the settings-bar mapping, the capture-progress
 * bookkeeping -- is the original's, line for line.
 */
package com.particlesdevs.photoncamera.ui.camera.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import android.content.Context
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.composeui.camera.CameraIcons
import com.particlesdevs.photoncamera.composeui.state.AuxLens
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.state.ManualBarState
import com.particlesdevs.photoncamera.composeui.state.ManualKnobState
import com.particlesdevs.photoncamera.composeui.state.ManualParam
import com.particlesdevs.photoncamera.composeui.state.SettingsBarEntry
import com.particlesdevs.photoncamera.composeui.state.SettingsBarOption
import com.particlesdevs.photoncamera.composeui.state.TopBarState
import com.particlesdevs.photoncamera.composeui.state.CameraMode as UiCameraMode
import com.particlesdevs.photoncamera.composeui.state.SettingType as UiSettingType
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.settings.TunableInjector
import com.particlesdevs.photoncamera.settings.annotations.Tunable
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel

/**
 * Drives the Compose camera screen from the app's existing camera plumbing:
 * what used to be a call into a View is a state update the screen recomposes
 * from.
 */
class CameraScreenHost(private val context: Context) {

	var state by mutableStateOf(CameraUiState(modeLabels = modeLabels(context)))
		private set

	/**
	 * The manual-mode console, which the Android host keeps in the inflated
	 * manual_palette.xml instead.  It is its own state because the console owns
	 * it: ManualUiKn writes here on every KnobModel/ManualModeModel change.
	 */
	var manualBar by mutableStateOf(ManualBarState())
		private set

	/** Java-friendly, so a converted caller can pass a method reference. */
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
	var enableQuadRes: Boolean = false

	private var eventsListener: EventListener? = null
	private var captureMax = 100
	private var captureValue = 0

	fun setEventListener(listener: EventListener?) {
		eventsListener = listener
	}

	/** What the screen's `onEvent` is wired to. */
	fun onEvent(event: CameraUiEvent) {
		eventsListener?.onEvent(event)
	}

	fun update(block: (CameraUiState) -> CameraUiState) {
		state = block(state)
	}

	// -- state the host pushes in -------------------------------------------

	fun setOrientation(orientation: Int) {
		update { it.copy(orientation = orientation) }
		updateManualBar { it.copy(orientation = orientation) }
	}

	fun setGalleryThumbnail(bitmap: ImageBitmap?) = update { it.copy(galleryThumbnail = bitmap) }

	fun setSettingsBarVisible(visible: Boolean) = update { it.copy(settingsBarVisible = visible) }

	fun setManualBarExpanded(expanded: Boolean) = update { it.copy(manualBarExpanded = expanded) }

	// -- the manual console's, one per ManualModeModel/KnobModel field --------

	fun updateManualBar(block: (ManualBarState) -> ManualBarState) {
		manualBar = block(manualBar)
	}

	fun setManualPanelVisible(visible: Boolean) = updateManualBar { it.copy(visible = visible) }

	fun setManualKnobVisible(visible: Boolean) = updateManualBar { it.copy(knobVisible = visible) }

	fun setManualKnob(knob: ManualKnobState) = updateManualBar { it.copy(knob = knob) }

	fun setManualSelectedParam(param: ManualParam?) = updateManualBar { it.copy(selected = param) }

	fun setManualValueText(param: ManualParam, text: String?) = updateManualBar {
		it.copy(values = it.values + (param to text.orEmpty()))
	}

	fun setFrameCount(text: String?) = update { it.copy(frameCount = text.orEmpty()) }

	fun setTimerCount(text: String?) = update { it.copy(timerCount = text.orEmpty()) }

	fun setCounting(counting: Boolean) = update { it.copy(counting = counting) }

	fun setShutterActivated(activated: Boolean) = update { it.copy(shutterActivated = activated) }

	fun setViewfinderAspect(aspect: Float) = update { it.copy(viewfinderAspect = aspect) }

	/**
	 * Maps SettingsBarEntryProvider's models onto the screen's own entries,
	 * including the per-mode visibility CameraUIViewImpl applied with
	 * setChildVisibility.
	 */
	fun setSettingsBarEntries(models: List<SettingsBarEntryModel?>?, quadResEnabled: Boolean) {
		val mode = state.mode
		val entries = (models ?: emptyList()).mapNotNull { model ->
			if (model == null) return@mapNotNull null
			// `type` converts as Enum<SettingType>, not SettingType, so the name
			// is what crosses to the UI enum -- which is what the Android host
			// does too, the two enums being separate declarations.
			val typeName = model.type?.name ?: return@mapNotNull null
			val type = runCatching { UiSettingType.valueOf(typeName) }.getOrNull()
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
				selectedValue = buttons.firstOrNull { it.selected }?.buttonValue ?: 0,
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

	fun setAuxLenses(lenses: List<CameraLensData?>?, activeId: String) = update {
		it.copy(
			auxLenses = (lenses ?: emptyList()).mapNotNull { lens ->
				val id = lens?.cameraId ?: return@mapNotNull null
				AuxLens(id, auxLabel(lens.zoomFactor))
			},
			activeCameraId = activeId,
		)
	}

	/** Mirrors the preference reads the data-binding layouts did on every invalidate. */
	fun syncFromPreferences() {
		TunableInjector.inject(this)
		if (!enableQuadRes) PreferenceKeys.setQuadBayer(false)
		update {
			it.copy(
				mode = UiCameraMode.entries[
					PreferenceKeys.getCameraModeOrdinal().coerceIn(0, UiCameraMode.entries.lastIndex)
				],
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
	 * What the four CameraModeState classes did: which top-bar toggles a mode
	 * shows, and how the viewfinder box is proportioned.
	 *
	 * [dpWidth] and [dpHeight] are the WINDOW's, in dp.  On Android they came
	 * off DisplayMetrics; here the window is the only thing that knows the
	 * panel, so it says.
	 */
	fun applyMode(
		mode: CameraMode,
		quadResEnabled: Boolean,
		displayAspectRatio: Float,
		dpWidth: Float,
		dpHeight: Float,
	) {
		val uiMode = UiCameraMode.entries[mode.ordinal]
		val video = mode == CameraMode.VIDEO
		val continuous = video || mode == CameraMode.UNLIMITED || mode == CameraMode.RAWVIDEO
		val wide = video || (PhotonCamera.getSettings()?.aspect169 == true && mode != CameraMode.RAWVIDEO) ||
			mode == CameraMode.RAWVIDEO
		// "3:4" in the layout meant a portrait box; the 16:9 modes use the
		// average of 4:3 and 16:9 on tall displays, as CameraUIViewImpl did.
		val aspect = when {
			!wide -> 3f / 4f
			displayAspectRatio <= 16f / 9f -> 3f / 4f
			else -> 1f / (((4f / 3f) + (16f / 9f)) / 2f)
		}
		// CustomBinding.adjustTopBar: on a display taller than 16:9 the top bar
		// gets a margin of (dpHeight - dpWidth * 16/9).  Upstream assigns that
		// dp figure straight into a PIXEL margin and matching the View layout
		// means keeping that, so it is divided back out here.
		val topInset = if (displayAspectRatio > 16f / 9f) {
			((dpHeight - dpWidth / 9f * 16f).toInt() / maxOf(1f, dpHeight / 160f)).dp
		} else 0.dp

		update {
			it.copy(
				mode = uiMode,
				topInset = topInset,
				viewfinderAspect = aspect,
				gradientStart = if (wide) 70f / 120f else 50f / 120f,
				frameTimerVisible = mode != CameraMode.RAWVIDEO,
				captureProgressVisible = mode != CameraMode.RAWVIDEO,
				// Continuous modes start idle, which unlimitedbutton drew as the
				// play triangle.
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

	// -- what CameraUIView was ----------------------------------------------

	fun activateShutterButton(status: Boolean) =
		update { it.copy(shutterEnabled = status, shutterActivated = status) }

	fun refresh(processing: Boolean) {
		syncFromPreferences()
		resetCaptureProgressBar()
		if (!processing) {
			activateShutterButton(true)
			setProcessingProgressBarIndeterminate(false)
			lockUIForBurst(false)
		}
	}

	fun setProcessingProgressBarIndeterminate(indeterminate: Boolean) =
		update { it.copy(processingIndeterminate = indeterminate) }

	fun resetCaptureProgressBar() {
		captureValue = 0
		update { it.copy(captureProgress = 0f, captureProgressAlpha = 0f) }
	}

	fun incrementCaptureProgressBar(step: Int) {
		captureValue += step
		update { it.copy(captureProgress = captureValue.toFloat() / captureMax.coerceAtLeast(1)) }
	}

	fun setCaptureProgressBarOpacity(alpha: Float) =
		update { it.copy(captureProgressAlpha = alpha) }

	fun setCaptureProgressMax(max: Int) {
		captureMax = max.coerceAtLeast(1)
	}

	fun showFlashButton(flashAvailable: Boolean) =
		update { it.copy(topBar = it.topBar.copy(flashVisible = flashAvailable)) }

	fun lockUIForBurst(locked: Boolean) = update { it.copy(uiLocked = locked) }

	fun updateVideoRecordingInfo(elapsedMs: Long, estimatedBytes: Long, availableBytes: Long) {
		val totalSeconds = elapsedMs / 1000
		// String.format("%02d:%02d  %.2f/%.1f GB") without a Formatter: K/N has
		// no java.util.Locale-aware format, and the two figures are fixed-width
		// by hand so the line does not jump about while recording.
		val text = "${two(totalSeconds / 60)}:${two(totalSeconds % 60)}  " +
			"${gb(estimatedBytes, 2)}/${gb(availableBytes, 1)} GB"
		update { it.copy(videoRecordingInfo = text) }
	}

	fun setVideoRecordingInfoVisible(visible: Boolean) =
		update { it.copy(videoRecordingInfo = if (visible) it.videoRecordingInfo.orEmpty() else null) }

	fun destroy() {
		eventsListener = null
	}

	private companion object {
		fun two(v: Long): String = if (v < 10) "0$v" else "$v"

		fun gb(bytes: Long, decimals: Int): String {
			val scale = if (decimals == 1) 10 else 100
			val v = (bytes.toDouble() / 1_073_741_824.0 * scale).toLong()
			return "${v / scale}.${(v % scale).toString().padStart(decimals, '0')}"
		}

		fun modeLabels(context: Context): List<String> =
			(CameraMode.nameIds() ?: IntArray(0)).map { context.getString(it) }

		/** AuxButtonsLayout.getAuxButtonName. */
		fun auxLabel(zoomFactor: Float): String {
			val v = ((zoomFactor - 0.049f) * 10f).toInt()
			return "${v / 10}.${v % 10}x".replace(".0", "")
		}
	}
}
