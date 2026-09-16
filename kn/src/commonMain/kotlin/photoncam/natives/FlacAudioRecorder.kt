@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_flac_nativeClose
import photoncam.natives.abi.pc_flac_nativeOpenFd
import photoncam.natives.abi.pc_flac_nativeWriteFrame

/**
 * com.particlesdevs.photoncamera.util.FlacAudioRecorder's three native methods
 * (app/src/main/cpp/flacRecorder.cpp): a technicallyflac encoder writing to a
 * file descriptor the caller opened.  Nothing about it is Android-specific -
 * the fd exists to get around Android 11's FUSE rules, and works the same here.
 */
object FlacAudioRecorder {

    fun nativeOpenFd(
        fd: Int, sampleRate: Int, channels: Int, bitDepth: Int, blockSize: Int
    ): Long = pc_flac_nativeOpenFd(fd, sampleRate, channels, bitDepth, blockSize)

    fun nativeWriteFrame(ctx: Long, samples: ShortArray?, frameCount: Int, channels: Int) =
        samples.pinned { p, n -> pc_flac_nativeWriteFrame(ctx, p, n, frameCount, channels) }

    fun nativeClose(ctx: Long) = pc_flac_nativeClose(ctx)
}
