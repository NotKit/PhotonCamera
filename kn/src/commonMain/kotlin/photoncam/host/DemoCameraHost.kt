package photoncam.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.camera.CameraIcons
import com.particlesdevs.photoncamera.composeui.state.AuxLens
import com.particlesdevs.photoncamera.composeui.state.CameraMode
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.composeui.state.CameraUiState
import com.particlesdevs.photoncamera.composeui.state.SettingType
import com.particlesdevs.photoncamera.composeui.state.SettingsBarEntry
import com.particlesdevs.photoncamera.composeui.state.SettingsBarOption
import com.particlesdevs.photoncamera.composeui.state.TopBarState

/**
 * The camera screen's state WITHOUT a camera, for milestone 2.
 *
 * `CameraUiState` is a plain immutable value the host replaces wholesale, so the
 * screen can be driven from anywhere -- and here it is driven from a table of
 * plausible values and a `when` over the events, which is enough to prove that
 * the Compose tree lays out, draws and routes a press on Kotlin/Native.
 *
 * What replaces it in round 2 is the converted `CameraFragment`/`CameraUIController`,
 * behind `-PwithApp`; nothing in `ui-compose` has to change for that, because
 * this file already speaks the same two types the real host will.
 */
object DemoCameraHost {

	/** The order is CameraMode's, because ModeSwitcher indexes by ordinal. */
	val MODE_LABELS = listOf("UNLIM", "RAW VID", "MOTION", "PHOTO", "NIGHT", "VIDEO")

	private val AUX = listOf(
		AuxLens("2", "0.6"),
		AuxLens("0", "1x"),
		AuxLens("3", "2x"),
	)

	fun initial(): CameraUiState = withSettingsBar(
		CameraUiState(
			mode = CameraMode.PHOTO,
			modeLabels = MODE_LABELS,
			topBar = TopBarState(
				timerVisible = true,
				quadVisible = true,
				flashVisible = true,
				eisVisible = false,
				fpsVisible = false,
				hdrxVisible = true,
				settingsVisible = true,
			),
			auxLenses = AUX,
			activeCameraId = "0",
			// 4:3 sensor held upright: the viewfinder box is taller than it is wide.
			viewfinderAspect = 3f / 4f,
			gradientBackground = true,
			frameTimerVisible = false,
			captureProgressVisible = false,
			manualBarAvailable = true,
		)
	)

	/**
	 * The one-way step a real host does in its view model.  Every event is
	 * handled, so an unhandled one is a compile error rather than a shrug.
	 */
	fun reduce(s: CameraUiState, e: CameraUiEvent): CameraUiState = withSettingsBar(
		when (e) {
			is CameraUiEvent.Shutter -> s.copy(
				shutterActivated = !s.shutterActivated,
				frameCount = if (s.shutterActivated) "" else "3",
			)
			is CameraUiEvent.FlipCamera -> s.copy(activeCameraId = if (s.activeCameraId == "0") "1" else "0")
			is CameraUiEvent.ToggleFlash -> s.copy(flashValue = (s.flashValue + 1) % 4)
			is CameraUiEvent.ToggleTimer -> s.copy(timerIndex = (s.timerIndex + 1) % 3)
			is CameraUiEvent.ToggleGrid -> s.copy(gridValue = (s.gridValue + 1) % 5)
			is CameraUiEvent.ToggleQuad -> s.copy(quadOn = !s.quadOn)
			is CameraUiEvent.ToggleEis -> s.copy(eisOn = !s.eisOn)
			is CameraUiEvent.ToggleFps -> s.copy(fpsMode = (s.fpsMode + 1) % 4)
			is CameraUiEvent.ToggleHdrx -> s.copy(hdrxOn = !s.hdrxOn)
			is CameraUiEvent.ToggleManualBar -> s.copy(manualBarExpanded = !s.manualBarExpanded)
			is CameraUiEvent.SelectMode -> s.copy(mode = e.mode)
			is CameraUiEvent.SelectAux -> s.copy(activeCameraId = e.cameraId)
			is CameraUiEvent.SetSettingsBarVisible -> s.copy(settingsBarVisible = e.visible)
			is CameraUiEvent.SetSetting -> when (e.type) {
				SettingType.FLASH -> s.copy(flashValue = e.value)
				SettingType.TIMER -> s.copy(timerIndex = e.value)
				SettingType.GRID -> s.copy(gridValue = e.value)
				SettingType.QUAD -> s.copy(quadOn = e.value == 1)
				SettingType.EIS -> s.copy(eisOn = e.value == 1)
				SettingType.HDRX -> s.copy(hdrxOn = e.value == 1)
				SettingType.FPS_60 -> s.copy(fpsMode = e.value)
				else -> s
			}
			// A tap on the preview puts the settings bar away, as Swipe.java did.
			is CameraUiEvent.ViewfinderTap -> s.copy(settingsBarVisible = false)
			is CameraUiEvent.ViewfinderLongPress -> s
			is CameraUiEvent.SwipeUp -> s.copy(settingsBarVisible = true)
			is CameraUiEvent.SwipeDown -> s.copy(settingsBarVisible = false)
			is CameraUiEvent.OpenGallery -> s
			is CameraUiEvent.OpenSettings -> s
		}
	)

	/**
	 * The event, for a log a person reads.  The `object` members of
	 * `CameraUiEvent` are singletons with no data-class `toString`, so plain
	 * printing gives `...CameraUiEvent.Shutter@2db22b8` -- the hash is noise and
	 * the package is the whole line.  A data class already prints its payload,
	 * and that is the one thing the class name alone would lose.
	 */
	fun describe(e: CameraUiEvent): String {
		val s = e.toString()
		return if (s.endsWith(")")) s else e::class.simpleName ?: s
	}

	/** The settings bar mirrors the state, so it is derived rather than stored. */
	private fun withSettingsBar(s: CameraUiState): CameraUiState = s.copy(
		settingsBarEntries = listOf(
			entry(SettingType.FLASH, "Flash", s.flashValue, listOf(0 to "Torch", 1 to "Off", 2 to "On", 3 to "Auto")),
			entry(SettingType.TIMER, "Timer", s.timerIndex, listOf(0 to "Off", 1 to "3s", 2 to "10s")),
			entry(SettingType.GRID, "Grid", s.gridValue, listOf(0 to "Off", 1 to "3x3", 2 to "4x4", 3 to "Golden")),
			entry(SettingType.HDRX, "HDRX", if (s.hdrxOn) 1 else 0, listOf(0 to "Off", 1 to "On")),
			entry(SettingType.QUAD, "Quad Bayer", if (s.quadOn) 1 else 0, listOf(0 to "Off", 1 to "On")),
		)
	)

	private fun entry(type: SettingType, title: String, selected: Int, values: List<Pair<Int, String>>) =
		SettingsBarEntry(
			type = type,
			title = title,
			options = values.map { (v, label) -> SettingsBarOption(v, label, CameraIcons.forOption(type, v)) },
			selectedValue = selected,
		)
}

/**
 * The viewfinder slot, filled with something that is obviously NOT a picture.
 *
 * The real preview is the capture session's GL surface and cannot be common
 * code, so `CameraScreen` takes it as a slot.  A flat placeholder here is the
 * honest stand-in: it occupies exactly the box the stream's aspect ratio pins,
 * so the screenshot shows the layout the phone will have, and nobody can mistake
 * it for a frame that was captured.
 */
@Composable
fun BoxScope.PlaceholderViewfinder(label: String) {
	Box(
		Modifier
			.fillMaxSize()
			.background(
				Brush.linearGradient(listOf(Color(0xFF1B1B22), Color(0xFF2E2A3A)))
			),
		contentAlignment = Alignment.Center,
	) {
		Text(
			label,
			color = Color(0x66FFFFFF),
			fontSize = 13.sp,
			textAlign = TextAlign.Center,
		)
	}
}
