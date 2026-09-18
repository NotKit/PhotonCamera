package photoncam

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import photoncam.screen.CameraScreenContent
import platform.posix._IOLBF
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.setvbuf
import platform.posix.stdout
import kotlin.native.CpuArchitecture
import kotlin.native.Platform

/** What this binary is actually running on, asked of the runtime rather than
 *  asserted by the build -- an arm64 screenshot that says "linuxX64" because the
 *  string was a literal is a lie that survives a long time. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private val KN_TARGET: String
	get() = when (Platform.cpuArchitecture) {
		CpuArchitecture.ARM64 -> "linuxArm64"
		CpuArchitecture.X64 -> "linuxX64"
		else -> Platform.cpuArchitecture.name.lowercase()
	}

/**
 *   photoncam-kn [seconds]
 *
 * PC_ES picks the GLES context version; PC_SOCKET an absolute Wayland socket
 * path (a confined UT click has to pass one -- XDG_RUNTIME_DIR is remapped);
 * PC_TRACE turns on the pointer log.
 *
 * WHICH SCREEN COMES UP is decided by the BUILD, not by an argument:
 * `photoncam.screen.CameraScreenContent` is defined twice, in src/linuxNoApp
 * (the hand-made state of milestone 2) and in src/linuxApp (the converted app,
 * -PwithApp).  A flag would mean the no-app binary naming the app to skip it,
 * and then it would need gen/ to link.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, kotlin.experimental.ExperimentalNativeApi::class)
fun main(args: Array<String>) {
	// STDOUT IS A PIPE HERE (ssh, then tee), so libc block-buffers it and a
	// crash takes the last 4 KB of println with it -- which is every line that
	// would have said where the crash was.  Log goes to stderr and is unbuffered;
	// this puts println on the same footing.
	setvbuf(stdout, null, _IOLBF, 0u)
	// Kotlin/Native on Linux ships no main dispatcher, and Compose's own
	// postDelayed launches on Dispatchers.Main -- without this the first layout
	// pass throws "Dispatchers.Main is missing on the current platform".
	// Aurora's own application {} does the same thing.
	Dispatchers.setMain(androidx.compose.ui.platform.ComposeUiMainDispatcher)

	val seconds = args.getOrNull(0)?.toIntOrNull() ?: 10
	val es = getenv("PC_ES")?.toKString()?.toIntOrNull() ?: 2
	val socket = getenv("PC_SOCKET")?.toKString()?.takeIf { it.isNotEmpty() }
	// THE SURFACE'S APP ID IS WHAT LOMIRI MATCHES THE APP BY, and it has to be
	// the id the shell launched -- the .desktop's basename for a legacy app.
	// With the two out of step the shell never associates this surface with the
	// application it started, and SIGSTOPs the process as a background app.
	val appId = getenv("PC_APP_ID")?.toKString()?.takeIf { it.isNotEmpty() } ?: "photoncamera"

	val host = MgwlComposeHost.create(socket, es)
		?: error("cannot reach the compositor (socket=${socket ?: "\$WAYLAND_DISPLAY"})")
	println("[pc] photoncam-kn on $KN_TARGET, app id $appId, ${seconds}s")
	// Offscreen GL (the converted GLContext) needs a display that does
	// pbuffers. The phone's hybris EGL answers the default display as Android
	// would; desktop Mesa's does not, so only the x64 host may fall back to
	// the surfaceless platform -- on the device that fallback must never
	// fire, or a software rasteriser would silently stand in for the Adreno.
	android.opengl.EGL14.allowSurfacelessFallback =
		Platform.cpuArchitecture == CpuArchitecture.X64
	// And when the default display will not initialise at all -- which is what
	// the phone does once the compositor's display is live -- the offscreen GL
	// shares the window's, as it would on Android, where there is only one.
	host.onWindowReady = { android.opengl.EGL14.hostDisplay = host.eglDisplay() }
	host.run(appId, "PhotonCamera", 720, 1440, seconds) { CameraScreenContent() }
	// The last line the app itself writes.  Anything after it belongs to the
	// process teardown -- which on this device still has the camera service's
	// binder threads in it, inside libcamera_client.
	fflush(stdout)
	platform.posix.fprintf(platform.posix.stderr, "[pc] main returning\n")
	platform.posix.fflush(platform.posix.stderr)
}
