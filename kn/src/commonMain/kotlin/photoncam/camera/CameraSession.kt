@file:OptIn(ExperimentalForeignApi::class)

package photoncam.camera

import kotlinx.cinterop.*
import cnames.structs.atl_camera
import cnames.structs.atl_camera_metadata
import cnames.structs.atl_camera_streams
import java.nio.ByteBuffer
import photoncam.atlcamera.*

/**
 * One open camera and its stream session, over atl-touch's camera backend
 * (host/atlcamera).  This is what android.hardware.camera2.impl.CameraDeviceNative
 * calls: atlas has the same thing in C as a JNI file, but the consumers it
 * routes buffers to - ImageReader, SurfaceTexture - live in Kotlin here, so the
 * routing does too.
 *
 * Every callback arrives on a backend thread, exactly as it does in atlas
 * before its JNI layer posts it to the main loop.
 */
class CameraSession(val sink: Sink) {

	/** AOSP: ICameraDeviceCallbacks. */
	interface Sink {
		fun onCaptureStarted(requestId: Int, frameNumber: Long, timestamp: Long)
		/** the bag is the listener's from here on */
		fun onCaptureResult(requestId: Int, frameNumber: Long, resultPtr: Long)
		fun onCaptureFailed(requestId: Int, frameNumber: Long)
		fun onBuffer(buffer: CameraBuffer)
		fun onBufferLost(requestId: Int, frameNumber: Long, stream: Int)
	}

	private var backend: CPointer<atl_camera_backend>? = null
	private var camera: CPointer<atl_camera>? = null
	private var streams: CPointer<atl_camera_streams>? = null
	private var callbacks: atl_camera_stream_callbacks? = null
	private var selfRef: StableRef<CameraSession>? = null
	/* the strings the stream configurations point at have to outlive the call */
	private var physicalIds: MutableList<CPointer<ByteVar>> = ArrayList()

	/** false when no backend can open this camera. */
	fun open(cameraId: String?): Boolean {
		if (cameraId == null)
			return false
		val b = atl_camera_backend_get() ?: return false
		val ids = b.pointed.get_camera2_id_list ?: return false
		val index = memScoped {
			val count = alloc<IntVar>()
			val list = ids.invoke(count.ptr) ?: return false
			(0 until count.value).firstOrNull { list[it]?.toKString() == cameraId } ?: -1
		}
		if (index < 0)
			return false
		val opened = b.pointed.open?.invoke(index) ?: return false

		val cb = nativeHeap.alloc<atl_camera_stream_callbacks>()
		cb.started = staticCFunction(::cbStarted)
		cb.result = staticCFunction(::cbResult)
		cb.failed = staticCFunction(::cbFailed)
		cb.buffer = staticCFunction(::cbBuffer)
		cb.buffer_lost = staticCFunction(::cbBufferLost)
		val ref = StableRef.create(this)
		val s = atl_camera_streams_new(b, opened, cb.ptr, ref.asCPointer())
		if (s == null) {
			ref.dispose()
			nativeHeap.free(cb)
			b.pointed.close?.invoke(opened)
			return false
		}
		backend = b
		camera = opened
		callbacks = cb
		selfRef = ref
		streams = s
		return true
	}

	/**
	 * Points the session at its outputs.  Returns the largest output's size,
	 * which is what the single backend stream is configured with, or null when
	 * the backend will not have them.
	 */
	fun configure(outputs: List<StreamConfig>): IntArray? {
		val s = streams ?: return null
		if (outputs.isEmpty() || outputs.size > 8)
			return null

		freePhysicalIds()
		val size = intArrayOf(0, 0)
		val configs = nativeHeap.allocArray<atl_camera_stream>(outputs.size)
		for (i in outputs.indices) {
			val o = outputs[i]
			configs[i].width = o.width
			configs[i].height = o.height
			configs[i].format = o.format
			configs[i].max_buffers = o.maxBuffers
			configs[i].usage = o.usage
			configs[i].physical_id = o.physicalId?.let {
				val c = platform.posix.strdup(it)!!
				physicalIds.add(c)
				c
			}
			if (o.width * o.height > size[0] * size[1]) {
				size[0] = o.width
				size[1] = o.height
			}
		}
		val ok = atl_camera_streams_configure(s, configs, outputs.size, null)
		nativeHeap.free(configs)
		if (!ok) {
			freePhysicalIds()
			return null
		}
		return size
	}

	fun submit(requestId: Int, settingsPtr: Long, targets: Int, repeating: Boolean): Boolean {
		val s = streams ?: return false
		val settings: CPointer<atl_camera_metadata>? = settingsPtr.toCPointer()
		return atl_camera_streams_submit(s, requestId, settings, targets.toUInt(), repeating)
	}

	fun stopRepeating() {
		streams?.let { atl_camera_streams_cancel_repeating(it) }
	}

	/** the ids of the one-shot requests dropped before they took a frame */
	fun flush(): IntArray {
		val s = streams ?: return IntArray(0)
		return memScoped {
			val ids = allocArray<IntVar>(64)
			val n = atl_camera_streams_flush(s, ids, 64)
			IntArray(if (n < 0) 0 else n) { ids[it] }
		}
	}

	fun close() {
		streams?.let { atl_camera_streams_free(it) }
		streams = null
		camera?.let { backend?.pointed?.close?.invoke(it) }
		camera = null
		callbacks?.let { nativeHeap.free(it) }
		callbacks = null
		selfRef?.dispose()
		selfRef = null
		freePhysicalIds()
	}

	private fun freePhysicalIds() {
		for (p in physicalIds)
			platform.posix.free(p)
		physicalIds.clear()
	}

	/** one output as the backend wants it described (AOSP: an OutputConfiguration) */
	class StreamConfig(
		val width: Int,
		val height: Int,
		val format: Int,
		val maxBuffers: Int,
		val usage: ULong,
		val physicalId: String?,
	)

	companion object {
		/* android.graphics.ImageFormat values a stream can be in; camera_backend.h */
		const val FORMAT_RAW_SENSOR = 0x20
		const val FORMAT_PRIVATE = 0x22
		const val FORMAT_YUV_420_888 = 0x23
		const val FORMAT_RAW10 = 0x25
		const val FORMAT_RAW12 = 0x26
		const val FORMAT_JPEG = 0x100

		const val USAGE_CPU_READ_OFTEN = 3UL
		const val USAGE_GPU_SAMPLED_IMAGE = 256UL
	}
}

/** One filled buffer of one stream; the consumer owns it until release(). */
@OptIn(ExperimentalForeignApi::class)
class CameraBuffer internal constructor(private val ptr: CPointer<atl_camera_buffer>) {
	val stream: Int get() = ptr.pointed.stream
	val width: Int get() = ptr.pointed.width
	val height: Int get() = ptr.pointed.height
	val format: Int get() = ptr.pointed.format
	val timestamp: Long get() = ptr.pointed.timestamp
	val planeCount: Int get() = ptr.pointed.n_planes
	val hasNativeBuffer: Boolean get() = ptr.pointed.native != null
	val nativeBuffer: COpaquePointer? get() = ptr.pointed.native

	fun rowStride(plane: Int): Int = ptr.pointed.planes[plane].row_stride
	fun pixelStride(plane: Int): Int = ptr.pointed.planes[plane].pixel_stride
	fun planeLength(plane: Int): Int = ptr.pointed.planes[plane].len

	/** A borrowed view, valid until [release]. */
	fun planeBuffer(plane: Int): ByteBuffer {
		val p = ptr.pointed.planes[plane]
		val data = p.data ?: return ByteBuffer.allocate(0)
		return ByteBuffer.wrapPointer(data, p.len)
	}

	/** the plane's bytes; a copy, because the buffer goes back to the producer. */
	fun readPlane(plane: Int): ByteArray {
		val p = ptr.pointed.planes[plane]
		val data = p.data ?: return ByteArray(0)
		val out = ByteArray(p.len)
		out.usePinned { pinned ->
			if (p.len > 0)
				platform.posix.memcpy(pinned.addressOf(0), data, p.len.convert())
		}
		return out
	}

	fun release() = atl_camera_buffer_release(ptr)
}

@OptIn(ExperimentalForeignApi::class)
private fun sessionOf(user: COpaquePointer?): CameraSession? =
	user?.asStableRef<CameraSession>()?.get()

@OptIn(ExperimentalForeignApi::class)
private fun cbStarted(user: COpaquePointer?, requestId: Int, frameNumber: Long, timestamp: Long) {
	sessionOf(user)?.sink?.onCaptureStarted(requestId, frameNumber, timestamp)
}

@OptIn(ExperimentalForeignApi::class)
private fun cbResult(user: COpaquePointer?, requestId: Int, frameNumber: Long,
                     result: CPointer<atl_camera_metadata>?) {
	sessionOf(user)?.sink?.onCaptureResult(requestId, frameNumber, result.rawValue.toLong())
}

@OptIn(ExperimentalForeignApi::class)
private fun cbFailed(user: COpaquePointer?, requestId: Int, frameNumber: Long) {
	sessionOf(user)?.sink?.onCaptureFailed(requestId, frameNumber)
}

@OptIn(ExperimentalForeignApi::class)
private fun cbBuffer(user: COpaquePointer?, buffer: CPointer<atl_camera_buffer>?) {
	val session = sessionOf(user)
	if (buffer == null)
		return
	if (session == null) {
		atl_camera_buffer_release(buffer)
		return
	}
	session.sink.onBuffer(CameraBuffer(buffer))
}

@OptIn(ExperimentalForeignApi::class)
private fun cbBufferLost(user: COpaquePointer?, requestId: Int, frameNumber: Long, stream: Int) {
	sessionOf(user)?.sink?.onBufferLost(requestId, frameNumber, stream)
}
