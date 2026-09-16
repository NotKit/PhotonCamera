package photoncam

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import photoncam.screen.CameraScreenContent
import platform.posix.getenv
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
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
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
	host.run(appId, "PhotonCamera", 720, 1440, seconds) { CameraScreenContent() }
}
