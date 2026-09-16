@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.media

import android.os.Handler
import android.view.Surface
import photoncam.camera.CameraBuffer
import photoncam.camera.SpinLock

/**
 * The consumer end of one camera2 output stream: a bounded queue of frames the
 * camera filled.  atlas keeps this queue in C (image_reader.h) because its
 * producer is the JNI layer; here the producer is
 * android.hardware.camera2.impl.CameraDeviceNative, which is Kotlin, so the
 * queue is too.
 *
 * submit() runs on a backend thread and the listener is called from there, the
 * way atlas's is before it posts to the main loop.
 */
open class ImageReader private constructor(
	private val width: Int,
	private val height: Int,
	private val format: Int,
	private val maxImages: Int,
	private val usage: Long,
) {

	fun interface OnImageAvailableListener {
		fun onImageAvailable(reader: ImageReader?)
	}

	private val queue = ArrayDeque<Image>()
	private val lock = SpinLock()
	private var listener: OnImageAvailableListener? = null
	private var surface: Surface? = null
	private var closed = false

	open fun getWidth(): Int = width
	open fun getHeight(): Int = height
	open fun getImageFormat(): Int = format
	open fun getMaxImages(): Int = maxImages
	open fun getUsage(): Long = usage

	open fun getSurface(): Surface {
		var s = surface
		if (s == null) {
			s = Surface()
			s.imageReader = this
			surface = s
		}
		return s
	}

	open fun setOnImageAvailableListener(listener: OnImageAvailableListener?, handler: Handler?) {
		this.listener = listener
	}

	/** null when the camera has produced nothing since the last acquire. */
	open fun acquireNextImage(): Image? = lock.withLock { queue.removeFirstOrNull() }

	/** the newest queued frame; the ones before it are dropped, as on Android. */
	open fun acquireLatestImage(): Image? {
		val stale = ArrayList<Image>()
		val newest = lock.withLock {
			while (queue.size > 1)
				stale.add(queue.removeFirst())
			queue.removeFirstOrNull()
		}
		for (image in stale)
			image.close()
		return newest
	}

	/** frames the app never acquired go back to the producer. */
	open fun discardFreeBuffers() {
		val dropped = lock.withLock {
			val all = queue.toList()
			queue.clear()
			all
		}
		for (image in dropped)
			image.close()
	}

	open fun close() {
		closed = true
		discardFreeBuffers()
		listener = null
		surface?.imageReader = null
	}

	/**
	 * A camera buffer for this stream.  The reader owns it from here on: over
	 * maxImages in hand means the app has fallen behind, and the frame is
	 * dropped rather than allowed to starve the producer.
	 */
	fun submit(buffer: CameraBuffer) {
		if (closed) {
			buffer.release()
			return
		}
		val image = Image(buffer)
		val accepted = lock.withLock {
			if (queue.size >= maxImages) {
				false
			} else {
				queue.addLast(image)
				true
			}
		}
		if (!accepted) {
			image.close()
			return
		}
		listener?.onImageAvailable(this)
	}

	companion object {
		/** ATL_DEBUG_IMAGEREADER: the frame bookkeeping atlas traces under this. */
		val DEBUG: Boolean = platform.posix.getenv("ATL_DEBUG_IMAGEREADER") != null

		fun newInstance(width: Int, height: Int, format: Int, maxImages: Int): ImageReader =
			ImageReader(width, height, format, maxImages, 0L)

		fun newInstance(width: Int, height: Int, format: Int, maxImages: Int,
		                usage: Long): ImageReader =
			ImageReader(width, height, format, maxImages, usage)
	}
}
