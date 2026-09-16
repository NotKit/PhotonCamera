@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_ncnn_available
import photoncam.natives.abi.pc_ncnn_nativeEnsureInit

/**
 * The ncnn runtime shared by FlowNet and KernelNet (app/src/main/cpp/ncnnMl.cpp,
 * built against a CPU-only ncnn by host/natives/build_ncnn.sh).
 *
 * On Android both models are read out of the apk through an AssetManager.  There
 * is none here, so they are read from a directory instead and [assetRoot] says
 * which one; the model paths stay exactly the asset paths the Java passes
 * ("models/flownet_flat.ncnn.param"), relative to it.  The host sets this once
 * at start-up, before anything constructs a processor.
 */
object NcnnMl {

    /** Directory the .ncnn.param/.bin models are read from. */
    var assetRoot: String = "assets"

    /** False when the build had no ncnn: every model then reports unavailable. */
    val isAvailable: Boolean get() = pc_ncnn_available() != 0

    fun nativeEnsureInit(): Boolean = pc_ncnn_nativeEnsureInit() != 0
}
