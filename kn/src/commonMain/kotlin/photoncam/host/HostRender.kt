/* The way a producer outside Compose asks for a frame.
 *
 * The host renders only when Compose says it has something to draw, and a
 * camera buffer arriving on the backend's thread is invisible to Compose: the
 * GPU preview keeps its frame in a field the scene never reads, so nothing
 * invalidates and the render loop stays idle with the last frame on screen.
 * The CPU path did not need this -- it wrote the frame into snapshot state and
 * that was the invalidation.
 */
package photoncam.host

object HostRender {
	/** MgwlComposeHost installs this; null before the window exists. */
	@kotlin.concurrent.Volatile
	var invalidate: (() -> Unit)? = null

	/** Safe from any thread. */
	fun requestRender() {
		invalidate?.invoke()
	}
}
