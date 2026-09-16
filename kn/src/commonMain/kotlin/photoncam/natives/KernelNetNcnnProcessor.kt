@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_kernelnet_nativeCreate
import photoncam.natives.abi.pc_kernelnet_nativeDestroy
import photoncam.natives.abi.pc_kernelnet_nativeRun

/**
 * com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor's three
 * native methods (app/src/main/cpp/ncnnMl.cpp).  See [FlowNetNcnnProcessor] for
 * why `assetManager` is ignored in favour of [NcnnMl.assetRoot].
 *
 * `out` is a ByteBuffer here and a FloatBuffer in FlowNet, which is the Java's
 * own inconsistency; both are direct buffers of floats to the C.
 */
object KernelNetNcnnProcessor {

    fun nativeCreate(assetManager: Any?, paramPath: String?): Long = pc_kernelnet_nativeCreate(NcnnMl.assetRoot, paramPath)

    fun nativeRun(
        handle: Long, gray: FloatBuffer?, width: Int, height: Int, sigma: Float,
        out: ByteBuffer?
    ): Boolean = pc_kernelnet_nativeRun(
        handle, gray.nativeFloats(), width, height, sigma, out.nativeFloats()
    ) != 0

    fun nativeDestroy(handle: Long) = pc_kernelnet_nativeDestroy(handle)
}
