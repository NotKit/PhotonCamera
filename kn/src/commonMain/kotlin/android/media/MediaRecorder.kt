package android.media

import android.view.Surface

/**
 * Video/audio recording.  This port has no encoder behind it (atl's
 * video_encoder.h is the JNI side of a GStreamer pipeline, which is not part of
 * the camera backend copied here), so a recorder accepts its configuration and
 * then fails at prepare(), which is the same shape as a device that cannot
 * encode: the caller's IOException path runs and no file appears.
 *
 * The constant classes are AOSP's values because they are written into the
 * configuration the app builds and read back in its own logic.
 */
open class MediaRecorder {

	object AudioSource {
		const val DEFAULT = 0
		const val MIC = 1
		const val VOICE_UPLINK = 2
		const val VOICE_DOWNLINK = 3
		const val VOICE_CALL = 4
		const val CAMCORDER = 5
		const val VOICE_RECOGNITION = 6
		const val VOICE_COMMUNICATION = 7
		const val UNPROCESSED = 9
		const val VOICE_PERFORMANCE = 10
	}

	object VideoSource {
		const val DEFAULT = 0
		const val CAMERA = 1
		const val SURFACE = 2
	}

	object OutputFormat {
		const val DEFAULT = 0
		const val THREE_GPP = 1
		const val MPEG_4 = 2
		const val AMR_NB = 3
		const val AMR_WB = 4
		const val AAC_ADTS = 6
		const val WEBM = 9
	}

	object AudioEncoder {
		const val DEFAULT = 0
		const val AMR_NB = 1
		const val AMR_WB = 2
		const val AAC = 3
		const val HE_AAC = 4
		const val AAC_ELD = 5
		const val VORBIS = 6
		const val OPUS = 7
	}

	object VideoEncoder {
		const val DEFAULT = 0
		const val H263 = 1
		const val H264 = 2
		const val MPEG_4_SP = 3
		const val VP8 = 4
		const val HEVC = 5
	}

	fun interface OnInfoListener {
		fun onInfo(mr: MediaRecorder?, what: Int, extra: Int)
	}

	fun interface OnErrorListener {
		fun onError(mr: MediaRecorder?, what: Int, extra: Int)
	}

	private var infoListener: OnInfoListener? = null
	private var errorListener: OnErrorListener? = null
	private var surface: Surface? = null

	open fun setAudioSource(source: Int) {}
	open fun setVideoSource(source: Int) {}
	open fun setOutputFormat(format: Int) {}
	open fun setAudioEncoder(encoder: Int) {}
	open fun setVideoEncoder(encoder: Int) {}
	open fun setAudioEncodingBitRate(rate: Int) {}
	open fun setAudioSamplingRate(rate: Int) {}
	open fun setAudioChannels(channels: Int) {}
	open fun setVideoEncodingBitRate(rate: Int) {}
	open fun setVideoFrameRate(rate: Int) {}
	open fun setVideoSize(width: Int, height: Int) {}
	open fun setOutputFile(path: String?) {}
	open fun setMaxDuration(durationMs: Int) {}
	open fun setMaxFileSize(bytes: Long) {}
	open fun setOrientationHint(degrees: Int) {}

	open fun setOnInfoListener(listener: OnInfoListener?) {
		infoListener = listener
	}

	open fun setOnErrorListener(listener: OnErrorListener?) {
		errorListener = listener
	}

	/** the encoder's input; a Surface with no sink behind it, so it is invalid */
	open fun getSurface(): Surface {
		var s = surface
		if (s == null) {
			s = Surface()
			surface = s
		}
		return s
	}

	open fun prepare() {
		throw java.io.IOException("no video encoder on this build")
	}

	open fun start() {
		throw IllegalStateException("no video encoder on this build")
	}

	open fun stop() {}
	open fun reset() { surface = null }
	open fun release() { surface = null; infoListener = null; errorListener = null }

	companion object {
		const val MEDIA_RECORDER_INFO_UNKNOWN = 1
		const val MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 800
		const val MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED = 801
		const val MEDIA_RECORDER_ERROR_UNKNOWN = 1
	}
}
