package android.hardware.camera2.impl

import android.media.ImageReader
import android.view.Surface
import photoncam.camera.CameraBuffer
import photoncam.camera.CameraSession

/**
 * The native half of an open camera2 device: one backend session, its
 * configured outputs, and the routing of every delivered buffer to the consumer
 * behind its Surface.  Hand-written from atlas's Java and the JNI file under it
 * (android_hardware_camera2_impl_CameraDeviceNative.c), because the consumers
 * are Kotlin objects in this port rather than native ones.
 *
 * Unlike atlas the callbacks are *not* posted to a main loop: they run on the
 * backend thread that produced them.  Nothing between here and the app's
 * listener touches the scene, and this port has no Looper to post to yet.
 */
class CameraDeviceNative(private val listener: Listener) : CameraSession.Sink {

	interface Listener {
		fun onCaptureStarted(requestId: Int, frameNumber: Long, timestamp: Long)
		/** the result is owned by the listener from here on */
		fun onCaptureCompleted(requestId: Int, frameNumber: Long, result: CameraMetadataNative?)
		fun onCaptureFailed(requestId: Int, frameNumber: Long)
		/* stream is the index of the output, in the order configure() was given */
		fun onCaptureBufferLost(requestId: Int, frameNumber: Long, stream: Int)
		fun onDeviceError(error: Int)
	}

	private var session: CameraSession? = null
	private var outputs: List<Surface> = emptyList()

	/** false when the backend cannot open this camera. */
	fun open(cameraId: String?): Boolean {
		val s = CameraSession(this)
		if (!s.open(cameraId))
			return false
		session = s
		return true
	}

	/**
	 * Points the session at its outputs.  Returns the size the single backend
	 * stream was configured with (the largest output's), or null if one of the
	 * surfaces is not something the backend can fill.
	 */
	fun configure(surfaces: List<Surface>?, physicalIds: Array<String>?): IntArray? {
		val s = session ?: return null
		if (surfaces == null || surfaces.isEmpty())
			return null

		val configs = ArrayList<CameraSession.StreamConfig>(surfaces.size)
		for (i in surfaces.indices) {
			/* the array is built with arrayOfNulls, so an entry really can be
			 * null however it is typed */
			val physical: String? =
				if (physicalIds != null && i < physicalIds.size) physicalIds[i] else null
			val config = streamOf(surfaces[i], if (physical.isNullOrEmpty()) null else physical)
				?: return null
			configs.add(config)
		}
		val size = s.configure(configs) ?: return null
		outputs = ArrayList(surfaces)
		return size
	}

	private fun streamOf(surface: Surface?, physicalId: String?): CameraSession.StreamConfig? {
		val reader = surface?.imageReader
		if (reader != null)
			return CameraSession.StreamConfig(reader.getWidth(), reader.getHeight(),
				reader.getImageFormat(), reader.getMaxImages(),
				if (reader.getUsage() != 0L) reader.getUsage().toULong()
				else CameraSession.USAGE_CPU_READ_OFTEN, physicalId)

		val texture = surface?.surfaceTexture ?: return null
		if (texture.getWidth() <= 0 || texture.getHeight() <= 0)
			return null
		return CameraSession.StreamConfig(texture.getWidth(), texture.getHeight(),
			CameraSession.FORMAT_YUV_420_888, 3,
			CameraSession.USAGE_GPU_SAMPLED_IMAGE or CameraSession.USAGE_CPU_READ_OFTEN,
			physicalId)
	}

	/**
	 * targets is a bit per configured output, in the order configure() was
	 * given them: a frame only reaches the surfaces its request aimed at.
	 */
	fun setRepeatingRequest(requestId: Int, request: CameraMetadataNative?, targets: Int): Boolean =
		session?.submit(requestId, request?.getPtr() ?: 0L, targets, true) ?: false

	fun capture(requestId: Int, request: CameraMetadataNative?, targets: Int): Boolean =
		session?.submit(requestId, request?.getPtr() ?: 0L, targets, false) ?: false

	/**
	 * Reprocessing: the backend's input stream.  Absent here - the camera2 NDK
	 * only grew an input configuration with the Halium extension, and nothing
	 * in this port queues images into one yet.
	 */
	fun createInputSurface(surface: Surface?, width: Int, height: Int, format: Int,
	                       multiResolution: Boolean): Boolean = false

	fun clearInputSurface() {
	}

	fun reprocess(requestId: Int, request: CameraMetadataNative?, targets: Int): Boolean = false

	fun stopRepeating() {
		session?.stopRepeating()
	}

	/** the ids of the one-shot requests that were dropped before taking a frame */
	fun abortCaptures(): IntArray = session?.flush() ?: IntArray(0)

	fun close() {
		session?.close()
		session = null
		outputs = emptyList()
	}

	/* ---- CameraSession.Sink: on a backend thread ---- */

	override fun onCaptureStarted(requestId: Int, frameNumber: Long, timestamp: Long) =
		listener.onCaptureStarted(requestId, frameNumber, timestamp)

	override fun onCaptureResult(requestId: Int, frameNumber: Long, resultPtr: Long) =
		listener.onCaptureCompleted(requestId, frameNumber, CameraMetadataNative.adopt(resultPtr))

	override fun onCaptureFailed(requestId: Int, frameNumber: Long) =
		listener.onCaptureFailed(requestId, frameNumber)

	override fun onBufferLost(requestId: Int, frameNumber: Long, stream: Int) =
		listener.onCaptureBufferLost(requestId, frameNumber, stream)

	override fun onBuffer(buffer: CameraBuffer) {
		val sinks = outputs
		val index = buffer.stream
		if (index < 0 || index >= sinks.size) {
			buffer.release()
			return
		}
		val surface = sinks[index]
		val reader: ImageReader? = surface.imageReader
		if (reader != null) {
			reader.submit(buffer) /* the reader owns it now */
			return
		}
		val texture = surface.surfaceTexture
		if (texture != null && texture.postNativeBuffer(buffer))
			return
		/* The CPU fallback copies only when the consumer took the last frame. */
		if (texture != null && texture.acceptsFrame()) {
			/* every plane: a viewfinder given only the luma plane would be a
			 * grey picture of a colour camera */
			val n = buffer.planeCount
			texture.postFrame(
				buffer.width, buffer.height, buffer.format, buffer.timestamp,
				Array(n) { buffer.readPlane(it) },
				IntArray(n) { buffer.rowStride(it) },
				IntArray(n) { buffer.pixelStride(it) },
			)
		}
		buffer.release()
	}
}
