package android.media

/**
 * The recording presets a device advertises.  There is no media profile
 * database here, so only QUALITY_HIGH exists and it describes a 1080p30 H.264
 * stream - the numbers the app copies into its MediaRecorder configuration.
 */
open class CamcorderProfile private constructor(
	var quality: Int,
	var videoFrameWidth: Int,
	var videoFrameHeight: Int,
	var videoFrameRate: Int,
	var videoBitRate: Int,
	var videoCodec: Int,
	var audioChannels: Int,
	var audioSampleRate: Int,
	var audioBitRate: Int,
	var audioCodec: Int,
	var fileFormat: Int,
	var duration: Int,
) {
	companion object {
		const val QUALITY_LOW = 0
		const val QUALITY_HIGH = 1
		const val QUALITY_QCIF = 2
		const val QUALITY_CIF = 3
		const val QUALITY_480P = 4
		const val QUALITY_720P = 5
		const val QUALITY_1080P = 6
		const val QUALITY_QVGA = 7
		const val QUALITY_2160P = 8

		fun hasProfile(quality: Int): Boolean = quality == QUALITY_HIGH
		fun hasProfile(cameraId: Int, quality: Int): Boolean = quality == QUALITY_HIGH

		fun get(quality: Int): CamcorderProfile = get(0, quality)

		fun get(cameraId: Int, quality: Int): CamcorderProfile =
			CamcorderProfile(quality, 1920, 1080, 30, 17_000_000,
				MediaRecorder.VideoEncoder.H264, 2, 44100, 128_000,
				MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.MPEG_4, 30)
	}
}
