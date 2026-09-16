package android.hardware.camera2.impl

import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DeviceStateSensorOrientationMap
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OisSample
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Pair
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import photoncam.camera.MetaType
import photoncam.camera.MetadataNatives

/**
 * A bag of camera2 metadata entries living in atl-touch's camera2_metadata.c,
 * plus the marshaling from the HAL's (tag, type, values) to the types the Key
 * constants declare.  Hand-written from atlas's Java of the same name: the
 * original dispatches on java.lang.Class, which Kotlin/Native has not, so the
 * key's type token is a photoncam.camera.MetaType.
 *
 * A tag ATL has no name for still reads back by id, and a key whose type we do
 * not know reads back as the natural array, so a vendor key never throws.
 */
class CameraMetadataNative private constructor(private var nativePtr: Long) {

	companion object {
		/* which key set getAvailableKeys() asks for; camera2_metadata.h */
		const val KEYS_CHARACTERISTICS = 0
		const val KEYS_REQUEST = 1
		const val KEYS_RESULT = 2

		/* camera2_metadata.h value types */
		const val TYPE_BYTE = MetadataNatives.TYPE_BYTE
		const val TYPE_INT32 = MetadataNatives.TYPE_INT32
		const val TYPE_FLOAT = MetadataNatives.TYPE_FLOAT
		const val TYPE_INT64 = MetadataNatives.TYPE_INT64
		const val TYPE_DOUBLE = MetadataNatives.TYPE_DOUBLE
		const val TYPE_RATIONAL = MetadataNatives.TYPE_RATIONAL

		/* ATL_CAMERA2_TAG_INVALID; android.colorCorrection.mode is tag 0 */
		const val TAG_INVALID = -1

		private const val MAX_REGIONS = "android.control.maxRegions"
		/* the suffix every full-resolution-sensor-mode twin of a tag carries */
		private const val MAXIMUM_RESOLUTION = "MaximumResolution"

		/** null when no backend can serve camera2 at all. */
		fun getCameraIdList(): Array<String>? = MetadataNatives.getCameraIdList()

		/** null when the backend does not know this camera. */
		fun getStaticMetadata(cameraId: String?): CameraMetadataNative? {
			val ptr = MetadataNatives.getStaticMetadata(cameraId)
			return if (ptr == 0L) null else CameraMetadataNative(ptr)
		}

		/** An empty bag, for a capture request being built. */
		fun create(): CameraMetadataNative = CameraMetadataNative(MetadataNatives.create())

		/** Takes ownership of a bag native code built (a capture result). */
		fun adopt(ptr: Long): CameraMetadataNative? =
			if (ptr == 0L) null else CameraMetadataNative(ptr)

		/** the tags of one of the KEYS_* sets; empty when the backend has no list. */
		fun getAvailableKeys(cameraId: String?, which: Int): IntArray =
			MetadataNatives.getAvailableKeys(cameraId, which) ?: IntArray(0)

		/* the null answer is a wall for a camera app; say so once */
		private var facesReported = false
	}

	fun copy(): CameraMetadataNative = CameraMetadataNative(MetadataNatives.copy(ptr()))

	/** the bag itself, for the native camera2 device */
	fun getPtr(): Long = ptr()

	fun close() {
		if (nativePtr != 0L) {
			MetadataNatives.free(nativePtr)
			nativePtr = 0L
		}
	}

	private fun ptr(): Long {
		check(nativePtr != 0L) { "metadata is closed" }
		return nativePtr
	}

	fun getTags(): IntArray = MetadataNatives.getTags(ptr())

	/** TAG_INVALID when neither the generated table nor a vendor entry names it. */
	fun getTag(name: String?): Int = MetadataNatives.getTag(ptr(), name)

	/** null when nothing names the tag. */
	fun getTagName(tag: Int): String? = MetadataNatives.getTagName(ptr(), tag)

	/** TYPE_*, or -1 when the tag is not present. */
	fun getType(tag: Int): Int = MetadataNatives.getType(ptr(), tag)

	fun readBytes(tag: Int): ByteArray? = MetadataNatives.readBytes(ptr(), tag)
	fun readInts(tag: Int): IntArray? = MetadataNatives.readInts(ptr(), tag)
	fun readFloats(tag: Int): FloatArray? = MetadataNatives.readFloats(ptr(), tag)
	fun readLongs(tag: Int): LongArray? = MetadataNatives.readLongs(ptr(), tag)
	fun readDoubles(tag: Int): DoubleArray? = MetadataNatives.readDoubles(ptr(), tag)

	/**
	 * The value of a key, marshaled to the type its Key constant declares.
	 * null when the camera does not report the key; a null or "Any" token
	 * yields the raw values as an array.
	 */
	fun get(name: String?, type: MetaType?): Any? {
		if (name == null)
			return null

		/* a key the HAL spreads over several tags has no tag of its own */
		when (type?.name) {
			"StreamConfigurationMap" ->
				return streamConfigurationMap(
					if (name.endsWith(MAXIMUM_RESOLUTION)) MAXIMUM_RESOLUTION else "")
			"DeviceStateSensorOrientationMap" -> return deviceStateSensorOrientationMap()
			"Face[]" -> return faces()
			"OisSample[]" -> return oisSamples()
			"LensShadingMap" -> return lensShadingMap()
			"DynamicRangeProfiles" -> return DynamicRangeProfiles()
		}
		if (name.startsWith(MAX_REGIONS) && name.length > MAX_REGIONS.length)
			return maxRegions(name.substring(MAX_REGIONS.length))

		val tag = getTag(name)
		if (tag == TAG_INVALID)
			return null
		return get(tag, type)
	}

	fun get(tag: Int, type: MetaType?): Any? {
		val nativeType = getType(tag)

		if (nativeType < 0)
			return null
		if (type == null || type.name == "Any")
			return raw(tag, nativeType)

		/* an enum is a byte in the HAL and an int in Java, so the int readers
		 * widen bytes; every "int[]" key is a byte[] key here */
		when (type.name) {
			"Int" -> {
				val v = readInts(tag)
				return if (v == null || v.isEmpty()) null else v[0]
			}
			"Byte" -> {
				val v = readBytes(tag)
				return if (v == null || v.isEmpty()) null else v[0]
			}
			"Boolean" -> {
				val v = readBytes(tag)
				return if (v == null || v.isEmpty()) null else v[0].toInt() != 0
			}
			"Long" -> {
				val v = readLongs(tag)
				return if (v == null || v.isEmpty()) null else v[0]
			}
			"Float" -> {
				val v = readFloats(tag)
				return if (v == null || v.isEmpty()) null else v[0]
			}
			"Double" -> {
				val v = readDoubles(tag)
				return if (v == null || v.isEmpty()) null else v[0]
			}
			"Int[]" -> return readInts(tag)
			"Byte[]" -> return readBytes(tag)
			"Float[]" -> return readFloats(tag)
			"Long[]" -> return readLongs(tag)
			"Double[]" -> return readDoubles(tag)
			"String" -> {
				val v = readBytes(tag)
				return if (v == null) null else cString(v)
			}
			"Rational" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 2) null else Rational(v[0], v[1])
			}
			"Rational[]" -> {
				val v = readInts(tag) ?: return null
				return Array(v.size / 2) { Rational(v[it * 2], v[it * 2 + 1]) }
			}
			"Size" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 2) null else Size(v[0], v[1])
			}
			"Size[]" -> {
				val v = readInts(tag) ?: return null
				return Array(v.size / 2) { Size(v[it * 2], v[it * 2 + 1]) }
			}
			"SizeF" -> {
				val v = readFloats(tag)
				return if (v == null || v.size < 2) null else SizeF(v[0], v[1])
			}
			/* the HAL says (x, y, width, height), Rect wants the two corners */
			"Rect" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 4) null
				else Rect(v[0], v[1], v[0] + v[2], v[1] + v[3])
			}
			"Rect[]" -> {
				val v = readInts(tag) ?: return null
				return Array(v.size / 4) {
					Rect(v[it * 4], v[it * 4 + 1], v[it * 4] + v[it * 4 + 2],
						v[it * 4 + 1] + v[it * 4 + 3])
				}
			}
			/* the HAL's region is (x, y, width, height, weight) */
			"MeteringRectangle" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 5) null
				else MeteringRectangle(v[0], v[1], v[2], v[3], v[4])
			}
			"MeteringRectangle[]" -> {
				val v = readInts(tag) ?: return null
				return Array(v.size / 5) {
					MeteringRectangle(v[it * 5], v[it * 5 + 1], v[it * 5 + 2],
						v[it * 5 + 3], v[it * 5 + 4])
				}
			}
			"ColorSpaceTransform" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 18) null else ColorSpaceTransform(v)
			}
			"BlackLevelPattern" -> {
				val v = readInts(tag)
				return if (v == null || v.size < 4) null else BlackLevelPattern(v)
			}
			"RggbChannelVector" -> {
				val v = readFloats(tag)
				return if (v == null || v.size < 4) null
				else RggbChannelVector(v[0], v[1], v[2], v[3])
			}
			"Point[]" -> {
				val v = readInts(tag) ?: return null
				return Array(v.size / 2) { Point(v[it * 2], v[it * 2 + 1]) }
			}
			/* a Pair key is two values of the tag's own type in one entry
			 * (LENS_FOCUS_RANGE floats, SENSOR_NOISE_PROFILE doubles) */
			"Pair" -> return pair(tag, nativeType, 0)
			"Pair[]" -> {
				val count = if (nativeType == TYPE_DOUBLE) readDoubles(tag)?.size ?: 0
				else readFloats(tag)?.size ?: 0
				return Array(count / 2) { pair(tag, nativeType, it) }
			}
			"Range" -> return range(tag, nativeType, 0)
			"Range[]" -> {
				val count = if (nativeType == TYPE_INT64) readLongs(tag)?.size ?: 0
				else readInts(tag)?.size ?: 0
				return Array(count / 2) { range(tag, nativeType, it) }
			}
		}

		/* a key whose type we do not model: hand back the values */
		return raw(tag, nativeType)
	}

	/**
	 * Writes a key, narrowing the value to whatever type the tag is declared
	 * with in native code.  A key this camera has no tag for is dropped rather
	 * than thrown, the same way an unknown key reads back as null.
	 */
	fun set(name: String?, value: Any?) {
		val tag = getTag(name)

		if (tag == TAG_INVALID)
			return
		if (value == null) {
			MetadataNatives.erase(ptr(), tag)
			return
		}
		if (value is String) {
			val utf8 = value.encodeToByteArray()
			MetadataNatives.writeBytes(ptr(), tag, utf8 + 0)
			return
		}
		if (value is ByteArray) {
			MetadataNatives.writeBytes(ptr(), tag, value)
			return
		}

		val fractional = asDoubles(value)
		if (fractional != null) {
			MetadataNatives.writeDoubles(ptr(), tag, fractional)
			return
		}
		val integral = asLongs(value)
		if (integral != null)
			MetadataNatives.writeLongs(ptr(), tag, integral)
	}

	/*
	 * AOSP's typed writers.  A camera app that overrides what the HAL reported -
	 * a black level, a colour transform - has no public way to do it and reaches
	 * these by reflection on Android; here the app calls them directly
	 * (CameraReflectionApi).  The three Key classes share no supertype, hence
	 * three overloads over the one writer above.
	 */
	fun <T> setBase(key: CameraCharacteristics.Key<T>?, value: T?) = set(key?.getName(), value)

	fun <T> set(key: CameraCharacteristics.Key<T>?, value: T?) = set(key?.getName(), value)

	fun <T> set(key: CaptureResult.Key<T>?, value: T?) = set(key?.getName(), value)

	fun <T> set(key: CaptureRequest.Key<T>?, value: T?) = set(key?.getName(), value)

	/* ---- narrowing to the two widest shapes the native writers take ---- */

	private fun asDoubles(value: Any): DoubleArray? {
		if (value is Float)
			return doubleArrayOf(value.toDouble())
		if (value is Double)
			return doubleArrayOf(value)
		if (value is RggbChannelVector) {
			val out = FloatArray(RggbChannelVector.COUNT)
			value.copyTo(out, 0)
			return asDoubles(out)
		}
		if (value is FloatArray)
			return DoubleArray(value.size) { value[it].toDouble() }
		if (value is DoubleArray)
			return value
		return null
	}

	/* the HAL's own layouts: a Rect is (x, y, width, height), a Rational and a
	 * Range are both a pair */
	private fun asLongs(value: Any): LongArray? {
		if (value is Number)
			return longArrayOf(value.toLong())
		if (value is Boolean)
			return longArrayOf(if (value) 1L else 0L)
		if (value is IntArray)
			return LongArray(value.size) { value[it].toLong() }
		if (value is LongArray)
			return value
		if (value is BooleanArray)
			return LongArray(value.size) { if (value[it]) 1L else 0L }
		if (value is Rect)
			return longArrayOf(value.left.toLong(), value.top.toLong(),
				value.width().toLong(), value.height().toLong())
		if (value is Size)
			return longArrayOf(value.getWidth().toLong(), value.getHeight().toLong())
		if (value is Rational)
			return longArrayOf(value.getNumerator().toLong(), value.getDenominator().toLong())
		if (value is Range<*>) {
			val lower = value.getLower()
			val upper = value.getUpper()
			if (lower is Number && upper is Number)
				return longArrayOf(lower.toLong(), upper.toLong())
		}
		if (value is MeteringRectangle)
			return asLongs(arrayOf(value))
		if (value is Array<*> && value.isNotEmpty() && value[0] is MeteringRectangle) {
			val out = LongArray(value.size * 5)
			for (i in value.indices) {
				val r = value[i] as MeteringRectangle
				out[i * 5] = r.getX().toLong()
				out[i * 5 + 1] = r.getY().toLong()
				out[i * 5 + 2] = r.getWidth().toLong()
				out[i * 5 + 3] = r.getHeight().toLong()
				out[i * 5 + 4] = r.getMeteringWeight().toLong()
			}
			return out
		}
		if (value is ColorSpaceTransform) {
			val elements = IntArray(18)
			value.copyElements(elements, 0)
			return asLongs(elements)
		}
		if (value is BlackLevelPattern) {
			val offsets = IntArray(BlackLevelPattern.COUNT)
			value.copyTo(offsets, 0)
			return asLongs(offsets)
		}
		return null
	}

	/* ---- keys the HAL spreads over several tags ---- */

	/** android.control.maxRegions is (AE, AWB, AF); the three keys index into it. */
	private fun maxRegions(which: String): Int? {
		val regions = ints(MAX_REGIONS)
		val index = when (which) {
			"Ae" -> 0
			"Awb" -> 1
			"Af" -> 2
			else -> -1
		}
		if (regions == null || index < 0 || index >= regions.size)
			return null
		return regions[index]
	}

	/**
	 * The stream tables, either the ordinary set or the maximum-resolution
	 * (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) one.  A camera without the second
	 * set answers null for it rather than handing back the first.
	 */
	private fun streamConfigurationMap(suffix: String): StreamConfigurationMap? {
		val configurations = ints("android.scaler.availableStreamConfigurations$suffix")
			?: return null
		return StreamConfigurationMap(configurations,
			longs("android.scaler.availableMinFrameDurations$suffix"),
			longs("android.scaler.availableStallDurations$suffix"), null, null)
	}

	private fun deviceStateSensorOrientationMap(): DeviceStateSensorOrientationMap? {
		val orientations = longs("android.info.deviceStateOrientations") ?: return null
		return DeviceStateSensorOrientationMap(orientations)
	}

	/**
	 * SIMPLE face detection reports bounds and scores; ids and landmarks are FULL.
	 *
	 * A result that names the mode always answers with an array, empty when the
	 * mode is OFF or the HAL left the per-face tags out, as AOSP does.  Only
	 * metadata with none of the five face tags at all - a request, or a
	 * characteristics bag - has no faces to report and answers null.
	 */
	private fun faces(): Array<Face>? {
		val mode = ints("android.statistics.faceDetectMode")
		val rectangles = ints("android.statistics.faceRectangles")
		val scores = bytes("android.statistics.faceScores")
		val ids = ints("android.statistics.faceIds")
		val landmarks = ints("android.statistics.faceLandmarks")

		if (mode == null && rectangles == null && scores == null && ids == null && landmarks == null) {
			if (!facesReported) {
				facesReported = true
				android.util.Log.i("CameraMetadata",
					"no face tags in this metadata, STATISTICS_FACES is null")
			}
			return null
		}
		if (mode != null && mode.isNotEmpty() &&
			mode[0] == CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
			return arrayOf()
		if (rectangles == null)
			return arrayOf()

		val out = ArrayList<Face>()
		var i = 0
		while (i + 3 < rectangles.size) {
			val face = i / 4
			val builder = Face.Builder()

			builder.setBounds(Rect(rectangles[i], rectangles[i + 1],
				rectangles[i] + rectangles[i + 2], rectangles[i + 1] + rectangles[i + 3]))
			if (scores != null && face < scores.size)
				builder.setScore(maxOf(Face.SCORE_MIN,
					minOf(Face.SCORE_MAX, scores[face].toInt() and 0xff)))
			if (landmarks != null && landmarks.size >= (face + 1) * 6) {
				builder.setLeftEyePosition(Point(landmarks[face * 6], landmarks[face * 6 + 1]))
				builder.setRightEyePosition(Point(landmarks[face * 6 + 2], landmarks[face * 6 + 3]))
				builder.setMouthPosition(Point(landmarks[face * 6 + 4], landmarks[face * 6 + 5]))
				if (ids != null && face < ids.size)
					builder.setId(ids[face])
			}
			out.add(builder.build()!!)
			i += 4
		}
		return out.toTypedArray()
	}

	private fun oisSamples(): Array<OisSample>? {
		val timestamps = longs("android.statistics.oisTimestamps") ?: return null
		val x = floats("android.statistics.oisXShifts") ?: return null
		val y = floats("android.statistics.oisYShifts") ?: return null

		val count = minOf(timestamps.size, minOf(x.size, y.size))
		return Array(count) { OisSample(timestamps[it], x[it], y[it]) }
	}

	/* the map size travels with the map, so a result describes its own grid */
	private fun lensShadingMap(): LensShadingMap? {
		val gains = floats("android.statistics.lensShadingMap") ?: return null
		val size = ints("android.lens.info.shadingMapSize")

		if (size == null || size.size < 2)
			return null
		return LensShadingMap(gains, size[1], size[0])
	}

	private fun ints(name: String): IntArray? {
		val tag = getTag(name)
		return if (tag == TAG_INVALID || getType(tag) < 0) null else readInts(tag)
	}

	private fun longs(name: String): LongArray? {
		val tag = getTag(name)
		return if (tag == TAG_INVALID || getType(tag) < 0) null else readLongs(tag)
	}

	private fun floats(name: String): FloatArray? {
		val tag = getTag(name)
		return if (tag == TAG_INVALID || getType(tag) < 0) null else readFloats(tag)
	}

	private fun bytes(name: String): ByteArray? {
		val tag = getTag(name)
		return if (tag == TAG_INVALID || getType(tag) < 0) null else readBytes(tag)
	}

	private fun raw(tag: Int, nativeType: Int): Any? = when (nativeType) {
		TYPE_BYTE -> readBytes(tag)
		TYPE_INT32, TYPE_RATIONAL -> readInts(tag)
		TYPE_FLOAT -> readFloats(tag)
		TYPE_INT64 -> readLongs(tag)
		TYPE_DOUBLE -> readDoubles(tag)
		else -> null
	}

	private fun pair(tag: Int, nativeType: Int, index: Int): Pair<*, *>? {
		if (nativeType == TYPE_DOUBLE) {
			val v = readDoubles(tag)
			if (v == null || v.size < index * 2 + 2)
				return null
			return Pair<Double, Double>(v[index * 2], v[index * 2 + 1])
		}
		val v = readFloats(tag)
		if (v == null || v.size < index * 2 + 2)
			return null
		return Pair<Float, Float>(v[index * 2], v[index * 2 + 1])
	}

	private fun range(tag: Int, nativeType: Int, index: Int): Range<*>? {
		if (nativeType == TYPE_INT64) {
			val v = readLongs(tag)
			if (v == null || v.size < index * 2 + 2)
				return null
			return Range<Long>(v[index * 2], v[index * 2 + 1])
		}
		val v = readInts(tag)
		if (v == null || v.size < index * 2 + 2)
			return null
		return Range<Int>(v[index * 2], v[index * 2 + 1])
	}

	private fun cString(v: ByteArray): String {
		var end = 0
		while (end < v.size && v[end].toInt() != 0)
			end++
		return v.decodeToString(0, end)
	}
}
