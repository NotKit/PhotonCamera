package android.media

/**
 * Microphone capture.  This port has no capture backend - neither PulseAudio nor
 * ALSA is wired up - so a recorder is built but never initialises, and every
 * caller takes the failure path it already has for a device with no microphone
 * (FlacAudioRecorder logs "AudioRecord init failed" and records no audio).
 *
 * Everything around that is real: the format, the channel count, the buffer
 * size arithmetic, and the states.
 */
open class AudioRecord private constructor(
	private val audioSource: Int,
	private val format: AudioFormat?,
	private val bufferSizeInBytes: Int,
) {
	private var state = STATE_UNINITIALIZED
	private var recording = false

	open fun getState(): Int = state
	open fun getRecordingState(): Int =
		if (recording) RECORDSTATE_RECORDING else RECORDSTATE_STOPPED
	open fun getAudioSource(): Int = audioSource
	open fun getFormat(): AudioFormat? = format
	open fun getChannelCount(): Int = format?.getChannelCount() ?: 1
	open fun getSampleRate(): Int = format?.getSampleRate() ?: 0
	open fun getBufferSizeInFrames(): Int = bufferSizeInBytes / (2 * getChannelCount())

	open fun startRecording() {
		recording = state == STATE_INITIALIZED
	}

	open fun stop() {
		recording = false
	}

	open fun release() {
		recording = false
		state = STATE_UNINITIALIZED
	}

	/** ERROR_INVALID_OPERATION: there is nothing capturing. */
	open fun read(audioData: ShortArray?, offsetInShorts: Int, sizeInShorts: Int): Int =
		ERROR_INVALID_OPERATION

	open fun read(audioData: ByteArray?, offsetInBytes: Int, sizeInBytes: Int): Int =
		ERROR_INVALID_OPERATION

	open fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = false

	open fun setPreferredMicrophoneDirection(direction: Int): Boolean = false

	class Builder {
		private var source = MediaRecorder.AudioSource.DEFAULT
		private var format: AudioFormat? = null
		private var bufferSize = 0

		fun setAudioSource(source: Int): Builder { this.source = source; return this }
		fun setAudioFormat(format: AudioFormat?): Builder { this.format = format; return this }
		fun setBufferSizeInBytes(bytes: Int): Builder { bufferSize = bytes; return this }
		fun build(): AudioRecord = AudioRecord(source, format, bufferSize)
	}

	companion object {
		const val STATE_UNINITIALIZED = 0
		const val STATE_INITIALIZED = 1
		const val RECORDSTATE_STOPPED = 1
		const val RECORDSTATE_RECORDING = 3
		const val ERROR = -1
		const val ERROR_BAD_VALUE = -2
		const val ERROR_INVALID_OPERATION = -3
		const val ERROR_DEAD_OBJECT = -6

		/** AOSP's floor: 1024 frames, which is what a HAL period is here too. */
		fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int {
			if (sampleRateInHz <= 0)
				return ERROR_BAD_VALUE
			val channels = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
			val bytesPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) 1 else 2
			return 1024 * channels * bytesPerSample
		}
	}
}
