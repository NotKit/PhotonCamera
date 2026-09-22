/* WHAT A TAP MEANS -- the port of CameraUIController.  -PwithApp only.
 *
 * ui/camera/CameraUIController.java and control/Swipe.java are both in
 * drop.txt: each one talks only to CameraFragment -- its captureController, its
 * cameraUiHost, its view models -- and there is no Fragment here.  What they
 * DECIDED is not View glue though, and this file is that, event for event, in
 * the order the Java wrote it:
 *
 *   CameraUiEvent -> a preference write, a CaptureController call, a call into
 *   the manual-mode console, or some of the three.
 *
 * Keep this file next to the Java when reading it.  Every branch below names
 * the Java method it came from, and where the port cannot do what the Java did
 * (there is no gallery activity) it says so and logs, rather than silently
 * doing nothing.  Swipe's manual-panel arm is here in full: ManualConsole.kt
 * builds the console the Views used to, so both gestures do what they did.
 */
package photoncam.screen

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.particlesdevs.photoncamera.R
import com.particlesdevs.photoncamera.api.CameraManager2
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.capture.CaptureController
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.SettingType
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel
import com.particlesdevs.photoncamera.control.CountdownTimer
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData
import com.particlesdevs.photoncamera.ui.camera.viewmodel.SettingsBarEntryProvider
import photoncam.host.HostWindow

/**
 * The camera screen's events, turned into camera actions.
 *
 * [rig] is asked for the CaptureController on every call rather than handed it
 * once: a restart replaces the session and the mode carousel can be tapped
 * while the camera is still coming up.
 */
internal class CameraActions(
	private val context: Context,
	private val host: CameraScreenHost,
	private val rig: CameraRig,
	/** CameraUIController.onEvent's `startActivity(SettingsActivity)`, which on
	 *  this port is the screen switch in [CameraScreenContent]. */
	private val onOpenSettings: () -> Unit,
) {
	private val entries = SettingsBarEntryProvider()
	private var countdown: CountdownTimer? = null

	/** CameraFragment.mCameraLensDataMap and its two anchors. */
	private var lensData: Map<String, CameraLensData> = emptyMap()
	private var activeBackId = "0"
	private var activeFrontId = "1"

	/** The mode the state was last built for; the window may change under it. */
	private var appliedMode: CameraMode? = null

	init {
		entries.createEntries()
		pushSettingsBarEntries()
		// The viewfinder box and the top bar's margin are both cut from the
		// window's dp size, and the first applyCameraMode runs against the
		// window Main opened rather than the one the shell hands back.
		HostWindow.onChange = { appliedMode?.let { applyCameraMode(it) } }
	}

	// -- CameraUIController.onEvent -----------------------------------------

	fun onEvent(event: CameraUiEvent) {
		when (event) {
			is CameraUiEvent.Shutter -> onShutter()
			is CameraUiEvent.OpenSettings -> onOpenSettings()
			is CameraUiEvent.OpenGallery ->
				log("OpenGallery: there is no gallery screen on this port yet")
			is CameraUiEvent.FlipCamera -> {
				setId(cycler(PreferenceKeys.getCameraID()))
				restartCamera()
			}
			is CameraUiEvent.SelectAux -> {
				setId(event.cameraId)
				restartCamera()
			}
			is CameraUiEvent.SelectMode ->
				onCameraModeChanged(CameraMode.valueOf(event.mode.ordinal))
			is CameraUiEvent.ToggleHdrx -> {
				applySetting(SettingType.HDRX, if (PreferenceKeys.isHdrXOn()) 0 else 1)
				log("HDRX: ${onOff(PreferenceKeys.isHdrXOn())}")
			}
			is CameraUiEvent.ToggleEis -> {
				applySetting(SettingType.EIS, if (PreferenceKeys.isEisPhotoOn()) 0 else 1)
				log("EIS: ${onOff(PreferenceKeys.isEisPhotoOn())}")
			}
			is CameraUiEvent.ToggleFps ->
				applySetting(SettingType.FPS_60, (PreferenceKeys.getFpsMode() + 1) % 4)
			is CameraUiEvent.ToggleQuad -> {
				applySetting(SettingType.QUAD, if (PreferenceKeys.isQuadBayerOn()) 0 else 1)
				log("Quad Bayer: ${onOff(PreferenceKeys.isQuadBayerOn())}")
			}
			is CameraUiEvent.ToggleGrid -> {
				val count = arrayLength(R.array.vf_grid_entryvalues, 5)
				applySetting(SettingType.GRID, (PreferenceKeys.getGridValue() + 1) % count)
			}
			is CameraUiEvent.ToggleFlash ->
				applySetting(SettingType.FLASH, (PreferenceKeys.getAeMode() + 1) % 2)
			is CameraUiEvent.ToggleTimer -> {
				val count = timerValues().size.coerceAtLeast(1)
				applySetting(SettingType.TIMER, (PreferenceKeys.getCountdownTimerIndex() + 1) % count)
			}
			is CameraUiEvent.SetSetting -> applySetting(event.type, event.value)
			is CameraUiEvent.SetSettingsBarVisible -> host.setSettingsBarVisible(event.visible)
			is CameraUiEvent.ToggleManualBar ->
				if (rig.manualConsole?.isPanelVisible() == true) swipeDown() else swipeUp()
			is CameraUiEvent.SwipeUp -> swipeUp()
			is CameraUiEvent.SwipeDown -> swipeDown()
			// ManualModeModel's ParamClickListener, which the console installed.
			is CameraUiEvent.SelectManualParam ->
				rig.manualUi.clicks?.onParamClicked(event.param.toConsole())
			is CameraUiEvent.ResetManualParam ->
				rig.manualUi.clicks?.onParamLongClicked(event.param.toConsole())
			is CameraUiEvent.ManualKnobTick -> rig.manualUi.setTick(event.tick)
			// Swipe.onTap: the bar goes away and the tap focuses where it landed
			// -- but only while the focus knob is on AUTO, or a tap would undo
			// the distance the knob was just set to.
			is CameraUiEvent.ViewfinderTap -> {
				host.setSettingsBarVisible(false)
				if (manualFocusValue() == ManualParamModel.FOCUS_AUTO)
					rig.touchFocus?.processTouchToFocus(event.x, event.y)
			}
			is CameraUiEvent.ViewfinderLongPress ->
				rig.touchFocus?.processSpotWb(event.x, event.y)
			else -> log("unhandled $event")
		}
	}

	// -- Swipe.SwipeUp / Swipe.SwipeDown -------------------------------------

	private fun swipeUp() {
		// The settings bar is in front of the panel; the first swipe only closes it.
		if (host.state.settingsBarVisible) {
			host.setSettingsBarVisible(false)
			return
		}
		rig.manualConsole?.setPanelVisibility(true)
		host.setManualBarExpanded(true)
		rig.touchFocus?.resetFocusCircle()
	}

	private fun swipeDown() {
		val console = rig.manualConsole
		if (console != null && console.isPanelVisible()) {
			rig.touchFocus?.resetFocusCircle()
			rig.controller?.reset3Aparams()
			console.setPanelVisibility(false)
			console.retractAllKnobs()
			host.setManualBarExpanded(false)
		} else {
			host.setSettingsBarVisible(true)
		}
	}

	private fun manualFocusValue(): Double =
		rig.manualConsole?.getManualParamModel()?.getCurrentFocusValue()
			?: ManualParamModel.FOCUS_AUTO

	// -- CameraUIController.onShutter ---------------------------------------

	private fun onShutter() {
		val controller = rig.controller ?: run { log("shutter: no camera"); return }
		when (PhotonCamera.getSettings()?.selectedMode) {
			CameraMode.PHOTO, CameraMode.MOTION, CameraMode.NIGHT ->
				if (countdown != null) resetTimer() else startTimer()
			CameraMode.UNLIMITED, CameraMode.RAWVIDEO ->
				if (!controller.onUnlimited) {
					controller.callUnlimitedStart()
					host.setShutterActivated(false)
				} else {
					controller.callUnlimitedEnd()
					host.setShutterActivated(true)
				}
			CameraMode.VIDEO ->
				if (!controller.mIsRecordingVideo) {
					controller.VideoStart()
					host.setShutterActivated(false)
				} else {
					controller.VideoEnd()
					host.setShutterActivated(true)
				}
			else -> log("shutter: no mode selected")
		}
	}

	/**
	 * CameraUIController.applySetting: the one place a setting is written and
	 * the camera told about it, whether the value came from a top-bar tap or a
	 * settings-bar button.
	 */
	private fun applySetting(type: SettingType, value: Int) {
		val controller = rig.controller
		when (type) {
			SettingType.FLASH -> {
				PreferenceKeys.setAeMode(value)
				controller?.setPreviewAEModeRebuild(value)
			}
			SettingType.HDRX -> {
				PreferenceKeys.setHdrX(value == 1)
				CaptureController.setTargetFormat(
					if (value == 1) CaptureController.RAW_FORMAT else CaptureController.YUV_FORMAT,
				)
				restartCamera()
			}
			SettingType.QUAD -> {
				PreferenceKeys.setQuadBayer(value == 1)
				restartCamera()
			}
			// invalidateSurfaceView() redrew the grid's own View; the grid is
			// drawn by the Compose screen from state.gridValue, which
			// syncFromPreferences below picks up.
			SettingType.GRID -> PreferenceKeys.setGridValue(value)
			SettingType.FPS_60 -> {
				PreferenceKeys.setFpsMode(value)
				controller?.applyFpsRange()
			}
			SettingType.TIMER -> PreferenceKeys.setCountdownTimerIndex(value)
			SettingType.EIS -> PreferenceKeys.setEisPhoto(value == 1)
			SettingType.RAW -> PreferenceKeys.setSaveRaw(value)
			SettingType.BATTERY_SAVER -> PreferenceKeys.setBatterySaver(value == 1)
			SettingType.BRACKETING -> {
				PreferenceKeys.setBracketingMode(value)
				IsoExpoSelector.HDR = value > 0
			}
			SettingType.AE_METERING_STD -> {
				PreferenceKeys.setAeMeteringStd(value)
				controller?.applyAeMetering()
			}
		}
		updateSettingsBar()
	}

	// -- the timer ----------------------------------------------------------

	private fun timerValues(): IntArray =
		runCatching { context.getResources()!!.getIntArray(R.array.countdowntimer_entryvalues) }
			.getOrDefault(intArrayOf(0))

	private fun startTimer() {
		host.setCounting(true)
		val values = timerValues()
		val seconds = values.getOrElse(PreferenceKeys.getCountdownTimerIndex()) { 0 }
		val timer = CountdownTimer(
			{ text -> host.setTimerCount(text) },
			seconds * 1000L, 1000L,
		) { onTimerFinished() }
		countdown = timer
		timer.start()
	}

	private fun resetTimer() {
		countdown?.cancel()
		countdown = null
		host.setCounting(false)
	}

	private fun onTimerFinished() {
		countdown = null
		host.setCounting(false)
		host.activateShutterButton(false)
		rig.controller?.takePicture()
	}

	// -- CameraUIController.onCameraModeChanged ------------------------------

	private fun onCameraModeChanged(mode: CameraMode?) {
		val m = mode ?: return
		PreferenceKeys.setCameraModeOrdinal(m.ordinal)
		log("onCameraModeChanged() called with: cameraMode = [$m]")
		applyCameraMode(m)
		restartCamera()
	}

	/** CameraFragment.applyCameraMode. */
	fun applyCameraMode(mode: CameraMode) {
		appliedMode = mode
		val w = HostWindow.widthDp
		val h = HostWindow.heightDp
		host.applyMode(
			mode, host.enableQuadRes, if (w > 0f) h / w else 16f / 9f, w, h,
			HostWindow.density,
		)
		pushSettingsBarEntries()
	}

	// -- CameraFragment's settings bar ---------------------------------------

	private fun pushSettingsBarEntries() {
		host.setSettingsBarEntries(entries.getAllEntries(), PreferenceKeys.isQuadBayerOn())
	}

	/** CameraFragment.updateSettingsBar. */
	fun updateSettingsBar() {
		entries.updateAllEntries()
		pushSettingsBarEntries()
		host.refresh(CaptureController.isProcessing)
	}

	// -- CameraFragment's camera ids -----------------------------------------

	/**
	 * CameraFragment.initCameraIDLists, plus the aux list the AuxButtonsViewModel
	 * pushed.  Called from the camera-opened event, as on Android.
	 */
	fun initCameraIdLists(cameraManager: CameraManager?) {
		val manager = cameraManager ?: return
		val map = runCatching {
			CameraManager2(manager, PhotonCamera.getSettingsManagerStatic()).getCameraLensDataMap()
		}.onFailure { log("camera lens data failed: $it") }.getOrNull() ?: return
		lensData = map
		// Re-anchor the two ids to cameras that exist: "0"/"1" are static
		// defaults and not every device has them.
		if (!map.containsKey(activeBackId))
			map.entries.firstOrNull { it.value.facing == CameraCharacteristics.LENS_FACING_BACK }
				?.let { activeBackId = it.key }
		if (!map.containsKey(activeFrontId))
			map.entries.firstOrNull { it.value.facing == CameraCharacteristics.LENS_FACING_FRONT }
				?.let { activeFrontId = it.key }
		pushAuxLenses()
	}

	/** CameraFragment.pushAuxLenses: the front or back list, by which holds the active id. */
	private fun pushAuxLenses() {
		val activeId = PreferenceKeys.getCameraID() ?: activeBackId
		val front = lensData.values
			.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
			.sortedByDescending { it.zoomFactor }
		val back = lensData.values
			.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
			.sortedByDescending { it.zoomFactor }
		val isFront = front.any { it.cameraId == activeId }
		host.setAuxLenses(if (isFront) front else back, activeId)
	}

	/** CameraFragment.cycler. */
	private fun cycler(savedCameraID: String?): String {
		val saved = savedCameraID ?: activeBackId
		return if (lensData[saved]?.facing == CameraCharacteristics.LENS_FACING_BACK) {
			activeBackId = saved
			activeFrontId
		} else {
			activeFrontId = saved
			activeBackId
		}
	}

	private fun setId(input: String?) {
		PreferenceKeys.setCameraID(input.toString())
	}

	private fun restartCamera() {
		resetTimer()
		val controller = rig.controller ?: run { log("restart: no camera"); return }
		runCatching { controller.restartCamera() }
			.onFailure { e ->
				// WITH THE TRACE.  A half-finished restart leaves the camera
				// closed and the next one blocks on mCameraOpenCloseLock, so
				// the message alone names the wrong failure.
				log("restartCamera failed: $e")
				e.stackTraceToString().lines().drop(1).take(14).forEach { println("    $it") }
			}
		pushAuxLenses()
	}

	private fun arrayLength(id: Int, fallback: Int): Int =
		runCatching { context.getResources()!!.getStringArray(id).size }
			.getOrDefault(fallback)
			.coerceAtLeast(1)

	private fun onOff(value: Boolean): String = if (value) "On" else "Off"

	private fun log(message: String) = println("[pc] actions: $message")
}
