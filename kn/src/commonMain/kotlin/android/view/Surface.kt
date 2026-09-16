package android.view

import android.graphics.SurfaceTexture
import android.media.ImageReader

/**
 * A camera2 output sink.  In atlas a Surface is a handle onto a native consumer
 * and the camera2 JNI reads the handle out of it; here the consumers are Kotlin
 * objects (android.media.ImageReader, android.graphics.SurfaceTexture), so the
 * Surface simply names one.
 *
 * The SurfaceView/ANativeWindow half of AOSP's Surface has no meaning in this
 * port: the scene is Compose, not a view hierarchy.
 */
open class Surface {
	var surfaceTexture: SurfaceTexture? = null
		private set
	/** set by ImageReader.getSurface(); the queue behind this sink */
	var imageReader: ImageReader? = null
		internal set

	constructor()

	constructor(surfaceTexture: SurfaceTexture?) {
		requireNotNull(surfaceTexture) { "surfaceTexture must not be null" }
		this.surfaceTexture = surfaceTexture
	}

	fun isValid(): Boolean = surfaceTexture != null || imageReader != null

	fun release() {
		surfaceTexture = null
		imageReader = null
	}

	/** the size the sink presents at */
	fun getWidth(): Int = imageReader?.getWidth() ?: surfaceTexture?.getWidth() ?: 0

	fun getHeight(): Int = imageReader?.getHeight() ?: surfaceTexture?.getHeight() ?: 0

	companion object {
		const val ROTATION_0 = 0
		const val ROTATION_90 = 1
		const val ROTATION_180 = 2
		const val ROTATION_270 = 3
	}
}
