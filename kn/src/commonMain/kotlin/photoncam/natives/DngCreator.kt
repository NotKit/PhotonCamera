@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.ByteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import photoncam.natives.abi.*

/**
 * com.particlesdevs.photoncamera.processing.DngCreator's thirty-nine native
 * methods (app/src/main/cpp/dngCreator.cpp).  Names and argument order are the
 * Java's, so the converted class delegates to this one for one (the j2k rule
 * r50_native_delegate).
 *
 * `nativePtr` is the C++ DngCreator, exactly as on Android: [create] makes one,
 * [destroy] deletes it, and every other call takes it back.
 *
 * The archive path ([openArchive]) dlopens libarchive-jni.so at run time, which
 * host/natives/build.sh produces beside the static library.
 */
object DngCreator {

    fun create(): Long = pc_dng_create()

    fun destroy(nativePtr: Long) = pc_dng_destroy(nativePtr)

    fun createDNG(
        nativePtr: Long, width: Int, height: Int, rawImageData: ByteBuffer?
    ): ByteBuffer? = memScoped {
        val size = alloc<LongVar>()
        val dng = pc_dng_createDNG(
            nativePtr, width, height, rawImageData.nativeBase(),
            rawImageData.nativeCapacity(), size.ptr
        ) ?: return@memScoped null
        wrapNative(dng, size.value.toInt())
    }

    fun setOrientation(nativePtr: Long, orientation: Int) =
        pc_dng_setOrientation(nativePtr, orientation)

    fun setWhiteLevel(nativePtr: Long, whiteLevel: Double) =
        pc_dng_setWhiteLevel(nativePtr, whiteLevel)

    fun setBlackLevel(nativePtr: Long, blackLevel: ShortArray?) =
        blackLevel.pinned { p, n -> pc_dng_setBlackLevel(nativePtr, p, n) }

    fun setColorMatrix1(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setColorMatrix1(nativePtr, p, n) }

    fun setColorMatrix2(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setColorMatrix2(nativePtr, p, n) }

    fun setForwardMatrix1(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setForwardMatrix1(nativePtr, p, n) }

    fun setForwardMatrix2(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setForwardMatrix2(nativePtr, p, n) }

    fun setCameraCalibration1(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setCameraCalibration1(nativePtr, p, n) }

    fun setCameraCalibration2(nativePtr: Long, matrix: DoubleArray?) =
        matrix.pinned { p, n -> pc_dng_setCameraCalibration2(nativePtr, p, n) }

    fun setAsShotNeutral(nativePtr: Long, neutral: DoubleArray?) =
        neutral.pinned { p, n -> pc_dng_setAsShotNeutral(nativePtr, p, n) }

    fun setAsShotWhiteXY(nativePtr: Long, x: Double, y: Double) =
        pc_dng_setAsShotWhiteXY(nativePtr, x, y)

    fun setAnalogBalance(nativePtr: Long, balance: DoubleArray?) =
        balance.pinned { p, n -> pc_dng_setAnalogBalance(nativePtr, p, n) }

    fun setCalibrationIlluminant1(nativePtr: Long, illuminant: Short) =
        pc_dng_setCalibrationIlluminant1(nativePtr, illuminant)

    fun setCalibrationIlluminant2(nativePtr: Long, illuminant: Short) =
        pc_dng_setCalibrationIlluminant2(nativePtr, illuminant)

    fun setUniqueCameraModel(nativePtr: Long, model: String?) =
        pc_dng_setUniqueCameraModel(nativePtr, model)

    fun setDescription(nativePtr: Long, desc: String?) =
        pc_dng_setDescription(nativePtr, desc)

    fun setSoftware(nativePtr: Long, soft: String?) =
        pc_dng_setSoftware(nativePtr, soft)

    fun setIso(nativePtr: Long, iso: Short) = pc_dng_setIso(nativePtr, iso)

    fun setExposureTime(nativePtr: Long, exposureTime: Double) =
        pc_dng_setExposureTime(nativePtr, exposureTime)

    fun setCFAPattern(nativePtr: Long, pattern: Int) = pc_dng_setCFAPattern(nativePtr, pattern)

    fun setGainMap(
        nativePtr: Long, gainMap: FloatArray?, xmin: Int, ymin: Int, xmax: Int, ymax: Int,
        width: Int, height: Int
    ) = gainMap.pinned { p, n ->
        pc_dng_setGainMap(nativePtr, p, n, xmin, ymin, xmax, ymax, width, height)
    }

    fun setAperture(nativePtr: Long, aperture: Double) = pc_dng_setAperture(nativePtr, aperture)

    fun setFocalLength(nativePtr: Long, focalLength: Double) =
        pc_dng_setFocalLength(nativePtr, focalLength)

    fun setMake(nativePtr: Long, make: String?) =
        pc_dng_setMake(nativePtr, make)

    fun setModel(nativePtr: Long, model: String?) =
        pc_dng_setModel(nativePtr, model)

    fun setTimeCode(nativePtr: Long, timecode: ByteArray?) =
        timecode.pinned { p, n -> pc_dng_setTimeCode(nativePtr, p, n) }

    fun setDateTime(nativePtr: Long, datetime: String?) =
        pc_dng_setDateTime(nativePtr, datetime)

    fun setNoiseProfile(nativePtr: Long, noiseProfile: DoubleArray?) =
        noiseProfile.pinned { p, n -> pc_dng_setNoiseProfile(nativePtr, p, n) }

    fun setFrameRate(nativePtr: Long, frameRate: Double) =
        pc_dng_setFrameRate(nativePtr, frameRate)

    fun setCompression(nativePtr: Long, useCompression: Boolean) =
        pc_dng_setCompression(nativePtr, if (useCompression) 1 else 0)

    fun setBitsPerSample(nativePtr: Long, bps: Int) = pc_dng_setBitsPerSample(nativePtr, bps)

    fun setBinning(nativePtr: Long, binning: Boolean) =
        pc_dng_setBinning(nativePtr, if (binning) 1 else 0)

    fun writeFile(
        nativePtr: Long, dngBuffer: ByteBuffer?, raw: ByteBuffer?, path: String?, offset: Int
    ) = pc_dng_writeFile(
        nativePtr, dngBuffer.nativeBase(), dngBuffer.nativeCapacity(),
        raw.nativeBase(), raw.nativeCapacity(), path, offset
    )

    fun openArchive(nativePtr: Long, path: String?) =
        pc_dng_openArchive(nativePtr, path)

    fun openArchiveByFd(nativePtr: Long, fd: Int) = pc_dng_openArchiveByFd(nativePtr, fd)

    fun closeArchive(nativePtr: Long) = pc_dng_closeArchive(nativePtr)
}
