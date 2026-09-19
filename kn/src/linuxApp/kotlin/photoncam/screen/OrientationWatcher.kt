/* Which way up the phone is, for the controls that counter-rotate.
 *
 * On Android this is two dropped classes: android.view.OrientationEventListener
 * does the maths, ui/camera/CustomOrientationEventListener buckets it into four
 * quadrants with a dead zone between them, and CameraFragmentViewModel turns the
 * bucket into the {0, -90, 90, 180} that CameraScreenHost.setOrientation takes
 * and Modifier.uprightIn negates.  The maths below is AOSP's and the buckets are
 * PhotonCamera's; only the plumbing is this port's.
 *
 * WHY IT EXISTS AT ALL.  click/photoncamera.desktop declares
 * X-Lomiri-Supported-Orientations=portrait, so Lomiri never rotates the surface
 * -- exactly as CameraActivity.onCreate pins the activity with
 * setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT).  A locked window that
 * nothing counter-rotates would leave every icon on its side.
 *
 * ONE DEVIATION, on purpose.  CustomOrientationEventListener returns early when
 * Settings.System.ACCELEROMETER_ROTATION is off.  The Ubuntu Touch counterpart
 * is `com.lomiri.touch.system rotation-lock`, and it is not consulted: there it
 * governs whether the SHELL turns the window, which this app has already opted
 * out of.  The saved picture's orientation never honoured that setting on
 * Android either -- Gravity, not this listener, is what sets JPEG_ORIENTATION.
 */
package photoncam.screen

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.round
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost

class OrientationWatcher(
	private val sensors: SensorManager,
	private val onOrientation: (Int) -> Unit,
) : SensorEventListener {

	private var sensor: Sensor? = null
	private var reported = UNKNOWN

	fun register() {
		val s = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
		sensor = s
		sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
	}

	fun unregister() {
		sensors.unregisterListener(this, sensor)
		sensor = null
		reported = UNKNOWN
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

	override fun onSensorChanged(event: SensorEvent?) {
		val v = event?.values ?: return
		if (v.size < 3) return
		val degrees = angleOf(v[0], v[1], v[2])
		if (degrees == UNKNOWN) return
		val quadrant = quadrantOf(degrees) ?: return
		if (quadrant == reported) return
		reported = quadrant
		onOrientation(quadrant)
	}

	companion object {
		private const val UNKNOWN = -1

		/**
		 * OrientationEventListener.SensorEventListenerImpl.onSensorChanged, in
		 * its own terms: the tilt is only believed when the phone is not lying
		 * flat, which is what the magnitude test against z is for.
		 */
		fun angleOf(ax: Float, ay: Float, az: Float): Int {
			val x = -ax
			val y = -ay
			val z = -az
			val magnitude = x * x + y * y
			if (magnitude * 4f < z * z) return UNKNOWN
			var orientation = 90 - round(atan2(-y, x) * 57.29577957855f).toInt()
			while (orientation >= 360) orientation -= 360
			while (orientation < 0) orientation += 360
			return orientation
		}

		/**
		 * CustomOrientationEventListener's four windows, and the rotation
		 * CameraFragmentViewModel derives from each.  The 20-degree gaps between
		 * them are the dead zone: inside one the last answer stands, so a phone
		 * held at 45 degrees does not flip back and forth.
		 */
		fun quadrantOf(degrees: Int): Int? = when {
			degrees >= 340 || degrees < 20 -> 0
			degrees in 70..109 -> -90    // left side up
			degrees in 160..199 -> 180   // upside down
			degrees in 250..289 -> 90    // right side up
			else -> null
		}
	}
}

/** The watcher wired to the screen's state, which is all CameraRig needs.
 *  CameraFragment's binding observer did this: uiModel.getOrientation() ->
 *  cameraUiHost.setOrientation(). */
fun orientationWatcherFor(sensors: SensorManager, host: CameraScreenHost) =
	OrientationWatcher(sensors) { host.setOrientation(it) }
