/* The screen WITH the converted app -- -PwithApp only.
 *
 * Its twin is src/linuxNoApp/kotlin/photoncam/screen/DemoScreen.kt.  Only one
 * of the two roots is ever on the source path, so the default build neither
 * names nor links anything below.
 *
 * WHAT THIS ROUND DOES.  It builds the one PhotonCamera Application the app's
 * statics expect, builds the app's own CaptureController against a Compose
 * PreviewSurface (photoncam.screen.ComposePreviewSurface), and runs the
 * viewfinder: the camera is opened, a repeating preview request is submitted,
 * and the delivered buffers are drawn in the scene by [CameraViewfinder].
 * The still-capture path is the app's and is reached through the shutter event
 * like any other; nothing here shortcuts it.
 *
 * ROUND 5 wires the events up: [CameraActions] is the port of the dropped
 * CameraUIController, so the shutter takes a picture, the carousel switches
 * mode, the top bar's toggles write the app's preferences and restart the
 * camera where the Java did, and a tap on the viewfinder runs TouchFocus.
 */
package photoncam.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.net.Uri
import android.os.Vibrator
import com.particlesdevs.photoncamera.api.CameraEventsListener
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.R_PREFERENCE_DEFAULTS
import com.particlesdevs.photoncamera.R_PREFERENCE_VALUES
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.capture.CaptureController
import com.particlesdevs.photoncamera.control.TouchFocus
import com.particlesdevs.photoncamera.composeui.camera.CameraScreen
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.util.FileManager
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost
import java.util.concurrent.Executors
import photoncam.host.HostMainLoop
import photoncam.host.HostWindow

/** The application singleton, built once and kept for the process's life. */
private val application: PhotonCamera by lazy {
	// Application's no-argument constructor is the platform lane's: a Context
	// here is three directories, a preference store and host-installed services.
	PhotonCamera().also {
		val cameraManager = CameraManager()
		it.installSystemService(Context.SENSOR_SERVICE, SensorManager())
		it.installSystemService(Context.AUDIO_SERVICE, AudioManager())
		it.installSystemService(Context.VIBRATOR_SERVICE, Vibrator())
		it.installSystemService(Context.CAMERA_SERVICE, cameraManager)
		runCatching { cameraManager.getCameraIdList()?.joinToString() }
			.onSuccess { ids -> println("[pc] camera2 ids=${ids.orEmpty()}") }
			.onFailure { e -> println("[pc] camera2 probe failed: $e") }
		runCatching { it.onCreate() }
			.onFailure { e -> report("PhotonCamera.onCreate", e) }
		runCatching { registerPreferenceDefaults(it) }
			.onFailure { e -> report("preference defaults", e) }
		// The window drains the app's main-thread queue for us; the camera path
		// posts to it (runOnUiThread) and nothing else would run those tasks.
		HostMainLoop.install { android.os.Looper.getMainLooper().pump() }
	}
}

/**
 * CameraActivity.onCreate's four lines, which nothing else on this port runs.
 *
 *     PreferenceManager.setDefaultValues(this, R.xml.preferences, ...)
 *     PreferenceKeys.setDefaults(this)
 *     PhotonCamera.getSettings().loadCache()
 *     FileManager.CreateFolders()
 *
 * The first is the one that matters and the one that cannot work as written:
 * it walks `res/xml/preferences.xml` for every `android:defaultValue`, and the
 * preference fragments that carry that tree are in drop.txt.  `R_PREFERENCE_
 * DEFAULTS` is that same table, read off the same XML by kn/gen-r.py, so the
 * app's settings have the defaults the app's own resources give them.
 *
 * WITHOUT IT every unset preference reads back 0, and 0 is a legal value for
 * most of them -- CONTROL_AF_MODE_OFF for the focus mode, which is a viewfinder
 * that never focuses and no error anywhere to say why.
 */
private fun registerPreferenceDefaults(app: PhotonCamera) {
	val settings = app.getSettingsManager() ?: return
	for ((key, default) in R_PREFERENCE_DEFAULTS)
		settings.setDefaults(key, default, R_PREFERENCE_VALUES[key] ?: arrayOf(default))
	// After the XML, as on Android: these carry richer possible-value sets and
	// must win where both name the same key.
	PreferenceKeys.setDefaults(app)
	PhotonCamera.getSettings()?.loadCache()
	// DCIM/Camera, DCIM/PhotonCamera/{Raw,Tuning}.  Also CameraActivity's, and
	// without it the pipeline runs to the very end and then cannot write: the
	// whole burst is lost to a FileNotFoundException on the last line.
	FileManager.CreateFolders()
	println("[pc] preference defaults: ${R_PREFERENCE_DEFAULTS.size} from res/xml," +
		" af=${PreferenceKeys.getAfMode()} ae=${PreferenceKeys.getAeMode()}")
}

private fun report(what: String, e: Throwable) {
	println("[pc] $what failed: $e")
	e.stackTraceToString().lines().drop(1).take(12).forEach { println("    $it") }
}

/**
 * The Activity the converted camera path asks for.
 *
 * android.app.Activity's own doc says nothing on this port ever creates one --
 * that was true while the app only drew itself.  CaptureController needs one for
 * three things and no more: the system services, `PhotonCamera.getInstance`
 * (which reads getApplicationContext) and runOnUiThread.  So this is not a
 * window; it is the application wearing the type the Java's signature names.
 */
private class HostActivity(private val app: PhotonCamera) : Activity() {
	override fun getApplication(): Application = app
	override fun getApplicationContext(): Context = app
	override fun getSystemService(name: String): Any? = app.getSystemService(name)
	override fun getResources() = app.getResources()
	override fun getAssets() = app.getAssets()
	override fun getString(resId: Int): String = app.getString(resId)
	override fun getString(resId: Int, vararg args: Any?): String = app.getString(resId, *args)
	override fun getFilesDir() = app.getFilesDir()
	override fun getCacheDir() = app.getCacheDir()
	override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(name, mode)
}

/**
 * CameraFragment's CameraEventsListenerImpl, minus everything that was a View.
 * Every call it makes is a CameraScreenHost method with the original's name, so
 * the capture path drives the Compose screen exactly where it drove the old one.
 */
private class HostCameraEvents(private val rig: CameraRig) : CameraEventsListener() {

	private val host: CameraScreenHost get() = rig.host

	/* ProcessingEventsListener */
	override fun onProcessingStarted(processName: String?) {
		logD("onProcessingStarted: $processName")
		host.setProcessingProgressBarIndeterminate(true)
		host.activateShutterButton(true)
	}

	override fun onProcessingChanged(obj: Any?) {}

	override fun onProcessingFinished(obj: Any?) {
		logD("onProcessingFinished: $obj")
		host.setProcessingProgressBarIndeterminate(false)
		host.activateShutterButton(true)
		host.lockUIForBurst(false)
	}

	override fun notifyImageSavedStatus(saved: Boolean, savedFilePath: java.nio.file.Path?) {
		if (saved) logD("ImageSaved: $savedFilePath") else logE("ImageSavingError")
	}

	override fun onProcessingError(obj: Any?) {
		logE("onProcessingError: $obj")
		host.lockUIForBurst(false)
		onProcessingFinished("Processing Finished Unexpectedly!!")
	}

	/* CaptureEventsListener */
	override fun onFrameCountSet(frameCount: Int) = host.setCaptureProgressMax(frameCount)

	override fun onCaptureStillPictureStarted(o: Any?) {
		if (PhotonCamera.getSettings()?.selectedMode != CameraMode.RAWVIDEO) {
			host.setCaptureProgressBarOpacity(1.0f)
			host.lockUIForBurst(true)
		}
	}

	override fun onBurstPrepared(o: Any?) {}

	override fun onFrameCaptureStarted(o: Any?) {}

	override fun onFrameCaptureProgressed(o: Any?) {}

	override fun onFrameCaptureCompleted(o: Any?) {
		if (PhotonCamera.getSettings()?.selectedMode != CameraMode.RAWVIDEO)
			host.incrementCaptureProgressBar(1)
	}

	override fun onCaptureSequenceCompleted(o: Any?) {
		host.resetCaptureProgressBar()
		host.lockUIForBurst(false)
		host.setVideoRecordingInfoVisible(false)
	}

	/**
	 * CameraFragment.updateScreenLog's one line that is not a debug HUD: the
	 * focus circle's colour is the AF state of the newest preview result.
	 */
	override fun onPreviewCaptureCompleted(captureResult: CaptureResult?) {
		val focus = rig.touchFocus ?: return
		focus.setState(captureResult?.get(CaptureResult.CONTROL_AF_STATE)
			?: CameraMetadata.CONTROL_AF_STATE_INACTIVE)
	}

	/* CameraEventsListener */
	override fun onOpenCamera(cameraManager: CameraManager?) {
		logD("onOpenCamera: ${cameraManager?.getCameraIdList()?.joinToString()}")
		// CameraFragment did this here too: the lens map is what the aux row
		// and the flip button are built from, and it cannot be read before a
		// camera has been opened.
		rig.actions?.initCameraIdLists(cameraManager)
	}

	override fun onCameraRestarted() {
		host.refresh(CaptureController.isProcessing)
		// A restart can land before the preview surface exists, and TouchFocus
		// is only built once it does.
		rig.touchFocus?.resetFocusCircle()
	}

	override fun onCharacteristicsUpdated(characteristics: CameraCharacteristics?) {
		val flash = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
		host.showFlashButton(flash != null && flash)
	}

	override fun onError(o: Any?) = logE("onError: $o")

	override fun onFatalError(errorMsg: String?) = logE("onFatalError: $errorMsg")

	override fun onRequestTriggerMediaScanner(f: Uri?) {}
}

/**
 * The app's camera, running.  One of these is made when the screen first
 * composes and closed when it goes away, which is CameraFragment's
 * onViewCreated/onResume and onPause in the two places Compose has for them.
 */
internal class CameraRig(val host: CameraScreenHost) {
	val preview = ComposePreviewSurface()
	/** The focus circle and the spot-WB reticle, which [CameraViewfinder] draws. */
	val overlay = ViewfinderOverlay()
	private val executor = Executors.newSingleThreadExecutor()
	var controller: CaptureController? = null
		private set
	var actions: CameraActions? = null
		private set
	/** CameraFragment.mTouchFocus, built once the preview surface exists. */
	var touchFocus: TouchFocus? = null
		private set

	fun start() {
		val activity = HostActivity(application)
		val a = CameraActions(application, host, this)
		actions = a
		host.setEventListener { event -> a.onEvent(event) }
		val c = CaptureController(activity, preview, executor, HostCameraEvents(this))
		controller = c
		// CameraFragment.onViewCreated does this; it decides whether a burst
		// gets its own session, and nothing else sets it.
		c.isDualSession = application.getSupportedDevice()?.specific?.specificSetting
			?.isDualSessionSupported ?: false
		PhotonCamera.setCaptureController(c)
		c.startBackgroundThread()
		c.resumeCamera()
		// CameraFragment.initTouchFocus, which runs right after resumeCamera and
		// posts so the preview view has been laid out.  The Compose slot reports
		// its size through markAvailable, so there is nothing to wait for.
		val focus = TouchFocus(c, HostFocusIndicator(preview, overlay))
		touchFocus = focus
		c.mTouchFocus = focus
		println("[pc] camera resumed against ${HostWindow.widthPx}x${HostWindow.heightPx}")
	}

	fun stop() {
		runCatching { controller?.closeCamera() }
		runCatching { controller?.stopBackgroundThread() }
		runCatching { preview.release() }
		executor.shutdownNow()
		PhotonCamera.setCaptureController(null)
		controller?.mTouchFocus = null
		touchFocus = null
		actions = null
		host.setEventListener(null)
		controller = null
	}
}

@Composable
fun CameraScreenContent() {
	PhotonTheme {
		val host = remember {
			CameraScreenHost(application).also { h ->
				// The preference sync is what fills the top bar, the mode and
				// the gradient from the app's own stored settings.  It reads
				// SettingsManager, so it is the first thing that can fail on a
				// half-built application -- hence the catch and the log rather
				// than a black window.
				runCatching { h.syncFromPreferences() }
					.onFailure { e -> report("syncFromPreferences", e) }
			}
		}
		val rig = remember { CameraRig(host) }
		DisposableEffect(rig) {
			runCatching { rig.start() }.onFailure { e -> report("camera start", e) }
			// CameraFragment.onViewCreated's applyMode, now that the actions
			// exist: it also pushes the settings-bar entries, which is what
			// fills the bar a swipe down opens.
			runCatching {
				rig.actions?.applyCameraMode(
					CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal())!!,
				)
			}.onFailure { e -> report("applyMode", e) }
			onDispose { runCatching { rig.stop() }.onFailure { e -> report("camera stop", e) } }
		}
		// Nothing on Ubuntu Touch can press a button for us; PC_EVENTS is how a
		// phone run reaches the shutter and the carousel at all.
		EventScriptRunner(host, rig.preview)
		CameraScreen(
			state = host.state,
			onEvent = { host.onEvent(it) },
			viewfinder = { CameraViewfinder(rig.preview, rig.overlay) },
		)
	}
}
