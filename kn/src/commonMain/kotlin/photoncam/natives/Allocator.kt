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
import photoncam.natives.abi.pc_alloc_allocateAndCopyCrop
import photoncam.natives.abi.pc_alloc_allocateAndCopyCropBinning
import photoncam.natives.abi.pc_alloc_createF16
import photoncam.natives.abi.pc_alloc_createU16FromF16
import photoncam.natives.abi.pc_alloc_packBits
import photoncam.natives.abi.pc_alloc_unpack16
import photoncam.natives.abi.pc_alloc_rgbaToYuv420Tile
import android.graphics.Bitmap

/**
 * com.particlesdevs.photoncamera.util.Allocator's native methods
 * (app/src/main/cpp/allocator.cpp and rawF16.cpp).  Same names and same argument order as the
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

    fun allocateAndCopyCrop(
        cropWidthBytes: Int, cropHeight: Int, origin: ByteBuffer?, row_stride: Int, offset: Int
    ): ByteBuffer? = allocated {
        pc_alloc_allocateAndCopyCrop(
            cropWidthBytes, cropHeight, origin.nativeBase(), origin.nativeCapacity(),
            row_stride, offset, it
        )
    }

    fun allocateAndCopyCropBinning(
        cropWidth: Int, cropHeight: Int, origin: ByteBuffer?, row_stride: Int, offset: Int
    ): ByteBuffer? = allocated {
        pc_alloc_allocateAndCopyCropBinning(
            cropWidth, cropHeight, origin.nativeBase(), origin.nativeCapacity(),
            row_stride, offset, it
        )
    }

    fun createF16(
        origin: ByteBuffer?, width: Int, height: Int, whiteLevel: Int, blackLevel: FloatArray?
    ): ByteBuffer? = blackLevel.pinned { bl, n ->
        allocated {
            pc_alloc_createF16(
                origin.nativeBase(), origin.nativeCapacity(), width, height, whiteLevel, bl, n, it
            )
        }
    }

    fun createU16FromF16(
        origin: ByteBuffer?, width: Int, height: Int, whiteLevel: Int, blackLevel: FloatArray?
    ): ByteBuffer? = blackLevel.pinned { bl, n ->
        allocated {
            pc_alloc_createU16FromF16(
                origin.nativeBase(), origin.nativeCapacity(), width, height, whiteLevel, bl, n, it
            )
        }
    }

    fun packBits(src: ByteBuffer?, pixels: Int, bits: Int, verify: Boolean): ByteBuffer? =
        allocated {
            pc_alloc_packBits(
                src.nativeBase(), src.nativeCapacity(), pixels, bits, if (verify) 1 else 0, it
            )
        }

    fun unpack16(dst: ByteBuffer?, packed: ByteBuffer?, pixels: Int, bits: Int) =
        pc_alloc_unpack16(
            dst.nativeBase(), dst.nativeCapacity(), packed.nativeBase(), packed.nativeCapacity(),
            pixels, bits
        )

    fun rgbaToYuv420Tile(
        src: ByteBuffer?, fullWidth: Int, fullHeight: Int,
        tileX: Int, tileY: Int, tileWidth: Int, tileHeight: Int,
        yPlane: ByteBuffer?, yRowStride: Int, yPixelStride: Int,
        uPlane: ByteBuffer?, uvRowStride: Int, uPixelStride: Int,
        vPlane: ByteBuffer?, vRowStride: Int, vPixelStride: Int
    ) = pc_alloc_rgbaToYuv420Tile(
        src.nativeBase(), src.nativeCapacity(), fullWidth, fullHeight,
        tileX, tileY, tileWidth, tileHeight,
        yPlane.nativeBase(), yPlane.nativeCapacity(), yRowStride, yPixelStride,
        uPlane.nativeBase(), uPlane.nativeCapacity(), uvRowStride, uPixelStride,
        vPlane.nativeBase(), vPlane.nativeCapacity(), vRowStride, vPixelStride
    )

    /*
     * wrapBitmap/unlockBitmap lock a Bitmap's pixels in place on Android, so
     * GL can read straight into them.  A Bitmap here is an IntArray of ARGB
     * ints, which is neither RGBA bytes nor pinnable across calls, so the lock
     * is a staging buffer instead: it starts as the bitmap's pixels and unlock
     * writes it back.  That is one full-frame copy each way, which Android
     * avoids -- the memory saving upstream wanted does not carry over.
     */
    private val locked = HashMap<Bitmap, ByteBuffer>()

    fun wrapBitmap(bitmap: Bitmap?): ByteBuffer? {
        if (bitmap == null || bitmap.isRecycled) return null
        val staging = allocate(bitmap.byteCount) ?: return null
        bitmap.copyPixelsToBuffer(staging)
        staging.rewind()
        locked[bitmap] = staging
        return staging
    }

    fun unlockBitmap(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        val staging = locked.remove(bitmap) ?: return false
        staging.rewind()
        bitmap.copyPixelsFromBuffer(staging)
        free(staging)
        return true
    }
}
