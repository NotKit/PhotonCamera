@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import photoncam.natives.abi.pc_image_backend
import photoncam.natives.abi.pc_image_decode_file
import photoncam.natives.abi.pc_image_decode_memory
import photoncam.natives.abi.pc_image_encode_jpeg_file
import photoncam.natives.abi.pc_image_encode_jpeg_memory
import photoncam.natives.abi.pc_image_free
import photoncam.natives.abi.pc_image_free_bytes
import photoncam.natives.abi.pc_image_probe_file
import photoncam.natives.abi.pc_image_probe_memory
import platform.posix.memcpy

/**
 * PNG and JPEG.  The one part of this lane with no app C++ under it: on Android
 * these are Skia's, reached through BitmapFactory and Bitmap.compress, and
 * app/src/main/cpp has no image codec at all.
 *
 * Pixels are ARGB_8888 as one Int each, row-major and tightly packed - which is
 * exactly what android.graphics.Bitmap holds on this port, so a decode hands
 * its IntArray straight to `Bitmap.createBitmap(colors, w, h, ARGB_8888)` with
 * no repacking.
 *
 * android.graphics.BitmapFactory and the JPEG branch of Bitmap.compress are the
 * callers; nothing in the app talks to this object directly.
 */
object ImageCodec {

    /** "libpng+libjpeg" or "stb", whichever the build found.  For logs. */
    val backend: String get() = pc_image_backend()?.toKString() ?: "none"

    class Decoded(val width: Int, val height: Int, val pixels: IntArray)

    class Bounds(val width: Int, val height: Int)

    fun decodeFile(path: String?): Decoded? {
        if (path == null) return null
        return memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            val p = pc_image_decode_file(path, w.ptr, h.ptr) ?: return@memScoped null
            take(p, w.value, h.value)
        }
    }

    fun decodeBytes(data: ByteArray?, offset: Int = 0, length: Int = data?.size ?: 0): Decoded? {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.size) return null
        return memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            val p = data.usePinned {
                pc_image_decode_memory(it.addressOf(offset), length.toLong(), w.ptr, h.ptr)
            } ?: return@memScoped null
            take(p, w.value, h.value)
        }
    }

    fun probeFile(path: String?): Bounds? {
        if (path == null) return null
        return memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            if (pc_image_probe_file(path, w.ptr, h.ptr) == 0) null else Bounds(w.value, h.value)
        }
    }

    fun probeBytes(data: ByteArray?, offset: Int = 0, length: Int = data?.size ?: 0): Bounds? {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.size) return null
        return memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            val ok = data.usePinned {
                pc_image_probe_memory(it.addressOf(offset), length.toLong(), w.ptr, h.ptr)
            }
            if (ok == 0) null else Bounds(w.value, h.value)
        }
    }

    /** ARGB_8888 to baseline JPEG.  Alpha is dropped, as it is on Android. */
    fun encodeJpeg(pixels: IntArray?, width: Int, height: Int, quality: Int): ByteArray? {
        if (pixels == null || width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        return memScoped {
            val len = alloc<LongVar>()
            val out: kotlinx.cinterop.CPointer<UByteVar> = pixels.usePinned {
                pc_image_encode_jpeg_memory(
                    it.addressOf(0).reinterpret(), width, height, quality, len.ptr,
                )
            } ?: return@memScoped null
            val bytes = ByteArray(len.value.toInt())
            if (bytes.isNotEmpty()) {
                bytes.usePinned { dst -> memcpy(dst.addressOf(0), out, bytes.size.convert()) }
            }
            pc_image_free_bytes(out)
            bytes
        }
    }

    fun encodeJpegToFile(
        pixels: IntArray?, width: Int, height: Int, quality: Int, path: String?,
    ): Boolean {
        if (pixels == null || path == null || width <= 0 || height <= 0) return false
        if (pixels.size < width * height) return false
        return pixels.usePinned {
            pc_image_encode_jpeg_file(it.addressOf(0).reinterpret(), width, height, quality, path)
        } != 0
    }

    /** Copy the decoded block into an IntArray and give the C its memory back. */
    private fun take(
        p: kotlinx.cinterop.CPointer<UIntVar>, width: Int, height: Int,
    ): Decoded? {
        if (width <= 0 || height <= 0) {
            pc_image_free(p)
            return null
        }
        val pixels = IntArray(width * height)
        pixels.usePinned { dst ->
            memcpy(dst.addressOf(0), p, (pixels.size.toLong() * 4L).convert())
        }
        pc_image_free(p)
        return Decoded(width, height, pixels)
    }
}
