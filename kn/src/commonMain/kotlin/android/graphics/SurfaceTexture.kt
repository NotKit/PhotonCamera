package android.graphics

/**
 * The GL texture a camera preview stream is drawn into.  atlas fills it from
 * native; here the frames arrive as camera buffers and the upload is the GL
 * lane's (android.opengl), so this carries the geometry, the listener and the
 * latest frame, and nothing that needs a GL context of its own.
 *
 * THE FRAME CROSSES A THREAD.  postFrame runs on the camera backend's thread
 * and the viewfinder takes the frame on the composition thread, so a frame is
 * an immutable object published through one volatile reference: whoever reads
 * it gets a whole frame or the previous one, never half of each.
 *
 * ONE FRAME IS HELD, NOT A QUEUE.  A preview stream runs at the sensor's rate
 * and the scene draws at the panel's, and the buffer has to be COPIED out of
 * the producer before it is given back.  Copying every one of them is 6 MB a
 * frame at 2304x1728, ninety times a second, and on the phone that starved the
 * render loop to four frames in twenty-five seconds -- the picture was live and
 * the window was not.  So a frame is copied only when the last one has been
 * taken; the rest are dropped, which is what a viewfinder wants anyway.
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
		/* the upload happens in the GL lane's preview renderer, which reads
		 * latest; there is no external-OES producer to latch here */
	}

	fun release() {
		listener = null
		latest = null
	}
}
