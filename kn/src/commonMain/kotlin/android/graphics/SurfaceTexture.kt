package android.graphics

import photoncam.camera.CameraBuffer
import photoncam.camera.SpinLock

/**
 * A newest-frame mailbox between the camera and Compose GL threads. Native
 * buffers stay borrowed for GPU import; copied planes provide the fallback.
 */
open class SurfaceTexture(private val texName: Int) {

	fun interface OnFrameAvailableListener {
		fun onFrameAvailable(surfaceTexture: SurfaceTexture?)
	}

	/**
	 * One delivered buffer, copied out of the producer's.  [serial] counts
	 * frames since the texture was made, so a consumer can tell a new frame
	 * from the one it drew last without comparing pixels.
	 */
	class Frame(
		val serial: Long,
		val width: Int,
		val height: Int,
		val format: Int,
		val timestamp: Long,
		val planes: Array<ByteArray>,
		val rowStrides: IntArray,
		val pixelStrides: IntArray,
	)

	private var defaultWidth = 0
	private var defaultHeight = 0
	private var listener: OnFrameAvailableListener? = null
	private var frames = 0L
	private val nativeLock = SpinLock()
	private var nativeLatest: CameraBuffer? = null
	@kotlin.concurrent.Volatile
	private var nativeEnabled = true

	/** the frame waiting to be taken, or null when the consumer is up to date */
	@kotlin.concurrent.Volatile
	var latest: Frame? = null
		private set

	/** frames the producer offered while one was still waiting */
	@kotlin.concurrent.Volatile
	var dropped = 0L
		private set

	/** the waiting frame's first plane, in the stream's own format */
	val latestFrame: ByteArray?
		get() = latest?.planes?.firstOrNull()

	/**
	 * Whether a frame would be kept if one were posted now.  The producer asks
	 * BEFORE it copies, which is the whole point: a dropped frame must cost
	 * nothing.
	 */
	fun acceptsFrame(): Boolean = latest == null

	/** The waiting frame, taken; null when there is none. */
	fun takeFrame(): Frame? {
		val f = latest
		latest = null
		return f
	}

	/** Retains the newest gralloc frame for the Compose GL thread. */
	fun postNativeBuffer(buffer: CameraBuffer): Boolean {
		if (!nativeEnabled || !buffer.hasNativeBuffer)
			return false
		var stale: CameraBuffer? = null
		val accepted = nativeLock.withLock {
			if (!nativeEnabled) {
				false
			} else {
				stale = nativeLatest
				nativeLatest = buffer
				true
			}
		}
		if (!accepted)
			return false
		if (stale != null) {
			dropped++
			stale?.release()
		}
		listener?.onFrameAvailable(this)
		// The scene reads this frame from a plain field, so nothing here
		// invalidates Compose; without asking for a render the loop idles and
		// the viewfinder holds whatever it drew last.
		photoncam.host.HostRender.requestRender()
		return true
	}

	fun takeNativeBuffer(): CameraBuffer? = nativeLock.withLock {
		val frame = nativeLatest
		nativeLatest = null
		frame
	}

	fun disableNativeBuffers() {
		nativeEnabled = false
		val frame = takeNativeBuffer()
		frame?.release()
	}

	fun getTexName(): Int = texName

	fun setDefaultBufferSize(width: Int, height: Int) {
		defaultWidth = width
		defaultHeight = height
	}

	fun getWidth(): Int = defaultWidth
	fun getHeight(): Int = defaultHeight

	fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?) {
		this.listener = listener
	}

	fun getTimestamp(): Long = latest?.timestamp ?: 0L

	/** the identity transform: this port never flips or rotates in the texture */
	fun getTransformMatrix(mtx: FloatArray?) {
		if (mtx == null || mtx.size < 16)
			return
		for (i in 0 until 16)
			mtx[i] = 0f
		mtx[0] = 1f
		mtx[5] = 1f
		mtx[10] = 1f
		mtx[15] = 1f
	}

	/** Called by the camera when a preview buffer lands on this texture. */
	fun postFrame(
		width: Int,
		height: Int,
		format: Int,
		timestamp: Long,
		planes: Array<ByteArray>,
		rowStrides: IntArray,
		pixelStrides: IntArray,
	) {
		if (latest != null) {
			dropped++
			return
		}
		frames++
		latest = Frame(frames, width, height, format, timestamp, planes, rowStrides, pixelStrides)
		listener?.onFrameAvailable(this)
	}

	fun updateTexImage() {
		/* The Compose host latches native buffers on its GL thread. */
	}

	fun release() {
		listener = null
		latest = null
		disableNativeBuffers()
	}
}
