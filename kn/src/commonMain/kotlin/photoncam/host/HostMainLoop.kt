/* The app's main-thread queue, as the window sees it.
 *
 * android.os.Looper is the converted app's, and the no-app build does not
 * compile the android.* shims at all -- so MgwlComposeHost cannot name it.  The
 * screen that has an app installs the drain here instead, and the window calls
 * whatever it finds, once per pass of its own loop.
 *
 * It matters: the camera path posts real work to the main thread.
 * UpdateCameraCharacteristics does its onCharacteristicsUpdated inside a
 * runOnUiThread, and with nothing draining that queue it never happens.
 */
package photoncam.host

object HostMainLoop {
	private var drain: (() -> Unit)? = null

	fun install(drain: () -> Unit) {
		this.drain = drain
	}

	fun pump() {
		drain?.invoke()
	}
}
