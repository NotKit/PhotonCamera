/* Copied from fenix-kn's androidshim/JavaUtilLocale.kt. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.util

import kotlinx.cinterop.toKString
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.getenv
import platform.posix.stderr

open class Locale private constructor(
	private val lang: String,
	private val scriptTag: String,
	private val region: String,
	private val variantTag: String,
) {
	constructor(language: String?) : this((language ?: "").lowercase(), "", "", "")

	constructor(language: String?, country: String?) :
		this((language ?: "").lowercase(), "", (country ?: "").uppercase(), "")

	constructor(language: String?, country: String?, variant: String?) :
		this((language ?: "").lowercase(), "", (country ?: "").uppercase(), variant ?: "")

	open fun getLanguage(): String = lang
	open fun getCountry(): String = region
	open fun getVariant(): String = variantTag
	open fun getScript(): String = scriptTag

	val language: String get() = lang
	val country: String get() = region

	/** BCP-47. */
	open fun toLanguageTag(): String {
		val sb = StringBuilder(lang.ifEmpty { "und" })
		if (scriptTag.isNotEmpty()) { sb.append('-'); sb.append(scriptTag) }
		if (region.isNotEmpty()) { sb.append('-'); sb.append(region) }
		if (variantTag.isNotEmpty()) { sb.append('-'); sb.append(variantTag) }
		return sb.toString()
	}

	/** java.util.Locale.toString(): underscore-joined. */
	override fun toString(): String {
		val sb = StringBuilder(lang)
		if (region.isNotEmpty() || variantTag.isNotEmpty()) { sb.append('_'); sb.append(region) }
		if (variantTag.isNotEmpty()) { sb.append('_'); sb.append(variantTag) }
		return sb.toString()
	}

	override fun equals(other: Any?): Boolean {
		val o = other as? Locale ?: return false
		return lang == o.lang && scriptTag == o.scriptTag &&
			region == o.region && variantTag == o.variantTag
	}

	override fun hashCode(): Int =
		((lang.hashCode() * 31 + scriptTag.hashCode()) * 31 + region.hashCode()) * 31 +
			variantTag.hashCode()

	companion object {
		val ROOT: Locale = Locale("", "", "", "")
		val US: Locale = Locale("en", "", "US", "")
		val UK: Locale = Locale("en", "", "GB", "")
		val ENGLISH: Locale = Locale("en", "", "", "")
		val CANADA: Locale = Locale("en", "", "CA", "")
		val FRANCE: Locale = Locale("fr", "", "FR", "")
		val GERMANY: Locale = Locale("de", "", "DE", "")
		val CHINESE: Locale = Locale("zh", "", "", "")
		val SIMPLIFIED_CHINESE: Locale = Locale("zh", "", "CN", "")
		val TRADITIONAL_CHINESE: Locale = Locale("zh", "", "TW", "")

		private var sDefault: Locale? = null

		fun getDefault(): Locale {
			var d = sDefault
			if (d == null) {
				d = fromEnvironment()
				sDefault = d
			}
			return d
		}

		fun setDefault(locale: Locale?) {
			if (locale != null) sDefault = locale
		}

		/** BCP-47 in, Locale out.  Subtag shapes only -- no registry lookup. */
		fun forLanguageTag(tag: String?): Locale {
			val t = (tag ?: "").replace('_', '-')
			if (t.isEmpty()) return ROOT
			val parts = t.split("-").filter { it.isNotEmpty() }
			if (parts.isEmpty()) return ROOT
			var i = 0
			val language = parts[0].lowercase()
			i++
			var script = ""
			if (i < parts.size && parts[i].length == 4 && parts[i].all { it.isLetter() }) {
				script = parts[i][0].uppercase() + parts[i].substring(1).lowercase()
				i++
			}
			var country = ""
			if (i < parts.size &&
				((parts[i].length == 2 && parts[i].all { it.isLetter() }) ||
					(parts[i].length == 3 && parts[i].all { it.isDigit() }))
			) {
				country = parts[i].uppercase()
				i++
			}
			val variant = if (i < parts.size) parts.subList(i, parts.size).joinToString("-") else ""
			return Locale(language, script, country, variant)
		}

		private fun say(msg: String) {
			fprintf(stderr, "%s\n", "[androidshim] Locale.getDefault: $msg")
			fflush(stderr)
		}

		/** POSIX `language[_TERRITORY][.codeset][@modifier]`. */
		private fun fromEnvironment(): Locale {
			val raw = envOf("LC_ALL") ?: envOf("LC_MESSAGES") ?: envOf("LANG")
			if (raw == null) {
				say("en-US MADEUP-fallback: no LC_ALL/LC_MESSAGES/LANG in the environment")
				return US
			}
			var s = raw
			val at = s.indexOf('@'); if (at >= 0) s = s.substring(0, at)
			val dot = s.indexOf('.'); if (dot >= 0) s = s.substring(0, dot)
			if (s == "C" || s == "POSIX" || s.isEmpty()) {
				say("en-US MADEUP-mapping: \$LANG=$raw names no language")
				return US
			}
			val under = s.indexOf('_')
			val language = if (under >= 0) s.substring(0, under) else s
			val country = if (under >= 0) s.substring(under + 1) else ""
			return Locale(language, "", country.uppercase(), "")
		}

		private fun envOf(name: String): String? {
			val v = getenv(name)?.toKString()
			return if (v.isNullOrEmpty()) null else v
		}
	}
}
