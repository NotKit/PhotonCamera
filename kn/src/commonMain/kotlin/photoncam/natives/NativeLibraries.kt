@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_halide_available

/**
 * What System.loadLibrary can find.  The app's native libraries are all linked
 * into this one binary, so loading one is a no-op -- except the Halide aligner,
 * which exists only where its prebuilt arm64 kernels link.  Asking for it
 * elsewhere fails as it does on Android's armeabi-v7a.
 */
object NativeLibraries {
    fun isLinked(name: String): Boolean = when (name) {
        "halidealign" -> pc_halide_available() != 0
        else -> true
    }
}
