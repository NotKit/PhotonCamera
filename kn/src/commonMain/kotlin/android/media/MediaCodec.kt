package android.media

import android.util.Range
import android.view.Surface
import java.nio.ByteBuffer

/*
 * The codec layer.  Like MediaRecorder, there is no encoder behind this port,
 * so the list of codecs is empty -- which is a real answer, and the one a
 * device without a HEVC or HEIC encoder gives.  HeicSupport and
 * VideoCodecSupport read it and report the formats unsupported; the still path
 * then writes JPEG, as it does on such a device.
 *
 * Nothing can hand out a MediaCodec, MediaCodecInfo or MediaMuxer: the
 * factories throw the IOException a missing codec throws on Android.  So the
 * instance members below are unreachable, and say so if that ever changes.
 * The constants are AOSP's.
 */

class MediaFormat {
	private val values = LinkedHashMap<String, Any>()

	fun setInteger(name: String, value: Int) { values[name] = value }
	fun setLong(name: String, value: Long) { values[name] = value }
	fun setFloat(name: String, value: Float) { values[name] = value }
	fun setString(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
	fun containsKey(name: String): Boolean = values.containsKey(name)
	fun getInteger(name: String): Int = values[name] as? Int ?: throw NullPointerException(name)
	fun getInteger(name: String, defaultValue: Int): Int = values[name] as? Int ?: defaultValue
	fun getLong(name: String): Long = values[name] as? Long ?: throw NullPointerException(name)
	fun getFloat(name: String): Float = values[name] as? Float ?: throw NullPointerException(name)
	fun getString(name: String): String? = values[name] as? String

	override fun toString(): String = values.toString()

	companion object {
		const val MIMETYPE_VIDEO_AVC = "video/avc"
		const val MIMETYPE_VIDEO_HEVC = "video/hevc"
		const val MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
		const val MIMETYPE_IMAGE_ANDROID_HEIC = "image/vnd.android.heic"

		const val KEY_MIME = "mime"
		const val KEY_WIDTH = "width"
		const val KEY_HEIGHT = "height"
		const val KEY_BIT_RATE = "bitrate"
		const val KEY_BITRATE_MODE = "bitrate-mode"
		const val KEY_COLOR_FORMAT = "color-format"
		const val KEY_COLOR_RANGE = "color-range"
		const val KEY_COLOR_STANDARD = "color-standard"
		const val KEY_COLOR_TRANSFER = "color-transfer"
		const val KEY_FRAME_RATE = "frame-rate"
		const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
		const val KEY_OPERATING_RATE = "operating-rate"
		const val KEY_QUALITY = "quality"
		const val KEY_TILE_WIDTH = "tile-width"
		const val KEY_TILE_HEIGHT = "tile-height"
		const val KEY_GRID_ROWS = "grid-rows"
		const val KEY_GRID_COLUMNS = "grid-cols"
		const val KEY_PROFILE = "profile"
		const val KEY_LEVEL = "level"
		const val KEY_SAMPLE_RATE = "sample-rate"
		const val KEY_CHANNEL_COUNT = "channel-count"

		const val COLOR_RANGE_FULL = 1
		const val COLOR_RANGE_LIMITED = 2
		const val COLOR_STANDARD_BT709 = 1
		const val COLOR_STANDARD_BT2020 = 6
		const val COLOR_TRANSFER_LINEAR = 1
		const val COLOR_TRANSFER_SDR_VIDEO = 3
		const val COLOR_TRANSFER_ST2084 = 6
		const val COLOR_TRANSFER_HLG = 7

		fun createVideoFormat(mime: String, width: Int, height: Int): MediaFormat =
			MediaFormat().apply {
				setString(KEY_MIME, mime)
				setInteger(KEY_WIDTH, width)
				setInteger(KEY_HEIGHT, height)
			}

		fun createAudioFormat(mime: String, sampleRate: Int, channelCount: Int): MediaFormat =
			MediaFormat().apply {
				setString(KEY_MIME, mime)
				setInteger(KEY_SAMPLE_RATE, sampleRate)
				setInteger(KEY_CHANNEL_COUNT, channelCount)
			}
	}
}

private fun noCodec(): Nothing =
	throw UnsupportedOperationException("no media codec exists on this build")

class MediaCodecInfo private constructor() {
	fun getName(): String = noCodec()
	fun isEncoder(): Boolean = noCodec()
	fun isHardwareAccelerated(): Boolean = noCodec()
	fun getSupportedTypes(): Array<String> = noCodec()
	fun getCapabilitiesForType(type: String): CodecCapabilities = noCodec()

	class CodecProfileLevel {
		var profile: Int = 0
		var level: Int = 0

		companion object {
			const val HEVCProfileMain = 0x01
			const val HEVCProfileMain10 = 0x02
			const val HEVCMainTierLevel31 = 0x100
			const val HEVCMainTierLevel4 = 0x400
			const val HEVCMainTierLevel41 = 0x1000
			const val HEVCMainTierLevel5 = 0x4000
			const val HEVCMainTierLevel51 = 0x10000
		}
	}

	class CodecCapabilities private constructor() {
		var profileLevels: Array<CodecProfileLevel> = emptyArray()

		fun getEncoderCapabilities(): EncoderCapabilities = noCodec()
		fun getVideoCapabilities(): VideoCapabilities = noCodec()
		fun getAudioCapabilities(): AudioCapabilities = noCodec()

		companion object {
			const val COLOR_FormatSurface = 0x7F000789
			const val COLOR_FormatYUV420Flexible = 0x7F420888
		}
	}

	class EncoderCapabilities private constructor() {
		fun isBitrateModeSupported(mode: Int): Boolean = noCodec()
		fun getQualityRange(): Range<Int> = noCodec()

		companion object {
			const val BITRATE_MODE_CQ = 0
			const val BITRATE_MODE_VBR = 1
			const val BITRATE_MODE_CBR = 2
		}
	}

	class VideoCapabilities private constructor() {
		fun getBitrateRange(): Range<Int> = noCodec()
	}

	class AudioCapabilities private constructor() {
		fun getBitrateRange(): Range<Int> = noCodec()
	}
}

class MediaCodecList(kind: Int) {
	/** No codec is installed on this build. */
	fun getCodecInfos(): Array<MediaCodecInfo> = emptyArray()

	fun findEncoderForFormat(format: MediaFormat?): String? = null

	companion object {
		const val REGULAR_CODECS = 0
		const val ALL_CODECS = 1
	}
}

class MediaCodec private constructor() {
	class BufferInfo {
		var offset: Int = 0
		var size: Int = 0
		var presentationTimeUs: Long = 0L
		var flags: Int = 0

		fun set(newOffset: Int, newSize: Int, newTimeUs: Long, newFlags: Int) {
			offset = newOffset
			size = newSize
			presentationTimeUs = newTimeUs
			flags = newFlags
		}
	}

	class CodecException(message: String?) : IllegalStateException(message) {
		fun getErrorCode(): Int = 0
		fun isTransient(): Boolean = false
		fun isRecoverable(): Boolean = false
		fun getDiagnosticInfo(): String = message ?: ""
	}

	fun configure(format: MediaFormat?, surface: Surface?, crypto: Any?, flags: Int): Unit = noCodec()
	fun createInputSurface(): Surface = noCodec()
	fun start(): Unit = noCodec()
	fun stop(): Unit = noCodec()
	fun release(): Unit = noCodec()
	fun signalEndOfInputStream(): Unit = noCodec()
	fun dequeueInputBuffer(timeoutUs: Long): Int = noCodec()
	fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = noCodec()
	fun getInputBuffer(index: Int): ByteBuffer? = noCodec()
	fun getInputImage(index: Int): Image? = noCodec()
	fun getOutputBuffer(index: Int): ByteBuffer? = noCodec()
	fun getOutputFormat(): MediaFormat = noCodec()
	fun getCodecInfo(): MediaCodecInfo = noCodec()
	fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int): Unit = noCodec()
	fun releaseOutputBuffer(index: Int, render: Boolean): Unit = noCodec()

	companion object {
		const val BUFFER_FLAG_KEY_FRAME = 1
		const val BUFFER_FLAG_CODEC_CONFIG = 2
		const val BUFFER_FLAG_END_OF_STREAM = 4
		const val CONFIGURE_FLAG_ENCODE = 1
		const val INFO_TRY_AGAIN_LATER = -1
		const val INFO_OUTPUT_FORMAT_CHANGED = -2
		const val INFO_OUTPUT_BUFFERS_CHANGED = -3

		fun createByCodecName(name: String): MediaCodec =
			throw java.io.IOException("no codec named $name on this build")

		fun createEncoderByType(type: String): MediaCodec =
			throw java.io.IOException("no encoder for $type on this build")

		fun createDecoderByType(type: String): MediaCodec =
			throw java.io.IOException("no decoder for $type on this build")
	}
}

class MediaMuxer(path: String, format: Int) {
	init {
		// Its only input is an encoder's output, and there is none here.
		refuse()
	}

	private fun refuse(): Unit = throw java.io.IOException("no muxer on this build")

	fun addTrack(format: MediaFormat): Int = noCodec()
	fun start(): Unit = noCodec()
	fun stop(): Unit = noCodec()
	fun release(): Unit = noCodec()
	fun setOrientationHint(degrees: Int): Unit = noCodec()
	fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): Unit = noCodec()

	object OutputFormat {
		const val MUXER_OUTPUT_MPEG_4 = 0
		const val MUXER_OUTPUT_WEBM = 1
		const val MUXER_OUTPUT_3GPP = 2
		const val MUXER_OUTPUT_HEIF = 3
	}
}
