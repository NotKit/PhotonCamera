/* java.text.SimpleDateFormat and DateFormat over localtime(3).  The pattern
 * letters this tree uses are y M d H m s S E a z, with 'text' quoting. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.text

import java.util.Date
import java.util.Locale
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.mktime
import platform.posix.time_tVar
import platform.posix.tm

private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTHS_LONG = arrayOf("January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December")
private val DAYS_SHORT = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val DAYS_LONG = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
    "Friday", "Saturday")

abstract class DateFormat {
    abstract fun format(date: Date): String
    fun format(millis: Long): String = format(Date(millis))
    abstract fun parse(source: String): Date

    companion object {
        fun getDateInstance(): DateFormat = SimpleDateFormat("MMM d, yyyy")
        fun getDateTimeInstance(): DateFormat = SimpleDateFormat("MMM d, yyyy h:mm:ss a")
        fun getTimeInstance(): DateFormat = SimpleDateFormat("h:mm:ss a")
    }
}

class SimpleDateFormat(private val pattern: String, private val locale: Locale = Locale.US) : DateFormat() {
    fun toPattern(): String = pattern
    fun getLocale(): Locale = locale
    fun setTimeZone(tz: Any?) {}

    override fun format(date: Date): String = memScoped {
        val t = alloc<time_tVar>()
        t.value = date.getTime() / 1000L
        val lt = alloc<tm>()
        localtime_r(t.ptr, lt.ptr)
        val millis = (date.getTime() % 1000L).toInt().let { if (it < 0) it + 1000 else it }
        render(lt, millis)
    }

    private fun render(lt: tm, millis: Int): String {
        val sb = StringBuilder(pattern.length + 8)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                i++
                if (i < pattern.length && pattern[i] == '\'') { sb.append('\''); i++; continue }
                while (i < pattern.length && pattern[i] != '\'') { sb.append(pattern[i]); i++ }
                i++
                continue
            }
            if (!c.isLetter()) { sb.append(c); i++; continue }
            var n = 0
            while (i + n < pattern.length && pattern[i + n] == c) n++
            val year = lt.tm_year + 1900
            val hour12 = if (lt.tm_hour % 12 == 0) 12 else lt.tm_hour % 12
            sb.append(when (c) {
                'y' -> if (n == 2) pad(year % 100, 2) else pad(year, n)
                'M' -> when {
                    n >= 4 -> MONTHS_LONG[lt.tm_mon]
                    n == 3 -> MONTHS_SHORT[lt.tm_mon]
                    else -> pad(lt.tm_mon + 1, n)
                }
                'd' -> pad(lt.tm_mday, n)
                'D' -> pad(lt.tm_yday + 1, n)
                'E' -> if (n >= 4) DAYS_LONG[lt.tm_wday] else DAYS_SHORT[lt.tm_wday]
                'H' -> pad(lt.tm_hour, n)
                'h' -> pad(hour12, n)
                'k' -> pad(if (lt.tm_hour == 0) 24 else lt.tm_hour, n)
                'K' -> pad(lt.tm_hour % 12, n)
                'm' -> pad(lt.tm_min, n)
                's' -> pad(lt.tm_sec, n)
                'S' -> pad(millis, n).takeLast(kotlin.math.max(n, 1))
                'a' -> if (lt.tm_hour < 12) "AM" else "PM"
                'z', 'Z' -> tzOffset(lt)
                else -> ""
            })
            i += n
        }
        return sb.toString()
    }

    /** Only the literal patterns this tree round-trips: y M d H m s, in order. */
    override fun parse(source: String): Date = memScoped {
        val nums = Regex("\\d+").findAll(source).map { it.value.toInt() }.toList()
        val letters = pattern.filter { it.isLetter() }.toSet()
        val lt = alloc<tm>()
        var k = 0
        fun next(): Int = if (k < nums.size) nums[k++] else 0
        for (c in "yMdHms") {
            if (!letters.contains(c)) continue
            when (c) {
                'y' -> lt.tm_year = next() - 1900
                'M' -> lt.tm_mon = next() - 1
                'd' -> lt.tm_mday = next()
                'H' -> lt.tm_hour = next()
                'm' -> lt.tm_min = next()
                's' -> lt.tm_sec = next()
            }
        }
        lt.tm_isdst = -1
        Date(mktime(lt.ptr) * 1000L)
    }

    private fun pad(v: Int, w: Int): String = v.toString().padStart(w, '0')

    private fun tzOffset(lt: tm): String {
        val off = lt.tm_gmtoff
        val sign = if (off < 0) "-" else "+"
        val a = kotlin.math.abs(off)
        return "GMT$sign${pad((a / 3600).toInt(), 2)}:${pad(((a % 3600) / 60).toInt(), 2)}"
    }
}

/** java.text.DecimalFormat: the "#.##"/"0.00" subset, no grouping locale data. */
class DecimalFormat(private val pattern: String = "#.###") {
    fun format(v: Double): String {
        val frac = pattern.substringAfter('.', "")
        val min = frac.count { it == '0' }
        val max = frac.length
        var s = java.lang.formatJava("%.${max}f", arrayOf(v))
        if (max > 0 && s.contains('.')) {
            var tail = s.substringAfter('.')
            while (tail.length > min && tail.endsWith('0')) tail = tail.dropLast(1)
            s = if (tail.isEmpty()) s.substringBefore('.') else s.substringBefore('.') + "." + tail
        }
        return s
    }

    fun format(v: Float): String = format(v.toDouble())
    fun format(v: Long): String = v.toString()
}

class ParseException(message: String? = null, val errorOffset: Int = 0) : Exception(message)
