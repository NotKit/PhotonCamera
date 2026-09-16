@file:OptIn(ExperimentalForeignApi::class)

package photoncam.camera

import kotlinx.cinterop.*
import cnames.structs.atl_camera_metadata
import photoncam.atlcamera.*

/**
 * The `native_*` half of android.hardware.camera2.impl.CameraMetadataNative,
 * over the cinterop bindings of atl-touch's camera2_metadata.c.  Widths and
 * widening follow the JNI original: HAL enums are bytes and Java keys are ints,
 * so the int readers widen bytes, and a rational reads as its pairs.
 */
object MetadataNatives {
	const val TYPE_BYTE = 0
	const val TYPE_INT32 = 1
	const val TYPE_FLOAT = 2
	const val TYPE_INT64 = 3
	const val TYPE_DOUBLE = 4
	const val TYPE_RATIONAL = 5
	const val TAG_INVALID = -1

	private fun bag(ptr: Long): CPointer<atl_camera_metadata>? =
		ptr.toCPointer()

	private fun entry(ptr: Long, tag: Int) =
		atl_camera_metadata_find(bag(ptr), tag.toUInt())?.pointed

	/** null when no backend serves camera2 at all. */
	fun getCameraIdList(): Array<String>? = memScoped {
		val backend = atl_camera_backend_get()?.pointed ?: return null
		val list = backend.get_camera2_id_list ?: return null
		val count = alloc<IntVar>()
		val ids = list.invoke(count.ptr) ?: return arrayOf()
		Array(count.value) { ids[it]?.toKString() ?: "" }
	}

	fun getStaticMetadata(cameraId: String?): Long {
		if (cameraId == null)
			return 0L
		val get = atl_camera_backend_get()?.pointed?.get_static_metadata ?: return 0L
		return memScoped { get.invoke(cameraId.cstr.ptr).rawValue.toLong() }
	}

	fun getAvailableKeys(cameraId: String?, which: Int): IntArray? = memScoped {
		if (cameraId == null)
			return null
		val get = atl_camera_backend_get()?.pointed?.get_available_keys ?: return null
		val count = alloc<IntVar>()
		val keys = get.invoke(cameraId.cstr.ptr, which, count.ptr) ?: return null
		IntArray(count.value) { keys[it].toInt() }
	}

	fun create(): Long = atl_camera_metadata_new().rawValue.toLong()

	fun copy(ptr: Long): Long = atl_camera_metadata_copy(bag(ptr)).rawValue.toLong()

	fun free(ptr: Long) = atl_camera_metadata_free(bag(ptr))

	fun erase(ptr: Long, tag: Int) = atl_camera_metadata_remove(bag(ptr), tag.toUInt())

	fun getTags(ptr: Long): IntArray {
		val md = bag(ptr)
		val n = atl_camera_metadata_n_entries(md)
		return IntArray(n) { atl_camera_metadata_entry_at(md, it)!!.pointed.tag.toInt() }
	}

	fun getTag(ptr: Long, name: String?): Int {
		if (name == null)
			return TAG_INVALID
		return atl_camera_metadata_tag_from_name(bag(ptr), name).toInt()
	}

	fun getTagName(ptr: Long, tag: Int): String? =
		atl_camera_metadata_tag_name(bag(ptr), tag.toUInt())?.toKString()

	/** TYPE_*, or -1 when the tag is not present. */
	fun getType(ptr: Long, tag: Int): Int = entry(ptr, tag)?.type ?: -1

	fun readBytes(ptr: Long, tag: Int): ByteArray? {
		val e = entry(ptr, tag) ?: return null
		if (e.type != TYPE_BYTE)
			return null
		val data = e.data?.reinterpret<ByteVar>() ?: return null
		return ByteArray(e.count) { data[it] }
	}

	fun readInts(ptr: Long, tag: Int): IntArray? {
		val e = entry(ptr, tag) ?: return null
		return when (e.type) {
			TYPE_BYTE -> {
				val data = e.data?.reinterpret<UByteVar>() ?: return null
				IntArray(e.count) { data[it].toInt() }
			}
			TYPE_INT32 -> {
				val data = e.data?.reinterpret<IntVar>() ?: return null
				IntArray(e.count) { data[it] }
			}
			/* a rational entry counts pairs, not values */
			TYPE_RATIONAL -> {
				val data = e.data?.reinterpret<IntVar>() ?: return null
				IntArray(e.count * 2) { data[it] }
			}
			else -> null
		}
	}

	fun readFloats(ptr: Long, tag: Int): FloatArray? {
		val e = entry(ptr, tag) ?: return null
		if (e.type != TYPE_FLOAT)
			return null
		val data = e.data?.reinterpret<FloatVar>() ?: return null
		return FloatArray(e.count) { data[it] }
	}

	/** int32 and byte entries widen, so a Range<Long> key works at either width. */
	fun readLongs(ptr: Long, tag: Int): LongArray? {
		val e = entry(ptr, tag) ?: return null
		return when (e.type) {
			TYPE_INT64 -> {
				val data = e.data?.reinterpret<LongVar>() ?: return null
				LongArray(e.count) { data[it] }
			}
			TYPE_INT32 -> {
				val data = e.data?.reinterpret<IntVar>() ?: return null
				LongArray(e.count) { data[it].toLong() }
			}
			TYPE_BYTE -> {
				val data = e.data?.reinterpret<UByteVar>() ?: return null
				LongArray(e.count) { data[it].toLong() }
			}
			else -> null
		}
	}

	fun readDoubles(ptr: Long, tag: Int): DoubleArray? {
		val e = entry(ptr, tag) ?: return null
		if (e.type != TYPE_DOUBLE)
			return null
		val data = e.data?.reinterpret<DoubleVar>() ?: return null
		return DoubleArray(e.count) { data[it] }
	}

	/* The write side: a value arrives as the widest shape the Java layer has
	 * (LongArray for anything integral, DoubleArray for anything fractional)
	 * and is narrowed here to the type the tag is declared with. */

	private fun writeType(ptr: Long, tag: Int, fallback: Int): Int {
		val existing = entry(ptr, tag)
		if (existing != null)
			return existing.type
		var type = atl_camera2_tag_type(tag.toUInt())
		if (type < 0)
			type = atl_camera2_vendor_type(tag.toUInt())
		return if (type >= 0) type else fallback
	}

	fun writeLongs(ptr: Long, tag: Int, values: LongArray?) {
		val count = values?.size ?: 0
		if (count == 0)
			return
		val type = writeType(ptr, tag, TYPE_INT32)
		memScoped {
			val out = allocArray<ByteVar>(count * 8L)
			when (type) {
				TYPE_BYTE -> {
					val a = out.reinterpret<UByteVar>()
					for (i in 0 until count) a[i] = values!![i].toUByte()
				}
				TYPE_INT64 -> {
					val a = out.reinterpret<LongVar>()
					for (i in 0 until count) a[i] = values!![i]
				}
				TYPE_FLOAT -> {
					val a = out.reinterpret<FloatVar>()
					for (i in 0 until count) a[i] = values!![i].toFloat()
				}
				TYPE_DOUBLE -> {
					val a = out.reinterpret<DoubleVar>()
					for (i in 0 until count) a[i] = values!![i].toDouble()
				}
				else -> {
					val a = out.reinterpret<IntVar>()
					for (i in 0 until count) a[i] = values!![i].toInt()
				}
			}
			atl_camera_metadata_add(bag(ptr), tag.toUInt(), type, out,
				if (type == TYPE_RATIONAL) count / 2 else count)
		}
	}

	fun writeDoubles(ptr: Long, tag: Int, values: DoubleArray?) {
		val count = values?.size ?: 0
		if (count == 0)
			return
		val type = writeType(ptr, tag, TYPE_FLOAT)
		memScoped {
			val out = allocArray<ByteVar>(count * 8L)
			when (type) {
				TYPE_BYTE -> {
					val a = out.reinterpret<UByteVar>()
					for (i in 0 until count) a[i] = values!![i].toInt().toUByte()
				}
				TYPE_INT32, TYPE_RATIONAL -> {
					val a = out.reinterpret<IntVar>()
					for (i in 0 until count) a[i] = values!![i].toInt()
				}
				TYPE_INT64 -> {
					val a = out.reinterpret<LongVar>()
					for (i in 0 until count) a[i] = values!![i].toLong()
				}
				TYPE_DOUBLE -> {
					val a = out.reinterpret<DoubleVar>()
					for (i in 0 until count) a[i] = values!![i]
				}
				else -> {
					val a = out.reinterpret<FloatVar>()
					for (i in 0 until count) a[i] = values!![i].toFloat()
				}
			}
			atl_camera_metadata_add(bag(ptr), tag.toUInt(), type, out,
				if (type == TYPE_RATIONAL) count / 2 else count)
		}
	}

	fun writeBytes(ptr: Long, tag: Int, values: ByteArray?) {
		val count = values?.size ?: 0
		if (count == 0)
			return
		memScoped {
			val out = allocArray<ByteVar>(count)
			for (i in 0 until count) out[i] = values!![i]
			atl_camera_metadata_add(bag(ptr), tag.toUInt(), TYPE_BYTE, out, count)
		}
	}
}
