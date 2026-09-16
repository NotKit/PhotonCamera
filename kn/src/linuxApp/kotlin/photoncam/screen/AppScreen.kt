/* The screen WITH the converted app -- -PwithApp only.
 *
 * Its twin is src/linuxNoApp/kotlin/photoncam/screen/DemoScreen.kt.  Only one
 * of the two roots is ever on the source path, so the default build neither
 * names nor links anything below.
 *
 * WHAT THIS ROUND DOES AND DOES NOT DO.  It builds the one PhotonCamera
 * Application the app's statics expect, hands the Compose screen the real
 * CameraScreenHost, and prints every event.  It does NOT start a capture
 * session: the viewfinder slot is still the placeholder, and CaptureController
 * is not touched.  What it proves is that the converted app is in the binary
 * and that the screen is driven by ITS state and not by a table.
 */
package photoncam.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.composeui.camera.CameraScreen
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost
import photoncam.host.PlaceholderViewfinder

/** The application singleton, built once and kept for the process's life. */
private val application: PhotonCamera by lazy {
	// Application's no-argument constructor is the platform lane's: a Context
	// here is three directories and a preference store, so there is nothing to
	// attach and onCreate() can be called straight away.
	PhotonCamera().also {
		runCatching { it.onCreate() }
			.onFailure { e -> println("[pc] PhotonCamera.onCreate failed: $e") }
	}
}

@Composable
fun CameraScreenContent() {
	PhotonTheme {
		val host = remember {
			CameraScreenHost(application).also { h ->
				h.setEventListener { event -> println("[pc] CameraUiEvent $event") }
				// The preference sync is what fills the top bar, the mode and
				// the gradient from the app's own stored settings.  It reads
				// SettingsManager, so it is the first thing that can fail on a
				// half-built application -- hence the catch and the log rather
				// than a black window.
				runCatching { h.syncFromPreferences() }
					.onFailure { e -> println("[pc] syncFromPreferences failed: $e") }
			}
		}
		CameraScreen(
			state = host.state,
			onEvent = { host.onEvent(it) },
			viewfinder = { PlaceholderViewfinder("viewfinder\n(capture not wired yet)") },
		)
	}
}
