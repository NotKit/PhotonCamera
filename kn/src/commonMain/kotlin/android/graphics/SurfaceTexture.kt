package android.graphics

/**
 * The GL texture a camera preview stream is drawn into.  atlas fills it from
 * native; here the frames arrive as camera buffers and the upload is the GL
 * lane's (android.opengl), so this carries the geometry, the listener and the
 * latest frame, and nothing that needs a GL context of its own.
 */
open class SurfaceTexture(private val texName: Int) {

	fun interface OnFrameAvailableListener {
		fun onFrameAvailable(surfaceTexture: SurfaceTexture?)
	}

	private var defaultWidth = 0
	private var defaultHeight = 0
	private var listener: OnFrameAvailableListener? = null
	private var frameTimestamp = 0L
	/** the newest frame's pixels, in the stream's own format */
	var latestFrame: ByteArray? = null
		private set

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

	fun getTimestamp(): Long = frameTimestamp

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

	/** called by the camera when a preview buffer lands on this texture */
	fun postFrame(pixels: ByteArray?, timestamp: Long) {
		latestFrame = pixels
		frameTimestamp = timestamp
		listener?.onFrameAvailable(this)
	}

	fun updateTexImage() {
		/* the upload happens in the GL lane's preview renderer, which reads
		 * latestFrame; there is no external-OES producer to latch here */
	}

	fun release() {
		listener = null
		latestFrame = null
	}
}
