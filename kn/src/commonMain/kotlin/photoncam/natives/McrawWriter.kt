@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.ByteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import photoncam.natives.abi.pc_mcraw_nativeClose
import photoncam.natives.abi.pc_mcraw_nativeCreate
import photoncam.natives.abi.pc_mcraw_nativeEncode
import photoncam.natives.abi.pc_mcraw_nativeFrameCount
import photoncam.natives.abi.pc_mcraw_nativeWriteAudio
import photoncam.natives.abi.pc_mcraw_nativeWriteFrame
import photoncam.natives.abi.pc_mcraw_nativeWriteGyro

/**
 * com.particlesdevs.photoncamera.util.McrawWriter's seven native methods
 * (app/src/main/cpp/mediacinemaraw): the .mcraw raw-video container, writing
 * to a file descriptor the caller opened, as FlacAudioRecorder does.
 */
object McrawWriter {

    fun nativeCreate(fd: Int, metadata: String?): Long = pc_mcraw_nativeCreate(fd, metadata)

    fun nativeEncode(
        plane: ByteBuffer?, width: Int, height: Int, stride: Int, raw10: Boolean,
        cropTop: Int, cropHeight: Int, bin: Boolean, outputSlot: ByteBuffer?
    ): Int = pc_mcraw_nativeEncode(
        plane.nativeBase(), plane.nativeCapacity(), width, height, stride, if (raw10) 1 else 0,
        cropTop, cropHeight, if (bin) 1 else 0, outputSlot.nativeBase(), outputSlot.nativeCapacity()
    )

    fun nativeWriteFrame(
        ptr: Long, encoded: ByteBuffer?, length: Int, timestampNs: Long, frameMetadata: String?
    ): Int = pc_mcraw_nativeWriteFrame(
        ptr, encoded.nativeBase(), encoded.nativeCapacity(), length, timestampNs, frameMetadata
    )

    fun nativeFrameCount(ptr: Long): Long = pc_mcraw_nativeFrameCount(ptr)

    fun nativeWriteAudio(ptr: Long, samples: ShortArray?, count: Int, timestampNs: Long) =
        samples.pinned { p, n -> pc_mcraw_nativeWriteAudio(ptr, p, n, count, timestampNs) }

    fun nativeWriteGyro(
        ptr: Long, timestamps: LongArray?, x: FloatArray?, y: FloatArray?, z: FloatArray?, count: Int
    ) {
        // The C reads `count` of each; a short or missing array is the Java's
        // ArrayIndexOutOfBounds, so it stops here instead of reading past it.
        if (timestamps == null || x == null || y == null || z == null) return
        if (count <= 0 || timestamps.size < count || x.size < count || y.size < count || z.size < count) return
        timestamps.usePinned { t ->
            x.usePinned { px ->
                y.usePinned { py ->
                    z.usePinned { pz ->
                        pc_mcraw_nativeWriteGyro(
                            ptr, t.addressOf(0), px.addressOf(0), py.addressOf(0), pz.addressOf(0), count
                        )
                    }
                }
            }
        }
    }

    fun nativeClose(ptr: Long) = pc_mcraw_nativeClose(ptr)
}
