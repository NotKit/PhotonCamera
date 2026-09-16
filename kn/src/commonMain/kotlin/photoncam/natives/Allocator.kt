@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.ByteBuffer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import photoncam.natives.abi.pc_alloc_allocate
import photoncam.natives.abi.pc_alloc_allocateAndCopy
import photoncam.natives.abi.pc_alloc_allocateAndCopyBinning
import photoncam.natives.abi.pc_alloc_allocateAndCopyConvert
import photoncam.natives.abi.pc_alloc_allocateAndCopyConvertBinning
import photoncam.natives.abi.pc_alloc_free
import photoncam.natives.abi.pc_alloc_getMemoryCount

/**
 * com.particlesdevs.photoncamera.util.Allocator's seven native methods
 * (app/src/main/cpp/allocator.cpp).  Same names and same argument order as the
 * Java, so the converted class delegates to this one for one (the j2k rule
 * r50_native_delegate).
 *
 * These buffers are plain malloc'd memory with no owner: nothing frees them but
 * [free], exactly as on Android.
 */
object Allocator {

    /* The C reports the block's real size, which for the convert and binning
     * variants is not the `capacity` argument but a function of the geometry. */
    private inline fun allocated(block: (CPointer<LongVar>) -> COpaquePointer?): ByteBuffer? =
        memScoped {
            val size = alloc<LongVar>()
            val p = block(size.ptr) ?: return@memScoped null
            wrapNative(p, size.value.toInt())
        }

    fun allocate(capacity: Int): ByteBuffer? =
        allocated { pc_alloc_allocate(capacity, it) }

    fun allocateAndCopy(capacity: Int, origin: ByteBuffer?, offset: Int): ByteBuffer? =
        allocated {
            pc_alloc_allocateAndCopy(
                capacity, origin.nativeBase(), origin.nativeCapacity(), offset, it
            )
        }

    fun allocateAndCopyConvert(
        capacity: Int, origin: ByteBuffer?, width: Int, row_stride: Int, offset: Int
    ): ByteBuffer? = allocated {
        pc_alloc_allocateAndCopyConvert(
            capacity, origin.nativeBase(), origin.nativeCapacity(), width, row_stride, offset, it
        )
    }

    fun allocateAndCopyConvertBinning(
        capacity: Int, origin: ByteBuffer?, width: Int, row_stride: Int, offset: Int
    ): ByteBuffer? = allocated {
        pc_alloc_allocateAndCopyConvertBinning(
            capacity, origin.nativeBase(), origin.nativeCapacity(), width, row_stride, offset, it
        )
    }

    fun allocateAndCopyBinning(
        capacity: Int, origin: ByteBuffer?, width: Int, height: Int, row_stride: Int
    ): ByteBuffer? = allocated {
        pc_alloc_allocateAndCopyBinning(
            capacity, origin.nativeBase(), origin.nativeCapacity(), width, height, row_stride, it
        )
    }

    fun free(buffer: ByteBuffer?) {
        if (buffer == null) return
        pc_alloc_free(buffer.nativeBase(), buffer.nativeCapacity())
    }

    fun getMemoryCount(): Long = pc_alloc_getMemoryCount()
}
