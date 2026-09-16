/* The screen WITHOUT the converted app -- the default build.
 *
 * Its twin is src/linuxApp/kotlin/photoncam/screen/AppScreen.kt, which the
 * build swaps in under -PwithApp.  Same package, same signature, and only one
 * of the two roots is ever on the source path, so nothing here can name the
 * app and the no-app binary links without gen/.
 */
package photoncam.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.particlesdevs.photoncamera.composeui.camera.CameraScreen
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import photoncam.host.DemoCameraHost
import photoncam.host.PlaceholderViewfinder

/**
 * Every event is printed: the run script presses the shutter through a virtual
 * pointer and then greps for the line, which is the only way a headless run can
 * say that the press reached the composition rather than merely repainted it.
 */
@Composable
fun CameraScreenContent() {
	PhotonTheme {
		var state by remember { mutableStateOf(DemoCameraHost.initial()) }
		CameraScreen(
			state = state,
			onEvent = { event ->
				println("[pc] CameraUiEvent ${DemoCameraHost.describe(event)}")
				state = DemoCameraHost.reduce(state, event)
			},
			viewfinder = { PlaceholderViewfinder("viewfinder\n(no camera in this build)") },
		)
	}
}
