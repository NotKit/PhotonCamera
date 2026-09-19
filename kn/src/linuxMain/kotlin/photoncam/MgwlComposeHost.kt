package photoncam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ComposeUiMainDispatcher
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import net.thekit.mgwl.MGWL_PTR_AXIS
import net.thekit.mgwl.MGWL_PTR_BUTTON
import net.thekit.mgwl.MGWL_PTR_MOTION
import net.thekit.mgwl.MGWL_TOUCH_DOWN
import net.thekit.mgwl.MGWL_TOUCH_MOVE
import net.thekit.mgwl.MGWL_TOUCH_UP
import net.thekit.mgwl.mgwl_callbacks
import net.thekit.mgwl.mgwl_create
import net.thekit.mgwl.mgwl_create_window
import net.thekit.mgwl.mgwl_destroy
import net.thekit.mgwl.mgwl_egl_display
import net.thekit.mgwl.mgwl_egl_vendor
import net.thekit.mgwl.mgwl_gl_renderer
import net.thekit.mgwl.mgwl_height
import net.thekit.mgwl.mgwl_make_current
import net.thekit.mgwl.mgwl_pump
import net.thekit.mgwl.mgwl_scale
import net.thekit.mgwl.mgwl_set_callbacks
import net.thekit.mgwl.mgwl_should_close
import net.thekit.mgwl.mgwl_swap_buffers
import net.thekit.mgwl.mgwl_width
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.timespec

/** Work that must run with the window's GL context current, before Compose. */
object HostGpuFrame {
	var update: ((DirectContext) -> Unit)? = null
}

/**
 * A Compose scene rendered by skiko into the EGL context our own C host created,
 * with no AWT, no JVM and no `ak-window`.
 *
 * Transliterated from `firefox-atl/fenix-kn`'s host of the same name, with
 * everything Fenix removed: no Gecko process, no Maliit IME, no clipboard, no
 * content-hub share, no keyboard -- a camera has no text field.  What is left is
 * the window, the surface, the frame loop and pointer input.
 *
 * WHY NOT AURORA'S OWN WINDOW.  Aurora's `application {}` ends in
 * `Window.runWindow()`, a Rust winit/glutin loop (`libac_window.a`).  On Ubuntu
 * Touch it dies in `eglCreateWindowSurface` with `EGL_BAD_ATTRIBUTE` against
 * libhybris, and on a desktop wlroots it never gets a window at all -- its winit
 * fork binds `wl_shell` and `qt_surface_extension`.  `mgwl` is xdg_wm_base and
 * has neither problem.  Everything else in the Aurora distribution -- Compose and
 * skiko -- is stock upstream and windowing-free, so this is the only seam.
 *
 * Everything runs on the calling thread: libwayland is not casually thread-safe
 * and the EGL context is bound per-thread.
 */
@OptIn(ExperimentalForeignApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class MgwlComposeHost private constructor(private val handle: CPointer<cnames.structs.mgwl>, private val es: Int) {

	private lateinit var scene: ComposeScene

	/** The scene's platform, which on this host is one fact: how big the window
	 *  is.  Everything else is PlatformContext.Empty's. */
	private val windowInfo = HostWindowInfo()

	@OptIn(InternalComposeUiApi::class)
	private val hostPlatformContext = object : PlatformContext by PlatformContext.Empty {
		override val windowInfo: WindowInfo get() = this@MgwlComposeHost.windowInfo
	}
	private var directContext: DirectContext? = null
	private var renderTarget: BackendRenderTarget? = null
	private var skiaSurface: Surface? = null

	private var width = 0
	private var height = 0
	private var scale = 1
	val density = readDensity()

	private var surfaceDirty = true
	// Written by the camera's thread through HostRender, read by the loop.
	@kotlin.concurrent.Volatile
	private var needsRender = true
	private var closed = false

	private val trace = getenv("PC_TRACE") != null

	/** Called once the window and its GL context exist; [eglDisplay] is valid
	 *  from then on.  Main.kt hands the display to the app's EGL shim there. */
	var onWindowReady: (() -> Unit)? = null

	/** The window's EGLDisplay. Android has one per process and hybris means
	 *  it: a second eglInitialize fails while the compositor's display lives. */
	fun eglDisplay(): COpaquePointer? = mgwl_egl_display(handle)

	// PC_SHOT=/path/shot.png writes the rendered frame there: once at
	// PC_SHOT_FRAME (default 3) and once as the run ends, so the file holds the
	// last frame drawn.  PC_SHOT_EVERY_MS asks for periodic ones as well.
	// On the phone this is the ONLY way to see the window: Lomiri ships no
	// screenshot tool and exposes no D-Bus method for one.
	//
	// THROTTLED, because a grab is a full GPU readback plus a PNG encode plus a
	// write: ~2 s of a frame on the phone.  Grabbing every frame turned a 44 fps
	// viewfinder into 23 frames in a minute, and because grab() sits between the
	// flush and the swap it was PC_TIME's "swap" that carried the cost -- which
	// reads as the compositor starving the app.  It never was.
	private val shotPath = getenv("PC_SHOT")?.toKString()?.takeIf { it.isNotEmpty() }
	private val shotFrame = getenv("PC_SHOT_FRAME")?.toKString()?.toIntOrNull() ?: 3
	private val shotEveryNanos =
		(getenv("PC_SHOT_EVERY_MS")?.toKString()?.toLongOrNull() ?: 0L) * 1_000_000L
	private var lastShotNanos = 0L
	private var grabbedFinal = false

	private val timing = getenv("PC_TIME") != null
	private var sceneNanos = 0L
	private var flushNanos = 0L
	private var swapNanos = 0L
	private var shotNanos = 0L

	private val pointers = HashMap<Int, ComposeScenePointer>()
	private var mouseButtons = 0
	private var frames = 0L

	fun run(appId: String, title: String, w: Int, h: Int, maxSeconds: Int, content: @Composable () -> Unit) {
		val self = StableRef.create(this)
		memScoped {
			val cb = alloc<mgwl_callbacks>()
			cb.configure = staticCFunction { u, cw, ch ->
				u!!.asStableRef<MgwlComposeHost>().get().onConfigure(cw, ch)
			}
			cb.close = staticCFunction { u -> u!!.asStableRef<MgwlComposeHost>().get().closed = true }
			cb.touch = staticCFunction { u, id, x, y, phase ->
				u!!.asStableRef<MgwlComposeHost>().get().onTouch(id, x.toFloat(), y.toFloat(), phase)
			}
			cb.pointer = staticCFunction { u, kind, x, y, buttons ->
				u!!.asStableRef<MgwlComposeHost>().get().onPointer(kind, x.toFloat(), y.toFloat(), buttons.toInt())
			}
			mgwl_set_callbacks(handle, cb.ptr, self.asCPointer())
		}
		if (mgwl_create_window(handle, appId, title, w, h, es) != 0) error("mgwl_create_window failed")

		width = mgwl_width(handle)
		height = mgwl_height(handle)
		scale = maxOf(1, mgwl_scale(handle))
		mgwl_make_current(handle)
		// The window's EGLDisplay, for whoever needs one.  Named here and not
		// used: the shim is the app build's, and this file is in every build.
		onWindowReady?.invoke()
		println(
			"[pc] EGL_VENDOR=${mgwl_egl_vendor(handle)?.toKString()}" +
				" GL_RENDERER=${mgwl_gl_renderer(handle)?.toKString()}" +
				" size=${width}x$height scale=$scale density=$density"
		)

		// The panel, for whoever asks: the app's camera path sizes its preview
		// buffer off the display and the shim's Resources cannot answer.
		photoncam.host.HostWindow.set(bufWidth(), bufHeight(), density)

		// WITHOUT windowInfo EVERY POPUP LANDS AT THE WINDOW'S TOP-LEFT CORNER.
		// A PopupPositionProvider is handed the window's SIZE and asked where the
		// menu fits; the scene takes a `size` but leaves PlatformContext.Empty's
		// WindowInfo alone, so containerSize is 0x0, nothing fits anywhere, and
		// the fallback is the origin.  The anchor was right all along --
		// `[dbg] anchor pos=Offset(0.0, 872.0) container=0 x 0`.  Providing
		// LocalWindowInfo around the content is NOT enough: Popup reads the
		// platform context's, not the composition local's.
		windowInfo.containerSize = IntSize(bufWidth(), bufHeight())
		// ComposeUiMainDispatcher is Compose's own; it is public and, unlike
		// Aurora's window layer, has no ak-window in it.
		scene = CanvasLayersComposeScene(
			density = Density(density),
			layoutDirection = LayoutDirection.Ltr,
			size = IntSize(bufWidth(), bufHeight()),
			coroutineContext = ComposeUiMainDispatcher,
			platformContext = hostPlatformContext,
			invalidate = { needsRender = true },
		)
		ComposeUiMainDispatcher.setInvalidator { needsRender = true }
		// A camera buffer arrives off-thread and invalidates nothing on its
		// own; this is what gets the GPU preview its next render.
		photoncam.host.HostRender.invalidate = { needsRender = true }
		scene.setContent(content)
		loop(maxSeconds)

		photoncam.host.HostRender.invalidate = null
		skiaSurface?.close(); renderTarget?.close(); directContext?.close()
		scene.close()
		mgwl_destroy(handle)
		self.dispose()
		println("[pc] $frames frames rendered")
	}

	// Compose animates off this, so it has to be WALL time.  posix.clock() is
	// processor time -- an animation driven by it advances only while the process
	// is burning CPU, which in an event-driven loop is almost never.
	private fun monotonicNanos(): Long = memScoped {
		val ts = alloc<timespec>()
		clock_gettime(CLOCK_MONOTONIC.toInt(), ts.ptr)
		ts.tv_sec * 1_000_000_000L + ts.tv_nsec
	}

	private fun bufWidth() = maxOf(1, width * scale)
	private fun bufHeight() = maxOf(1, height * scale)

	private fun loop(maxSeconds: Int) {
		val deadline = platform.posix.time(null) + maxSeconds
		while (!closed && mgwl_should_close(handle) == 0) {
			val now = platform.posix.time(null)
			if (maxSeconds > 0 && now >= deadline) break
			// the run's last seconds, which is when the screenshot is taken
			val nearEnd = maxSeconds > 0 && now >= deadline - 2
			Snapshot.sendApplyNotifications()
			ComposeUiMainDispatcher.drainTasks()
			// Whatever the screen installed as its main-thread queue.  The
			// camera path posts real work to it -- UpdateCameraCharacteristics
			// does its setAspectRatio and onCharacteristicsUpdated inside a
			// runOnUiThread -- and nothing else would run those tasks.
			photoncam.host.HostMainLoop.pump()
			if (surfaceDirty) rebuildSurface()

			val surface = skiaSurface
			if (surface != null && (needsRender || scene.hasInvalidations())) {
				needsRender = false
				val context = directContext!!
				if (HostGpuFrame.update != null) {
					HostGpuFrame.update?.invoke(context)
					context.resetGLAll()
				}
				val canvas = surface.canvas
				val t0 = monotonicNanos()
				canvas.clear(Color.BLACK)
				scene.render(canvas.asComposeCanvas(), monotonicNanos())
				val t1 = monotonicNanos()
				surface.flushAndSubmit()
				val t2 = monotonicNanos()
				// Before the swap: after eglSwapBuffers the back buffer's
				// contents are undefined, so a grab there reads garbage.
				// TWICE a run by default, not every frame: one grab is a full GPU
				// readback, a 10 MB bitmap and a PNG encode -- 3.3 s on the phone.
				// Once at shotFrame, so an early failure still leaves a picture,
				// and once as the run ends, so the file holds the last frame.
				// PC_SHOT_EVERY_MS opts back into periodic ones.
				val wantShot = shotPath != null && frames + 1 >= shotFrame && (
					lastShotNanos == 0L ||
						(nearEnd && !grabbedFinal) ||
						(shotEveryNanos > 0 && t2 - lastShotNanos >= shotEveryNanos))
				if (wantShot) {
					if (nearEnd) grabbedFinal = true
					lastShotNanos = t2
					grab(surface)
				}
				// the grab has its own column: charged to the swap it read as the
				// compositor's fault, which is a whole round of the wrong question
				val t3 = monotonicNanos()
				mgwl_swap_buffers(handle)
				frames++
				// PC_TIME: where a frame goes.  Compose's own render, Skia's
				// flush to the GPU, and the swap are three different costs and
				// only the split says which one a slow phone is paying.
				if (timing) {
					sceneNanos += t1 - t0
					flushNanos += t2 - t1
					shotNanos += t3 - t2
					swapNanos += monotonicNanos() - t3
					if (frames % 10L == 0L) {
						println("[pc] $frames frames: scene=${sceneNanos / 10 / 1_000_000}ms" +
							" flush=${flushNanos / 10 / 1_000_000}ms" +
							" swap=${swapNanos / 10 / 1_000_000}ms" +
							if (shotNanos > 0) " shot=${shotNanos / 10 / 1_000_000}ms" else "")
						sceneNanos = 0; flushNanos = 0; swapNanos = 0; shotNanos = 0
					}
				}
			}
			val timeout = if (scene.hasInvalidations() || needsRender) 0 else 16
			mgwl_pump(handle, timeout)
			ComposeUiMainDispatcher.drainTasks()
		}
	}

	/** The frame as a PNG.  Skia reads it back off the GPU surface itself, so
	 *  this needs no glReadPixels and no change to the C host.  It goes through
	 *  a raster Bitmap on purpose: encodeToData on the makeImageSnapshot() image
	 *  is texture-backed and returns null here, silently. */
	private fun grab(surface: Surface) {
		val path = shotPath ?: return
		val first = frames + 1 == shotFrame.toLong()
		val bitmap = Bitmap()
		try {
			bitmap.allocPixels(ImageInfo.makeN32Premul(surface.width, surface.height))
			if (!surface.readPixels(bitmap, 0, 0)) {
				if (first) println("[pc] PC_SHOT: readPixels off the surface failed")
				return
			}
			val bytes = Image.makeFromBitmap(bitmap).encodeToData()?.bytes
			if (bytes == null) {
				if (first) println("[pc] PC_SHOT: PNG encode failed")
				return
			}
			val f = fopen(path, "wb")
			if (f == null) {
				if (first) println("[pc] PC_SHOT: cannot write $path")
				return
			}
			bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f) }
			fclose(f)
			if (first) println("[pc] PC_SHOT: $path ${surface.width}x${surface.height}, ${bytes.size} bytes")
		} finally {
			bitmap.close()
		}
	}

	private fun rebuildSurface() {
		surfaceDirty = false
		mgwl_make_current(handle)
		skiaSurface?.close(); renderTarget?.close()
		val ctx = directContext ?: DirectContext.makeGL().also { directContext = it }
		val w = bufWidth(); val h = bufHeight()
		val rt = BackendRenderTarget.makeGL(w, h, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8)
		renderTarget = rt
		// Native's makeFromBackendRenderTarget is nullable where the JVM's is not.
		skiaSurface = Surface.makeFromBackendRenderTarget(
			ctx, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
		) ?: error("Surface.makeFromBackendRenderTarget returned null (${w}x$h)")
		scene.size = IntSize(w, h)
		windowInfo.containerSize = IntSize(w, h)
		needsRender = true
	}

	private fun onConfigure(w: Int, h: Int) {
		if (w == width && h == height) return
		width = w; height = h
		scale = maxOf(1, mgwl_scale(handle))
		photoncam.host.HostWindow.set(bufWidth(), bufHeight(), density)
		surfaceDirty = true
	}

	private fun onTouch(id: Int, x: Float, y: Float, phase: Int) {
		needsRender = true
		if (trace) println("[pc] touch id=$id $x,$y phase=$phase")
		when (phase) {
			MGWL_TOUCH_DOWN.toInt() -> { pointers[id] = pointer(id, x, y, true, PointerType.Touch); send(PointerEventType.Press) }
			MGWL_TOUCH_MOVE.toInt() -> { pointers[id] = pointer(id, x, y, true, PointerType.Touch); send(PointerEventType.Move) }
			MGWL_TOUCH_UP.toInt() -> {
				pointers[id]?.let { pointers[id] = pointer(id, it.position.x / scale, it.position.y / scale, false, PointerType.Touch) }
				send(PointerEventType.Release)
				pointers.remove(id)
			}
			else -> { pointers.clear(); scene.cancelPointerInput(); ComposeUiMainDispatcher.drainTasks() }
		}
	}

	// qtmir emulates a mouse from touch and sends buttonless enter/leave/motion at
	// the touch point; an unpressed Mouse pointer left in the dispatch set rides
	// along with every touch event.  Keep the mouse only while a button is held,
	// and drop hover entirely -- MonoGram found this the hard way.
	private fun onPointer(kind: Int, x: Float, y: Float, buttons: Int) {
		needsRender = true
		if (trace) println("[pc] pointer kind=$kind $x,$y buttons=$buttons")
		// A WHEEL EVENT CARRIES NO POSITION: mgwl's axis callback puts the scroll
		// DELTA in x and y, so the last motion/button is where the wheel is.
		if (kind != MGWL_PTR_AXIS.toInt()) { lastX = x; lastY = y }
		when (kind) {
			MGWL_PTR_BUTTON.toInt() -> {
				val pressed = buttons != 0
				mouseButtons = buttons
				pointers[MOUSE] = pointer(MOUSE, x, y, pressed, PointerType.Mouse)
				send(if (pressed) PointerEventType.Press else PointerEventType.Release)
				if (!pressed) pointers.remove(MOUSE)
			}
			MGWL_PTR_MOTION.toInt() -> if (mouseButtons != 0) {
				pointers[MOUSE] = pointer(MOUSE, x, y, true, PointerType.Mouse)
				send(PointerEventType.Move)
			}
			MGWL_PTR_AXIS.toInt() -> {
				pointers[MOUSE] = pointer(MOUSE, lastX, lastY, mouseButtons != 0, PointerType.Mouse)
				// A wl_pointer axis value is already a distance in surface-local
				// units (libinput's notch is 15) and this scene takes scrollDelta
				// in PIXELS.  Three pixels per unit is the usual three lines.
				send(PointerEventType.Scroll, Offset(-x * SCROLL_PX, -y * SCROLL_PX))
				if (mouseButtons == 0) pointers.remove(MOUSE)
			}
		}
	}

	// Where the pointer last was, in surface-local units.  Only the wheel needs
	// it; every other kind carries its own place.
	private var lastX = 0f
	private var lastY = 0f

	private fun pointer(id: Int, x: Float, y: Float, pressed: Boolean, type: PointerType) =
		ComposeScenePointer(PointerId(id.toLong()), Offset(x * scale, y * scale), pressed, type)

	// `buttons` and `button` are not optional decoration for a MOUSE pointer.
	// Compose's clickable reads the button state out of the event, not out of the
	// pointer's own `pressed` flag, so a press sent without them leaves the button
	// visually held and never fires onClick.
	private fun send(eventType: PointerEventType, scroll: Offset = Offset.Zero) {
		val mouseDown = pointers[MOUSE]?.pressed == true
		val r = scene.sendPointerEvent(
			eventType,
			pointers.values.toList(),
			buttons = PointerButtons(isPrimaryPressed = mouseDown),
			scrollDelta = scroll,
			button = if (pointers.containsKey(MOUSE) &&
				(eventType == PointerEventType.Press || eventType == PointerEventType.Release)
			) PointerButton.Primary else null,
		)
		if (trace) println("[pc]   -> $eventType n=${pointers.size} primary=$mouseDown result=$r")
		// One pump can dispatch several batched events; without draining here only
		// the first of a batch is ever seen by the gesture coroutines.
		ComposeUiMainDispatcher.drainTasks()
	}

	companion object {
		/** Pixels per unit of wl_pointer axis value.  See the AXIS branch. */
		private const val SCROLL_PX = 3f
		private const val MOUSE = -1

		fun create(socketPath: String? = null, es: Int = 2): MgwlComposeHost? {
			val h = mgwl_create(socketPath) ?: return null
			return MgwlComposeHost(h, es)
		}

		/** Lomiri's grid unit is 8 px per density step; a desktop has none. */
		private fun readDensity(): Float {
			val gu = getenv("GRID_UNIT_PX")?.toKString()?.toFloatOrNull()
			return if (gu != null && gu > 0f) gu / 8f else 2f
		}
	}
}

/** The window's own size, for whoever has to decide where a menu fits. */
@OptIn(InternalComposeUiApi::class)
private class HostWindowInfo : WindowInfo {
	override val isWindowFocused: Boolean get() = true
	override var containerSize: IntSize by mutableStateOf(IntSize.Zero)
}
