@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.FloatBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_flownet_nativeCreate
import photoncam.natives.abi.pc_flownet_nativeDestroy
import photoncam.natives.abi.pc_flownet_nativeRun

/**
 * com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor's three
 * native methods (app/src/main/cpp/ncnnMl.cpp).
 *
 * [assetManager] is ignored: the Java hands over an android AssetManager, and
 * this port reads the model from [NcnnMl.assetRoot] instead.  The parameter
 * stays in the signature so the converted class can delegate unchanged, and is
 * typed `Any?` so this lane does not depend on whose shim AssetManager is.
 *
 * The buffers must be direct - they are read and written in place by ncnn.
 */
object FlowNetNcnnProcessor {

    fun nativeCreate(assetManager: Any?, paramPath: String?): Long = pc_flownet_nativeCreate(NcnnMl.assetRoot, paramPath)

    fun nativeRun(
        handle: Long, baseRgba: FloatBuffer?, alterRgba: FloatBuffer?, width: Int, height: Int,
        flowOut: FloatBuffer?
    ): Boolean = pc_flownet_nativeRun(
        handle, baseRgba.nativeFloats(), alterRgba.nativeFloats(), width, height, flowOut.nativeFloats()
    ) != 0

    fun nativeDestroy(handle: Long) = pc_flownet_nativeDestroy(handle)
}
