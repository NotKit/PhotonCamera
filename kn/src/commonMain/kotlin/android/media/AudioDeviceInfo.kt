package android.media

/** One audio endpoint.  Nothing enumerates any in this port. */
open class AudioDeviceInfo internal constructor(private val type: Int, private val source: Boolean) {
	open fun getType(): Int = type
	open fun isSource(): Boolean = source
	open fun isSink(): Boolean = !source
	open fun getId(): Int = 0

	companion object {
		const val TYPE_BUILTIN_MIC = 15
		const val TYPE_BUILTIN_SPEAKER = 2
		const val TYPE_WIRED_HEADSET = 3
		const val TYPE_TELEPHONY = 18
	}
}
