package android.media

/**
 * The shape of a PCM stream.  The constants are AOSP's values, because they
 * travel into the encoder and into the FLAC header.
 */
open class AudioFormat private constructor(
	private val encoding: Int,
	private val sampleRate: Int,
	private val channelMask: Int,
) {
	open fun getEncoding(): Int = encoding
	open fun getSampleRate(): Int = sampleRate
	open fun getChannelMask(): Int = channelMask
	open fun getChannelCount(): Int = when (channelMask) {
		CHANNEL_IN_MONO -> 1
		CHANNEL_IN_STEREO -> 2
		else -> 1
	}

	class Builder {
		private var encoding = ENCODING_DEFAULT
		private var sampleRate = 0
		private var channelMask = CHANNEL_IN_MONO

		fun setEncoding(encoding: Int): Builder { this.encoding = encoding; return this }
		fun setSampleRate(rate: Int): Builder { sampleRate = rate; return this }
		fun setChannelMask(mask: Int): Builder { channelMask = mask; return this }
		fun setChannelIndexMask(mask: Int): Builder { channelMask = mask; return this }
		fun build(): AudioFormat = AudioFormat(encoding, sampleRate, channelMask)
	}

	companion object {
		const val ENCODING_DEFAULT = 1
		const val ENCODING_PCM_16BIT = 2
		const val ENCODING_PCM_8BIT = 3
		const val ENCODING_PCM_FLOAT = 4
		const val ENCODING_PCM_24BIT_PACKED = 21
		const val ENCODING_PCM_32BIT = 22

		const val CHANNEL_IN_MONO = 16
		const val CHANNEL_IN_STEREO = 12
		const val CHANNEL_OUT_MONO = 4
		const val CHANNEL_OUT_STEREO = 12
	}
}
