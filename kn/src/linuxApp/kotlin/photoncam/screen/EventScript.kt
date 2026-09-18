/* PRESSING THE BUTTONS WITH NOBODY THERE.  -PwithApp only.
 *
 * PC_EVENTS is a script of CameraUiEvents on a clock, in the same spirit as
 * PC_PREVIEW_ROTATE: it settles a question in a run rather than a rebuild.
 *
 * IT IS THE CHEAP PATH AND NOT THE HONEST ONE.  A real finger on a phone is
 * `uinput-touch` (~/UT/firefox-atl/compose-ut/ut/device/uinput-touch-arm64): it
 * makes a kernel touchscreen over /dev/uinput, so Mir routes what it reports
 * exactly like the panel and the tap is indistinguishable from a finger by the
 * time it reaches the app.  That is what to use when the QUESTION is whether
 * input arrives -- and it needs the app launched through lomiri-app-launch,
 * because Mir gives touch only to a shell-focused surface and a toplevel
 * started over ssh is not one.  The desktop equivalent is
 * zwlr_virtual_pointer.
 *
 * What this file is for is the other question: does the SHUTTER take a picture,
 * does the carousel switch mode.  It goes through CameraScreenHost.onEvent, so
 * it takes the same path a finger's hit test ends in, and it needs no root, no
 * device node and no managed launch.
 *
 *     PC_EVENTS="6:shutter,12:mode=PHOTO,16:tap=0.5x0.5,20:shutter"
 *
 * `<seconds>:<event>`, comma separated, seconds from the first frame.  Nothing
 * here calls the capture controller itself.
 */
package photoncam.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.particlesdevs.photoncamera.composeui.state.CameraMode as UiCameraMode
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private val script: String? = getenv("PC_EVENTS")?.toKString()?.takeIf { it.isNotBlank() }

/**
 * ONCE PER PROCESS, not once per composition.  Coming back from the settings
 * screen rebuilds the camera screen, and a LaunchedEffect keyed on the script
 * text starts over: the run re-opened settings ten seconds after every visit.
 */
private var scriptStarted = false

/**
 * Runs PC_EVENTS, if it is set.  One coroutine on the composition's scope, so
 * it dies with the screen; the delays are absolute, not cumulative, which is
 * what makes a script readable against the log's timestamps.
 */
@Composable
fun EventScriptRunner(host: CameraScreenHost, surface: ComposePreviewSurface) {
	val text = script ?: return
	if (scriptStarted) return
	LaunchedEffect(text) {
		scriptStarted = true
		println("[pc] PC_EVENTS=$text")
		var elapsedMs = 0L
		for (step in text.split(",")) {
			val at = step.substringBefore(':', "").trim().toDoubleOrNull()
			val name = step.substringAfter(':', "").trim()
			if (at == null || name.isEmpty()) {
				println("[pc] PC_EVENTS: cannot read step '$step'")
				continue
			}
			val atMs = (at * 1000).toLong()
			if (atMs > elapsedMs) delay(atMs - elapsedMs)
			elapsedMs = maxOf(elapsedMs, atMs)
			val event = parse(name, surface)
			if (event == null) {
				println("[pc] PC_EVENTS: no such event '$name'")
				continue
			}
			println("[pc] PC_EVENTS ${at}s -> $event")
			host.onEvent(event)
		}
		println("[pc] PC_EVENTS: done")
	}
}

/**
 * The vocabulary.  Tap coordinates are FRACTIONS of the preview box, because
 * the box's pixel size is the panel's and a script should not have to know it.
 */
private fun parse(name: String, surface: ComposePreviewSurface): CameraUiEvent? {
	val key = name.substringBefore('=').lowercase()
	val arg = name.substringAfter('=', "")
	return when (key) {
		"shutter" -> CameraUiEvent.Shutter
		"settings" -> CameraUiEvent.OpenSettings
		"flip" -> CameraUiEvent.FlipCamera
		"flash" -> CameraUiEvent.ToggleFlash
		"timer" -> CameraUiEvent.ToggleTimer
		"grid" -> CameraUiEvent.ToggleGrid
		"quad" -> CameraUiEvent.ToggleQuad
		"eis" -> CameraUiEvent.ToggleEis
		"fps" -> CameraUiEvent.ToggleFps
		"hdrx" -> CameraUiEvent.ToggleHdrx
		"swipeup" -> CameraUiEvent.SwipeUp
		"swipedown" -> CameraUiEvent.SwipeDown
		"bar" -> CameraUiEvent.SetSettingsBarVisible(arg != "off" && arg != "0")
		"mode" -> runCatching { UiCameraMode.valueOf(arg.uppercase()) }.getOrNull()
			?.let { CameraUiEvent.SelectMode(it) }
		"aux" -> if (arg.isEmpty()) null else CameraUiEvent.SelectAux(arg)
		"tap" -> point(arg, surface)?.let { CameraUiEvent.ViewfinderTap(it.first, it.second) }
		"longpress" -> point(arg, surface)
			?.let { CameraUiEvent.ViewfinderLongPress(it.first, it.second) }
		else -> null
	}
}

private fun point(arg: String, surface: ComposePreviewSurface): Pair<Float, Float>? {
	// A comma would end the step, so the fractions are separated by an 'x'.
	val parts = arg.split('x', ';')
	if (parts.size != 2) return null
	val fx = parts[0].toFloatOrNull() ?: return null
	val fy = parts[1].toFloatOrNull() ?: return null
	return Pair(fx * surface.width, fy * surface.height)
}
