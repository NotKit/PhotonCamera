package android.media

import android.graphics.Rect
import java.nio.ByteBuffer
import photoncam.camera.CameraBuffer

/**
 * One frame out of an ImageReader.  Backed by a camera buffer of the backend's
 * (photoncam.camera.CameraBuffer); close() is what hands that buffer back to
 * the producer, so an app that leaks Images starves the stream exactly as it
 * does on Android.
 *
 * The plane bytes are copied out on first access: the backend's buffer may be
 * HAL memory that is only valid until release(), and this port's ByteBuffer is
 * a heap buffer.
 */
open class Image internal constructor(private val buffer: CameraBuffer) {

	open class Plane internal constructor(
		private val bytes: ByteArray,
		private val rowStride: Int,
		private val pixelStride: Int,
	) {
		private val wrapped: ByteBuffer = ByteBuffer.wrap(bytes)

		open fun getBuffer(): ByteBuffer = wrapped
		open fun getRowStride(): Int = rowStride
		open fun getPixelStride(): Int = pixelStride
	}

	private var planes: Array<Plane>? = null
	private var closed = false
	private var cropRect: Rect? = null

	open fun getWidth(): Int = buffer.width
	open fun getHeight(): Int = buffer.height
	open fun getFormat(): Int = buffer.format
	open fun getTimestamp(): Long = buffer.timestamp

	open fun getPlanes(): Array<Plane> {
		var p = planes
		if (p == null) {
			p = Array(buffer.planeCount) {
				Plane(buffer.readPlane(it), buffer.rowStride(it), buffer.pixelStride(it))
			}
			planes = p
		}
		return p
	}

	open fun getCropRect(): Rect {
		var r = cropRect
		if (r == null) {
			r = Rect(0, 0, buffer.width, buffer.height)
			cropRect = r
		}
		return r
	}

	open fun setCropRect(rect: Rect?) {
		cropRect = rect
	}

	open fun close() {
		if (closed)
			return
		closed = true
		planes = null
		buffer.release()
	}
}
