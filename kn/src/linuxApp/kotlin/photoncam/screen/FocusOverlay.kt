/* THE VIEWFINDER'S INDICATORS -- the focus circle and the spot-WB reticle.
 * -PwithApp only.
 *
 * control/FocusIndicator is the seam TouchFocus was cut against: it runs the
 * whole 3A protocol and asks the screen only for the preview's geometry, the
 * display rotation, a thread to post work on, and these two indicators.  The
 * Android build satisfies it with Views (ui/camera/views/ViewFocusIndicator);
 * here the indicators are Compose state that [CameraViewfinder] draws, so they
 * live inside the viewfinder box -- which is where viewfinder_stack.xml put
 * them.
 *
 * THE THREAD.  TouchFocus posts from the camera's result callbacks, which run
 * on the backend's thread, and its own doc says the indicators live on ONE
 * thread.  That thread here is the main looper, the same one HostMainLoop
 * drains, so every write to the state below happens where Compose reads it.
 */
package photoncam.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import android.hardware.camera2.CameraMetadata
import android.os.Handler
import android.os.Looper
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.control.FocusIndicator
import java.lang.Runnable

/** What the two indicators are showing, read by [CameraViewfinder]'s canvas. */
class ViewfinderOverlay {
	/** Where the focus circle sits, in preview-box pixels, or null when hidden. */
	var focusAt by mutableStateOf<Offset?>(null)
		internal set

	/** CameraMetadata.CONTROL_AF_STATE_*, which is the circle's colour. */
	var afState by mutableStateOf(CameraMetadata.CONTROL_AF_STATE_INACTIVE)
		internal set

	var spotWbAt by mutableStateOf<Offset?>(null)
		internal set

	/** Non-null once the measurement failed; the reticle turns red. */
	var spotWbError by mutableStateOf<String?>(null)
		internal set
}

/**
 * FocusIndicator over the Compose viewfinder.
 *
 * WHAT IT DROPS.  ViewFocusIndicator's animations (the circle's 250 ms pulse,
 * the fade on hide) are View animators; the state here is a position and the
 * canvas draws it, so the indicator appears and goes without them.  Everything
 * TouchFocus decides on -- the geometry, the rotation, the posting, the AF
 * state -- is real.
 */
class HostFocusIndicator(
	private val surface: ComposePreviewSurface,
	private val overlay: ViewfinderOverlay,
) : FocusIndicator {

	private val handler = Handler(Looper.getMainLooper())

	override fun getPreviewWidth(): Int = surface.width

	override fun getPreviewHeight(): Int = surface.height

	/**
	 * ViewFocusIndicator returns `display.getRotation() * 90 + 90`.  This port's
	 * window is never rotated by a window manager -- Lomiri hands the app the
	 * panel as it is -- so the rotation is 0 and the figure is the 90 the
	 * Android build reports in portrait.  -1 until the slot has been laid out,
	 * which is what "the viewfinder is not attached" means here.
	 */
	override fun getDisplayRotation(): Int = if (surface.width <= 0) -1 else 90

	override fun hapticLongPress() {
		runCatching { PhotonCamera.getVibration()?.Click() }
	}

	override fun post(action: Runnable?) {
		if (action != null) handler.post(action)
	}

	override fun postDelayed(action: Runnable?, delayMs: Long) {
		if (action != null) handler.postDelayed(action, delayMs)
	}

	override fun cancel(action: Runnable?) {
		if (action != null) handler.removeCallbacks(action)
	}

	override fun showFocusCircle(x: Float, y: Float) {
		overlay.focusAt = Offset(x, y)
	}

	override fun hideFocusCircle() {
		overlay.focusAt = null
	}

	override fun setAfState(afState: Int) {
		overlay.afState = afState
	}

	override fun showSpotWb(x: Float, y: Float) {
		overlay.spotWbError = null
		overlay.spotWbAt = Offset(x, y)
	}

	override fun setSpotWbError(reason: String?) {
		overlay.spotWbError = reason ?: "error"
	}

	override fun hideSpotWb() {
		overlay.spotWbAt = null
		overlay.spotWbError = null
	}

	/** The reticle's label is what counter-rotated; there is no label here. */
	override fun setOrientation(orientation: Int) {}
}
