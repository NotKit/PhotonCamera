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
 * while the preview is running.  The conversion itself is [PreviewConverter]'s
 * worker, not the composition thread's.
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.Stroke
import android.graphics.Point
import android.hardware.camera2.CameraMetadata
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import com.particlesdevs.photoncamera.capture.PreviewSurface
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SurfaceOrigin
import photoncam.HostGpuFrame
import photoncam.atlcamera.atl_preview_texture_free
import photoncam.atlcamera.atl_preview_texture_new
import photoncam.atlcamera.atl_preview_texture_update
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
	var width = 0
		private set
	var height = 0
		private set

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

	/* The Android renderer holds the last frame across a same-sensor lens
	 * switch until the new stream settles.  This viewfinder draws whatever
	 * frame arrives, so there is nothing to hold and the switch shows as is. */
	override fun beginPreviewSettleTracking() {}

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
fun BoxScope.CameraViewfinder(surface: ComposePreviewSurface, overlay: ViewfinderOverlay) {
	val texture = surface.getSurfaceTexture()
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var lastSize by remember { mutableStateOf(IntSize.Zero) }
	// Skia holds the pixels; an image that is not closed leaks a frame's worth
	// of native memory every frame.
	val open = remember { arrayOfNulls<Image>(1) }
	DisposableEffect(open) { onDispose { open[0]?.close(); open[0] = null } }

	val converter = remember { PreviewConverter() }
	DisposableEffect(converter) { onDispose { converter.close() } }

	val gpu = remember(texture) { GpuPreviewRenderer(texture) }
	DisposableEffect(gpu) {
		val update: (DirectContext) -> Unit = { gpu.update(it) }
		HostGpuFrame.update = update
		onDispose {
			if (HostGpuFrame.update === update)
				HostGpuFrame.update = null
			gpu.close()
		}
	}

	LaunchedEffect(texture) {
		var drawn = 0L
		var reported = 0L
		var reportedAt = 0L
		var convertNanos = 0L
		while (true) {
			withFrameNanos { nanos ->
				val raster = converter.take()
				if (raster != null) {
					drawn++
					convertNanos += raster.nanos
					val img = raster.toSkiaImage()
					open[0]?.close()
					open[0] = img
					image = img.toComposeImageBitmap()
					lastSize = IntSize(img.width, img.height)
					// One line per 60 drawn frames: proof the stream is live
					// without a log that is only the preview.
					if (drawn - reported >= 60L || reported == 0L) {
						val n = (drawn - reported).coerceAtLeast(1)
						val wall = if (reportedAt == 0L) 0L else (nanos - reportedAt) / n / 1_000_000
						println("[pc] preview frame $drawn ${converter.source}" +
							" dropped=${texture.dropped}" +
							" convert=${convertNanos / n / 1_000_000}ms frame=${wall}ms")
						reported = drawn
						reportedAt = nanos
						convertNanos = 0L
					}
				}
				// Taking it is what lets the producer copy the next one, so it
				// is taken only when the converter can start on it at once.
				if (!converter.busy()) texture.takeFrame()?.let { converter.submit(it) }
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
		Canvas(Modifier.fillMaxSize()) {
			val shown = gpu.image ?: image
			val shownSize = if (gpu.image != null) gpu.size else lastSize
			if (shown != null)
				drawPreview(shown, shownSize, surface.orientationDegrees - 90, surface.mirror)
		}
		// The indicators go on top of the frame and INSIDE the box, which is
		// where viewfinder_stack.xml had their Views; the coordinates
		// TouchFocus was given are the box's own.
		val focus = overlay.focusAt
		val spot = overlay.spotWbAt
		if (focus != null || spot != null) {
			Canvas(Modifier.fillMaxSize()) {
				if (focus != null) drawFocusCircle(focus, overlay.afState)
				if (spot != null) drawSpotWb(spot, overlay.spotWbError != null)
			}
		}
	}
}

/** Imports the HAL buffer, converts it on the GPU and gives Skia a 2D texture. */
@OptIn(ExperimentalForeignApi::class)
private class GpuPreviewRenderer(private val texture: SurfaceTexture) {
	private var native = atl_preview_texture_new()
	private var skia: Image? = null
	private var held: photoncam.camera.CameraBuffer? = null
	private var failed = false
	private var frames = 0L

	// SNAPSHOT STATE, not plain fields: the import runs on the render thread
	// just before scene.render, and a plain field would leave the Canvas with
	// no reason to draw again -- the frame would only appear when something
	// else (a tap) happened to invalidate the scene.
	private val imageState = mutableStateOf<ImageBitmap?>(null)
	private val sizeState = mutableStateOf(IntSize.Zero)

	val image: ImageBitmap? get() = imageState.value
	val size: IntSize get() = sizeState.value

	fun update(context: DirectContext) {
		if (failed)
			return
		val frame = texture.takeNativeBuffer() ?: return
		val state = native
		if (state == null) {
			frame.release()
			return
		}
		memScoped {
			val width = alloc<IntVar>()
			val height = alloc<IntVar>()
			val name = atl_preview_texture_update(
				state, frame.nativeBuffer, frame.width, frame.height, width.ptr, height.ptr)
			if (name == 0u) {
				frame.release()
				fail()
				return
			}
			val backend = BackendTexture.makeGL(
				width.value, height.value, false, name.toInt(),
				GLES20.GL_TEXTURE_2D, GL_RGBA8,
			)
			val next = try {
				Image.adoptTextureFrom(
					context, backend, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888)
			} catch (e: Throwable) {
				GLES20.glDeleteTextures(1, intArrayOf(name.toInt()), 0)
				backend.close()
				frame.release()
				fail()
				return
			}
			backend.close()
			val previousImage = skia
			val previousBuffer = held
			skia = next
			held = frame
			imageState.value = next.toComposeImageBitmap()
			sizeState.value = IntSize(width.value, height.value)
			previousImage?.close()
			previousBuffer?.release()
			frames++
			if (frames == 1L)
				println("[pc] preview GPU import ${frame.width}x${frame.height} -> ${width.value}x${height.value}")
		}
	}

	private fun fail() {
		if (failed)
			return
		failed = true
		texture.disableNativeBuffers()
		native?.let { atl_preview_texture_free(it) }
		native = null
		skia?.close()
		skia = null
		imageState.value = null
		held?.release()
		held = null
		println("[pc] preview GPU import unavailable; using CPU conversion")
	}

	fun close() {
		native?.let { atl_preview_texture_free(it) }
		native = null
		skia?.close()
		skia = null
		imageState.value = null
		held?.release()
		held = null
	}
}

private const val GL_RGBA8 = 0x8058

/**
 * The focus circle, coloured by AF state as FocusCircleView coloured it: white
 * while the lens is moving, green on a lock, red when the lock failed.
 */
private fun DrawScope.drawFocusCircle(at: Offset, afState: Int) {
	val colour = when (afState) {
		CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
		CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> Color(0xFF4CAF50)
		CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
		CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> Color(0xFFE53935)
		else -> Color.White
	}
	val r = minOf(size.width, size.height) * FOCUS_RADIUS_FRACTION
	drawCircle(colour, radius = r, center = at, style = Stroke(width = 3f))
	drawCircle(colour, radius = 3f, center = at)
}

/** The spot-WB reticle: a square, red once the measurement has failed. */
private fun DrawScope.drawSpotWb(at: Offset, failed: Boolean) {
	val colour = if (failed) Color(0xFFE53935) else Color(0xFFFFC107)
	val r = minOf(size.width, size.height) * SPOT_WB_RADIUS_FRACTION
	drawRect(
		colour,
		topLeft = Offset(at.x - r, at.y - r),
		size = Size(r * 2f, r * 2f),
		style = Stroke(width = 3f),
	)
}

/** Both indicators are sized off the box, as the layout's dp figures were. */
internal const val FOCUS_RADIUS_FRACTION = 0.09f
internal const val SPOT_WB_RADIUS_FRACTION = 0.05f

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
 * The conversion, on a worker of its own.  Under withFrameNanos it was the
 * whole of the composition thread's frame budget -- and so the shutter ring's,
 * the carousel's and every touch's, which is what a capture made visible: the
 * pipeline takes the cores, the conversion takes longer, and the screen slows
 * with it.  ONE frame in flight, so a slow one drops rather than queues.
 */
private class PreviewConverter {
	class Raster(val bytes: ByteArray, val width: Int, val height: Int, val nanos: Long) {
		fun toSkiaImage(): Image = Image.makeRaster(
			ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), bytes, width * 4)
	}

	private val worker = Worker.start(name = "preview-convert")
	private var pending: Future<Raster?>? = null

	/** the newest frame's own shape, for the preview log line */
	var source: String = ""
		private set

	fun busy(): Boolean = pending != null

	fun submit(frame: SurfaceTexture.Frame) {
		source = "${frame.width}x${frame.height} format=0x${frame.format.toString(16)}" +
			" planes=${frame.planes.size}"
		pending = worker.execute(TransferMode.SAFE, { frame }) { it.toRaster() }
	}

	/** The finished raster, once; null while one is still in flight.  A frame
	 *  that threw clears the slot too, or the preview never starts again. */
	fun take(): Raster? {
		val f = pending ?: return null
		if (f.state == FutureState.SCHEDULED) return null
		pending = null
		return if (f.state == FutureState.COMPUTED) f.result else null
	}

	fun close() {
		pending = null
		worker.requestTermination(processScheduledJobs = false)
	}
}

/**
 * YUV_420_888 -> RGBA, at whatever stride the producer chose.
 *
 * The chroma planes are half resolution and may be INTERLEAVED (pixel stride 2
 * is NV12/NV21, which is what most HALs hand out), so both strides are read per
 * plane rather than assumed.  Anything that is not three-plane 4:2:0 is drawn
 * from its luma alone -- grey, and visibly so, rather than a wrong colour that
 * reads as a camera fault.
 */
private fun SurfaceTexture.Frame.toRaster(): PreviewConverter.Raster? {
	val t0 = nanoTime()
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
	// The last index each plane is asked for on a row is a property of the
	// strides, so it is tested ONCE per row and the inner loop then runs
	// unchecked.  Per pixel it was three bounds tests and a branch for every
	// one of a million pixels, and that is most of what the conversion cost.
	val yRowLast = (w - 1) * step * yPixel
	val cRowLast = (((w - 1) * step) shr 1)
	for (row in 0 until h) {
		val srcRow = row * step
		val yBase = srcRow * yStride
		val cBase = (srcRow shr 1)
		val uBase = cBase * uStride
		val vBase = cBase * vStride
		val yOk = yBase + yRowLast < y.size
		val cOk = u != null && v != null &&
			uBase + cRowLast * uPixel < u.size && vBase + cRowLast * vPixel < v.size
		if (yOk && cOk) {
			// the whole row is there, in both planes: the hot path
			val uu = u!!
			val vv = v!!
			var yi = yBase
			var ui = uBase
			var vi = vBase
			val yAdd = step * yPixel
			// step >= 1, so a chroma sample is shared by every 2/step output
			// pixels; at step 2 (a 2304-wide preview) that is one each.
			var col = 0
			while (col < w) {
				val yv = y[yi].toInt() and 0xFF
				val cb = (uu[ui].toInt() and 0xFF) - 128
				val cr = (vv[vi].toInt() and 0xFF) - 128
				// BT.601 full range, in fixed point: the HAL's YUV_420_888
				// is JFIF-ranged, not video-ranged.
				out[o] = clamp(yv + ((91881 * cr) shr 16))
				out[o + 1] = clamp(yv - ((22554 * cb + 46802 * cr) shr 16))
				out[o + 2] = clamp(yv + ((116130 * cb) shr 16))
				out[o + 3] = 0xFF.toByte()
				o += 4
				col++
				yi += yAdd
				ui = uBase + (((col * step) shr 1) * uPixel)
				vi = vBase + (((col * step) shr 1) * vPixel)
			}
		} else {
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
	}
	return PreviewConverter.Raster(out, w, h, nanoTime() - t0)
}

/** The long side the preview raster is built at; see [toRaster]. */
private const val PREVIEW_MAX = 1280

private fun clamp(v: Int): Byte = when {
	v < 0 -> 0
	v > 255 -> 255.toByte()
	else -> v.toByte()
}
