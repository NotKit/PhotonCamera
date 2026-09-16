/* java.util.TimeZone: the process's zone, which localtime(3) already applies.
 * Only getDefault()/getID() are real; SimpleDateFormat here always formats in
 * local time, so setTimeZone on anything else is accepted and has no effect. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.util

class TimeZone private constructor(private val id: String) {
    fun getID(): String = id
    fun getDisplayName(): String = id
    override fun toString(): String = id

    companion object {
        fun getDefault(): TimeZone = TimeZone(
            java.lang.System.getenv("TZ") ?: "localtime",
        )

        fun getTimeZone(id: String): TimeZone = TimeZone(id)
        fun getAvailableIDs(): Array<String> = arrayOf(getDefault().getID())
    }
}
