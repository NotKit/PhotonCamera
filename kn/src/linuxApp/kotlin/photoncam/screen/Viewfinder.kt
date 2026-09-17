/* THE VIEWFINDER -- the live preview inside the Compose scene.  -PwithApp only.
 *
 * On Android CaptureController drives a GL view: the camera fills an external
 * OES texture and a shader draws it.  There is no View layer here and no second
 * GL context to give the camera, so the seam the Java already cuts --
 * capture/PreviewSurface -- is implemented by [ComposePreviewSurface], and the
 * frames arrive as ordinary buffers that this file turns into ONE SKIA RASTER
 * the scene composites.  That is the same bargain fenix-kn struck for Gecko's
 * output (`fenixkn/page/PageArea.kt`): foreign pixels enter the scene as a
 * raster, not as a surface the scene has to make room for.
 *
 * WHERE THE FRAME COMES FROM.  CameraDeviceNative routes a delivered buffer to
 * the SurfaceTexture behind the output Surface, on the camera backend's thread.
 * Nothing here touches Compose from that thread: the texture holds the newest
 * frame in one volatile reference and this file picks it up under
 * `withFrameNanos`, on the composition thread.  Awaiting that clock is also what
 * keeps `scene.hasInvalidations()` true, so MgwlComposeHost keeps rendering
 * while the preview is running.
 */
package photoncam.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import android.graphics.Point
import android.graphics.SurfaceTexture
import com.particlesdevs.photoncamera.capture.PreviewSurface
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import photoncam.camera.CameraSession
import photoncam.host.HostWindow
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.getenv
import platform.posix.timespec

/**
 * capture/PreviewSurface over the Compose scene.
 *
 * AVAILABILITY IS A REAL EVENT HERE and not a constant.  On Android the surface
 * comes up when the GL view's texture is created, and CaptureController branches
 * on it: available means "a camera was already open against this texture, open
 * it again", and unavailable means "arm the listener, and open when it appears".
 * The Compose analogue of the texture appearing is the viewfinder slot being
 * laid out, which is when [markAvailable] is called -- so the first open goes
 * through the listener, exactly as it does on the phone's Java build.
 */
class ComposePreviewSurface : PreviewSurface {

	private val texture = SurfaceTexture(0)
	private var listener: PreviewSurface.Listener? = null
	private var available = false

	/** what setAspectRatio was given, in preview-buffer pixels */
	var aspectWidth: Int = 0
		private set
	var aspectHeight: Int = 0
		private set
	/** CaptureController hands the GL path `sensorOrientation + 90`. */
	var orientationDegrees: Int = 0
		private set
	var mirror: Boolean = false
		private set

	override fun getSurfaceTexture(): SurfaceTexture = texture

	override fun isAvailable(): Boolean = available

	override fun setListener(listener: PreviewSurface.Listener?) {
		this.listener = listener
		// A listener armed after the slot was laid out has already missed the
		// event, so it is told at once; openCamera's own in-flight guard is
		// what makes a second telling harmless.
		if (available) listener?.onPreviewSurfaceAvailable(texture, width, height)
	}

	/** The viewfinder slot has been laid out: [width] x [height] in pixels. */
	fun markAvailable(width: Int, height: Int) {
		val first = !available
		this.width = width
		this.height = height
		available = true
		if (first) listener?.onPreviewSurfaceAvailable(texture, width, height)
		else listener?.onPreviewSurfaceSizeChanged(texture, width, height)
	}

	/** the slot's size in pixels, which is what the listener is told */
	private var width = 0
	private var height = 0

	override fun setAspectRatio(width: Int, height: Int) {
		aspectWidth = width
		aspectHeight = height
	}

	override fun setCameraSize(size: Point?) {
	}

	override fun setOrientation(degrees: Int) {
		orientationDegrees = degrees
	}

	override fun setMirror(mirror: Boolean) {
		this.mirror = mirror
	}

	override fun getDisplaySize(): Point = Point(HostWindow.widthPx, HostWindow.heightPx)

	fun release() {
		val l = listener
		listener = null
		available = false
		// The listener is told before the texture goes, as the View callback
		// is; what it answers only decides who frees it, and here that is us.
		l?.onPreviewSurfaceDestroyed(texture)
		texture.release()
	}
}

/**
 * The preview.  Aspect-FILL, because that is what a viewfinder does: the box
 * CameraScreen pins is the frame the picture is composed in, and a letterboxed
 * preview would show the user a different crop from the one the shutter takes.
 */
@Composable
fun BoxScope.CameraViewfinder(surface: ComposePreviewSurface) {
	val texture = surface.getSurfaceTexture()
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var lastSize by remember { mutableStateOf(IntSize.Zero) }
	// Skia holds the pixels; an image that is not closed leaks a frame's worth
	// of native memory every frame.
	val open = remember { arrayOfNulls<Image>(1) }
	DisposableEffect(open) { onDispose { open[0]?.close(); open[0] = null } }

	LaunchedEffect(texture) {
		var drawn = 0L
		var reported = 0L
		var reportedAt = 0L
		var convertNanos = 0L
		while (true) {
			withFrameNanos { nanos ->
				// Taking it is what lets the producer copy the next one.
				val frame = texture.takeFrame()
				if (frame != null) {
					drawn++
					val t0 = nanoTime()
					val img = frame.toSkiaImage()
					convertNanos += nanoTime() - t0
					if (img != null) {
						open[0]?.close()
						open[0] = img
						image = img.toComposeImageBitmap()
						lastSize = IntSize(img.width, img.height)
					}
					// One line per 60 drawn frames: proof the stream is live
					// without a log that is only the preview.
					if (drawn - reported >= 60L || reported == 0L) {
						val n = (drawn - reported).coerceAtLeast(1)
						val wall = if (reportedAt == 0L) 0L else (nanos - reportedAt) / n / 1_000_000
						println("[pc] preview frame $drawn ${frame.width}x${frame.height}" +
							" format=0x${frame.format.toString(16)} planes=${frame.planes.size}" +
							" dropped=${texture.dropped}" +
							" convert=${convertNanos / n / 1_000_000}ms frame=${wall}ms")
						reported = drawn
						reportedAt = nanos
						convertNanos = 0L
					}
				}
			}
		}
	}

	// onSizeChanged is this port's onSurfaceTextureAvailable: the slot having a
	// size is the moment the preview surface exists, and it is what lets
	// CaptureController open the camera.
	Box(
		Modifier
			.fillMaxSize()
			.background(Color.Black)
			.onSizeChanged { surface.markAvailable(it.width, it.height) },
	) {
		val shown = image
		if (shown != null) {
			Canvas(Modifier.fillMaxSize()) {
				drawPreview(shown, lastSize, surface.orientationDegrees - 90, surface.mirror)
			}
		}
	}
}

/**
 * The frame, rotated upright and scaled to cover.
 *
 * [degrees] is clockwise: the buffer comes off the sensor in ITS orientation and
 * the panel is held in the device's, so a back camera whose SENSOR_ORIENTATION
 * is 90 needs its landscape buffer turned a quarter turn.  PC_PREVIEW_ROTATE
 * overrides the figure outright, which is how a bring-up on a phone nobody is
 * watching settles the question in one run instead of one rebuild.
 */
private fun DrawScope.drawPreview(image: ImageBitmap, srcSize: IntSize, degrees: Int, mirror: Boolean) {
	if (srcSize.width <= 0 || srcSize.height <= 0) return
	val rot = ((rotateOverride ?: degrees) % 360 + 360) % 360
	val quarter = rot == 90 || rot == 270
	// The frame's footprint after the rotation, which is what has to cover the box.
	val outW = if (quarter) srcSize.height.toFloat() else srcSize.width.toFloat()
	val outH = if (quarter) srcSize.width.toFloat() else srcSize.height.toFloat()
	val cover = maxOf(size.width / outW, size.height / outH)
	val w = srcSize.width * cover
	val h = srcSize.height * cover

	rotate(rot.toFloat()) {
		scale(if (mirror) -1f else 1f, 1f) {
			drawImage(
				image,
				dstOffset = IntOffset(
					((size.width - w) / 2f).toInt(),
					((size.height - h) / 2f).toInt(),
				),
				dstSize = IntSize(w.toInt(), h.toInt()),
			)
		}
	}
}

@OptIn(ExperimentalForeignApi::class)
private fun nanoTime(): Long = kotlinx.cinterop.memScoped {
	val ts = alloc<timespec>()
	clock_gettime(CLOCK_MONOTONIC.toInt(), ts.ptr)
	ts.tv_sec * 1_000_000_000L + ts.tv_nsec
}

@OptIn(ExperimentalForeignApi::class)
private val rotateOverride: Int? =
	getenv("PC_PREVIEW_ROTATE")?.toKString()?.toIntOrNull()

/**
 * YUV_420_888 -> RGBA, at whatever stride the producer chose.
 *
 * The chroma planes are half resolution and may be INTERLEAVED (pixel stride 2
 * is NV12/NV21, which is what most HALs hand out), so both strides are read per
 * plane rather than assumed.  Anything that is not three-plane 4:2:0 is drawn
 * from its luma alone -- grey, and visibly so, rather than a wrong colour that
 * reads as a camera fault.
 */
private fun SurfaceTexture.Frame.toSkiaImage(): Image? {
	if (width <= 0 || height <= 0 || planes.isEmpty()) return null
	// EVERY PIXEL IS CONVERTED BY THE CPU, so the raster is built at the size
	// the screen can actually show rather than the sensor's.  A 2304x1728
	// preview buffer is 4 MP a frame, and converting all of it costs more than
	// the panel is ever asked to display; sampling every step'th pixel holds
	// the long side at PREVIEW_MAX and the frame rate with it.
	val step = ((maxOf(width, height) + PREVIEW_MAX - 1) / PREVIEW_MAX).coerceAtLeast(1)
	val w = width / step
	val h = height / step
	if (w <= 0 || h <= 0) return null
	val y = planes[0]
	val yStride = rowStrides.getOrElse(0) { width }
	val yPixel = pixelStrides.getOrElse(0) { 1 }.coerceAtLeast(1)
	val colour = planes.size >= 3 && format == CameraSession.FORMAT_YUV_420_888
	val u = if (colour) planes[1] else null
	val v = if (colour) planes[2] else null
	val uStride = rowStrides.getOrElse(1) { width / 2 }
	val vStride = rowStrides.getOrElse(2) { width / 2 }
	val uPixel = pixelStrides.getOrElse(1) { 1 }.coerceAtLeast(1)
	val vPixel = pixelStrides.getOrElse(2) { 1 }.coerceAtLeast(1)

	val out = ByteArray(w * h * 4)
	var o = 0
	for (row in 0 until h) {
		val srcRow = row * step
		val yBase = srcRow * yStride
		val cBase = (srcRow shr 1)
		val uBase = cBase * uStride
		val vBase = cBase * vStride
		for (col in 0 until w) {
			val srcCol = col * step
			val yi = yBase + srcCol * yPixel
			val yv = if (yi < y.size) (y[yi].toInt() and 0xFF) else 0
			var r = yv
			var g = yv
			var b = yv
			if (u != null && v != null) {
				val ui = uBase + (srcCol shr 1) * uPixel
				val vi = vBase + (srcCol shr 1) * vPixel
				if (ui < u.size && vi < v.size) {
					// BT.601 full range, in fixed point: the HAL's YUV_420_888
					// is JFIF-ranged, not video-ranged.
					val cb = (u[ui].toInt() and 0xFF) - 128
					val cr = (v[vi].toInt() and 0xFF) - 128
					r = yv + ((91881 * cr) shr 16)
					g = yv - ((22554 * cb + 46802 * cr) shr 16)
					b = yv + ((116130 * cb) shr 16)
				}
			}
			out[o] = clamp(r)
			out[o + 1] = clamp(g)
			out[o + 2] = clamp(b)
			out[o + 3] = 0xFF.toByte()
			o += 4
		}
	}
	return Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), out, w * 4)
}

/** The long side the preview raster is built at; see [toSkiaImage]. */
private const val PREVIEW_MAX = 1280

private fun clamp(v: Int): Byte = when {
	v < 0 -> 0
	v > 255 -> 255.toByte()
	else -> v.toByte()
}
