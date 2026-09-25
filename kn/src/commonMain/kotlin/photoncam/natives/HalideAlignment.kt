@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.ByteBuffer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import photoncam.natives.abi.pc_halide_freeResult
import photoncam.natives.abi.pc_halide_nAlignFrame
import photoncam.natives.abi.pc_halide_nBase
import photoncam.natives.abi.pc_halide_nInit
import photoncam.natives.abi.pc_halide_nRelease
import photoncam.natives.abi.pc_halide_nSetThreads

/**
 * com.particlesdevs.photoncamera.processing.cpu.HalideAlignment's five native
 * methods (app/src/main/cpp/halide/align_jni.cpp).  The prebuilt kernels are
 * arm64 only; elsewhere NativeLibraries says "halidealign" is not linked, the
 * Java's loadLibrary fails, and ESD4D takes the GL aligner.
 */
object HalideAlignment {

    fun nInit(rawW: Int, rawH: Int): Long = pc_halide_nInit(rawW, rawH)

    fun nBase(ctx: Long, raw: ByteBuffer?, white: Float): Int =
        pc_halide_nBase(ctx, raw.nativeBase(), raw.nativeCapacity(), white)

    /** The per-tile vectors, or null when the kernel failed. */
    fun nAlignFrame(ctx: Long, raw: ByteBuffer?, white: Float): FloatArray? = memScoped {
        val out = alloc<CPointerVar<FloatVar>>()
        val n = pc_halide_nAlignFrame(ctx, raw.nativeBase(), raw.nativeCapacity(), white, out.ptr)
        val p = out.value
        if (n < 0 || p == null) return@memScoped null
        val result = FloatArray(n) { p[it] }
        pc_halide_freeResult(p)
        result
    }

    fun nRelease(ctx: Long) = pc_halide_nRelease(ctx)

    fun nSetThreads(n: Int) = pc_halide_nSetThreads(n)
}
