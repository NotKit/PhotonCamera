package android.media

/**
 * The system audio service.  There is no audio policy behind this port, so the
 * device lists are empty and the modes are remembered but do nothing.
 */
open class AudioManager {
	private var mode = MODE_NORMAL

	open fun getDevices(flags: Int): Array<AudioDeviceInfo> = arrayOf()
	open fun getMode(): Int = mode
	open fun setMode(mode: Int) { this.mode = mode }
	open fun isMicrophoneMute(): Boolean = false
	open fun setMicrophoneMute(on: Boolean) {}
	open fun getStreamVolume(streamType: Int): Int = 0
	open fun getStreamMaxVolume(streamType: Int): Int = 0

	companion object {
		const val GET_DEVICES_INPUTS = 1
		const val GET_DEVICES_OUTPUTS = 2
		const val GET_DEVICES_ALL = 3
		const val MODE_NORMAL = 0
		const val STREAM_MUSIC = 3
		const val STREAM_SYSTEM = 1
	}
}
