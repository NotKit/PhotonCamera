@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.nio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * The bytes behind a buffer family.  Every view taken from a ByteBuffer
 * (asFloatBuffer, slice, duplicate) shares one of these, as the views of a
 * direct java.nio buffer share one allocation.  [owned] is false for memory
 * that belongs to someone else (a glMapBufferRange mapping, a native malloc).
 */
class NioMem private constructor(val ptr: CPointer<ByteVar>, val size: Int, private val owned: Boolean) {
    fun free() {
        if (owned) nativeHeap.free(ptr)
    }

    companion object {
        fun allocate(size: Int): NioMem {
            val p = nativeHeap.allocArray<ByteVar>(if (size > 0) size else 1)
            for (i in 0 until size) p[i] = 0
            return NioMem(p, size, true)
        }

        fun wrapping(ptr: COpaquePointer, size: Int): NioMem =
            NioMem(ptr.reinterpret(), size, false)
    }
}

abstract class Buffer internal constructor(
    internal val mem: NioMem,
    internal val byteOffset: Int,
    private val cap: Int,
    private val direct: Boolean
) {
    internal var pos: Int = 0
    internal var lim: Int = cap
    internal var markPos: Int = -1
    internal var bo: ByteOrder = ByteOrder.BIG_ENDIAN

    internal abstract val elemSize: Int

    /** Byte index, inside [mem], of element [index] of this buffer. */
    internal fun at(index: Int): Int = byteOffset + index * elemSize

    /** The address of the current position: what a GL entry point is handed. */
    fun pointer(): COpaquePointer = (mem.ptr + at(pos))!!

    fun capacity(): Int = cap
    fun position(): Int = pos
    fun limit(): Int = lim
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun isDirect(): Boolean = direct

    open fun position(newPosition: Int): Buffer {
        require(newPosition in 0..lim) { "position $newPosition out of 0..$lim" }
        pos = newPosition
        if (markPos > pos) markPos = -1
        return this
    }

    open fun limit(newLimit: Int): Buffer {
        require(newLimit in 0..cap) { "limit $newLimit out of 0..$cap" }
        lim = newLimit
        if (pos > lim) pos = lim
        if (markPos > lim) markPos = -1
        return this
    }

    open fun mark(): Buffer {
        markPos = pos
        return this
    }

    open fun reset(): Buffer {
        check(markPos >= 0) { "mark not set" }
        pos = markPos
        return this
    }

    open fun clear(): Buffer {
        pos = 0; lim = cap; markPos = -1
        return this
    }

    open fun flip(): Buffer {
        lim = pos; pos = 0; markPos = -1
        return this
    }

    open fun rewind(): Buffer {
        pos = 0; markPos = -1
        return this
    }

    /**
     * Java's heap buffers expose their backing array; ours always live in
     * native memory, so array() hands back a copy of the whole capacity.
     * Every caller here reads it once after filling the buffer.
     */
    open fun hasArray(): Boolean = !direct
    open fun arrayOffset(): Int = 0

    /** Releases the native memory.  Not java.nio, but nothing else can free it. */
    fun free() = mem.free()

    internal fun nextIndex(n: Int): Int {
        check(pos + n <= lim) { "buffer underflow: $pos + $n > $lim" }
        val p = pos
        pos += n
        return p
    }

    internal fun checkIndex(index: Int) {
        require(index in 0 until lim) { "index $index out of 0..$lim" }
    }

    internal val nativeOrder: Boolean get() = bo === ByteOrder.nativeOrder()
}

// ---------------------------------------------------------------- raw access

internal fun NioMem.u8(i: Int): Int = ptr[i].toInt() and 0xFF

internal fun NioMem.getShortAt(i: Int, little: Boolean): Short {
    val a = u8(i); val b = u8(i + 1)
    return (if (little) (b shl 8) or a else (a shl 8) or b).toShort()
}

internal fun NioMem.putShortAt(i: Int, v: Short, little: Boolean) {
    val x = v.toInt()
    if (little) {
        ptr[i] = (x and 0xFF).toByte(); ptr[i + 1] = ((x shr 8) and 0xFF).toByte()
    } else {
        ptr[i] = ((x shr 8) and 0xFF).toByte(); ptr[i + 1] = (x and 0xFF).toByte()
    }
}

internal fun NioMem.getIntAt(i: Int, little: Boolean): Int {
    val a = u8(i); val b = u8(i + 1); val c = u8(i + 2); val d = u8(i + 3)
    return if (little) (d shl 24) or (c shl 16) or (b shl 8) or a
    else (a shl 24) or (b shl 16) or (c shl 8) or d
}

internal fun NioMem.putIntAt(i: Int, v: Int, little: Boolean) {
    if (little) {
        ptr[i] = (v and 0xFF).toByte(); ptr[i + 1] = ((v shr 8) and 0xFF).toByte()
        ptr[i + 2] = ((v shr 16) and 0xFF).toByte(); ptr[i + 3] = ((v shr 24) and 0xFF).toByte()
    } else {
        ptr[i] = ((v shr 24) and 0xFF).toByte(); ptr[i + 1] = ((v shr 16) and 0xFF).toByte()
        ptr[i + 2] = ((v shr 8) and 0xFF).toByte(); ptr[i + 3] = (v and 0xFF).toByte()
    }
}

internal fun NioMem.getLongAt(i: Int, little: Boolean): Long {
    val hi: Int; val lo: Int
    if (little) { lo = getIntAt(i, true); hi = getIntAt(i + 4, true) }
    else { hi = getIntAt(i, false); lo = getIntAt(i + 4, false) }
    return (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
}

internal fun NioMem.putLongAt(i: Int, v: Long, little: Boolean) {
    val hi = (v ushr 32).toInt(); val lo = v.toInt()
    if (little) { putIntAt(i, lo, true); putIntAt(i + 4, hi, true) }
    else { putIntAt(i, hi, false); putIntAt(i + 4, lo, false) }
}

private fun copyIn(mem: NioMem, byteIdx: Int, src: COpaquePointer, bytes: Int) {
    if (bytes > 0) memcpy(mem.ptr + byteIdx, src, bytes.convert())
}

private fun copyOut(mem: NioMem, byteIdx: Int, dst: COpaquePointer, bytes: Int) {
    if (bytes > 0) memcpy(dst, mem.ptr + byteIdx, bytes.convert())
}

// -------------------------------------------------------------- ByteBuffer

class ByteBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 1

    fun order(): ByteOrder = bo

    fun order(order: ByteOrder): ByteBuffer {
        bo = order
        return this
    }

    fun get(): Byte = mem.ptr[at(nextIndex(1))]

    fun get(index: Int): Byte {
        checkIndex(index); return mem.ptr[at(index)]
    }

    fun get(dst: ByteArray): ByteBuffer = get(dst, 0, dst.size)

    fun get(dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        val p = nextIndex(length)
        if (length > 0) dst.usePinned { copyOut(mem, at(p), it.addressOf(offset), length) }
        return this
    }

    fun put(b: Byte): ByteBuffer {
        mem.ptr[at(nextIndex(1))] = b
        return this
    }

    fun put(index: Int, b: Byte): ByteBuffer {
        checkIndex(index); mem.ptr[at(index)] = b
        return this
    }

    fun put(src: ByteArray): ByteBuffer = put(src, 0, src.size)

    fun put(src: ByteArray, offset: Int, length: Int): ByteBuffer {
        val p = nextIndex(length)
        if (length > 0) src.usePinned { copyIn(mem, at(p), it.addressOf(offset), length) }
        return this
    }

    fun put(src: ByteBuffer): ByteBuffer {
        val n = src.remaining()
        val p = nextIndex(n)
        if (n > 0) copyIn(mem, at(p), src.pointer(), n)
        src.pos += n
        return this
    }

    fun getShort(): Short = mem.getShortAt(at(nextIndex(2)), !isBig)
    fun getShort(index: Int): Short = mem.getShortAt(at(index), !isBig)
    fun putShort(v: Short): ByteBuffer { mem.putShortAt(at(nextIndex(2)), v, !isBig); return this }
    fun putShort(index: Int, v: Short): ByteBuffer { mem.putShortAt(at(index), v, !isBig); return this }

    fun getChar(): Char = getShort().toInt().toChar()
    fun putChar(v: Char): ByteBuffer = putShort(v.code.toShort())

    fun getInt(): Int = mem.getIntAt(at(nextIndex(4)), !isBig)
    fun getInt(index: Int): Int = mem.getIntAt(at(index), !isBig)
    fun putInt(v: Int): ByteBuffer { mem.putIntAt(at(nextIndex(4)), v, !isBig); return this }
    fun putInt(index: Int, v: Int): ByteBuffer { mem.putIntAt(at(index), v, !isBig); return this }

    fun getLong(): Long = mem.getLongAt(at(nextIndex(8)), !isBig)
    fun putLong(v: Long): ByteBuffer { mem.putLongAt(at(nextIndex(8)), v, !isBig); return this }

    fun getFloat(): Float = Float.fromBits(getInt())
    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))
    fun putFloat(v: Float): ByteBuffer = putInt(v.toRawBits())

    fun getDouble(): Double = Double.fromBits(getLong())
    fun putDouble(v: Double): ByteBuffer = putLong(v.toRawBits())

    fun asFloatBuffer(): FloatBuffer =
        FloatBuffer(mem, at(pos), remaining() / 4, isDirect()).also { it.bo = bo }

    fun asIntBuffer(): IntBuffer =
        IntBuffer(mem, at(pos), remaining() / 4, isDirect()).also { it.bo = bo }

    fun asShortBuffer(): ShortBuffer =
        ShortBuffer(mem, at(pos), remaining() / 2, isDirect()).also { it.bo = bo }

    fun asLongBuffer(): LongBuffer =
        LongBuffer(mem, at(pos), remaining() / 8, isDirect()).also { it.bo = bo }

    fun asCharBuffer(): CharBuffer =
        CharBuffer(mem, at(pos), remaining() / 2, isDirect()).also { it.bo = bo }

    fun slice(): ByteBuffer =
        ByteBuffer(mem, at(pos), remaining(), isDirect()).also { it.bo = bo }

    fun duplicate(): ByteBuffer =
        ByteBuffer(mem, byteOffset, capacity(), isDirect()).also {
            it.bo = bo; it.pos = pos; it.lim = lim; it.markPos = markPos
        }

    fun array(): ByteArray {
        val out = ByteArray(capacity())
        if (out.isNotEmpty()) out.usePinned { copyOut(mem, byteOffset, it.addressOf(0), out.size) }
        return out
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): ByteBuffer =
            ByteBuffer(NioMem.allocate(capacity), 0, capacity, false)

        fun allocateDirect(capacity: Int): ByteBuffer =
            ByteBuffer(NioMem.allocate(capacity), 0, capacity, true)

        fun wrap(array: ByteArray): ByteBuffer = wrap(array, 0, array.size)

        fun wrap(array: ByteArray, offset: Int, length: Int): ByteBuffer {
            val b = allocate(length)
            if (length > 0) array.usePinned { copyIn(b.mem, 0, it.addressOf(offset), length) }
            return b
        }

        /** A buffer over memory this one does not own (a GL mapping, malloc). */
        fun wrapPointer(ptr: COpaquePointer, size: Int): ByteBuffer =
            ByteBuffer(NioMem.wrapping(ptr, size), 0, size, true)
    }
}

// --------------------------------------------------------- typed views

class FloatBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 4

    fun get(): Float = Float.fromBits(mem.getIntAt(at(nextIndex(1)), !isBig))
    fun get(index: Int): Float { checkIndex(index); return Float.fromBits(mem.getIntAt(at(index), !isBig)) }
    fun put(v: Float): FloatBuffer { mem.putIntAt(at(nextIndex(1)), v.toRawBits(), !isBig); return this }
    fun put(index: Int, v: Float): FloatBuffer { checkIndex(index); mem.putIntAt(at(index), v.toRawBits(), !isBig); return this }

    fun get(dst: FloatArray): FloatBuffer = get(dst, 0, dst.size)

    fun get(dst: FloatArray, offset: Int, length: Int): FloatBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) dst.usePinned { copyOut(mem, at(p), it.addressOf(offset), length * 4) }
        else for (i in 0 until length) dst[offset + i] = Float.fromBits(mem.getIntAt(at(p + i), !isBig))
        return this
    }

    fun put(src: FloatArray): FloatBuffer = put(src, 0, src.size)

    fun put(src: FloatArray, offset: Int, length: Int): FloatBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) src.usePinned { copyIn(mem, at(p), it.addressOf(offset), length * 4) }
        else for (i in 0 until length) mem.putIntAt(at(p + i), src[offset + i].toRawBits(), !isBig)
        return this
    }

    fun slice(): FloatBuffer = FloatBuffer(mem, at(pos), remaining(), isDirect()).also { it.bo = bo }

    fun duplicate(): FloatBuffer = FloatBuffer(mem, byteOffset, capacity(), isDirect()).also {
        it.bo = bo; it.pos = pos; it.lim = lim; it.markPos = markPos
    }

    fun array(): FloatArray {
        val out = FloatArray(capacity())
        val save = pos; pos = 0
        get(out, 0, out.size)
        pos = save
        return out
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): FloatBuffer =
            FloatBuffer(NioMem.allocate(capacity * 4), 0, capacity, false)

        /**
         * Java's wrap aliases the array; ours copies it into native memory, so
         * the buffer can be handed straight to a GL upload.  Every caller here
         * only reads the buffer afterwards.
         */
        fun wrap(array: FloatArray): FloatBuffer {
            val b = FloatBuffer(NioMem.allocate(array.size * 4), 0, array.size, true)
            b.bo = ByteOrder.nativeOrder()
            b.put(array, 0, array.size)
            b.pos = 0
            return b
        }
    }
}

class IntBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 4

    fun get(): Int = mem.getIntAt(at(nextIndex(1)), !isBig)
    fun get(index: Int): Int { checkIndex(index); return mem.getIntAt(at(index), !isBig) }
    fun put(v: Int): IntBuffer { mem.putIntAt(at(nextIndex(1)), v, !isBig); return this }
    fun put(index: Int, v: Int): IntBuffer { checkIndex(index); mem.putIntAt(at(index), v, !isBig); return this }

    fun get(dst: IntArray): IntBuffer = get(dst, 0, dst.size)

    fun get(dst: IntArray, offset: Int, length: Int): IntBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) dst.usePinned { copyOut(mem, at(p), it.addressOf(offset), length * 4) }
        else for (i in 0 until length) dst[offset + i] = mem.getIntAt(at(p + i), !isBig)
        return this
    }

    fun put(src: IntArray): IntBuffer = put(src, 0, src.size)

    fun put(src: IntArray, offset: Int, length: Int): IntBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) src.usePinned { copyIn(mem, at(p), it.addressOf(offset), length * 4) }
        else for (i in 0 until length) mem.putIntAt(at(p + i), src[offset + i], !isBig)
        return this
    }

    fun slice(): IntBuffer = IntBuffer(mem, at(pos), remaining(), isDirect()).also { it.bo = bo }

    fun duplicate(): IntBuffer = IntBuffer(mem, byteOffset, capacity(), isDirect()).also {
        it.bo = bo; it.pos = pos; it.lim = lim; it.markPos = markPos
    }

    fun array(): IntArray {
        val out = IntArray(capacity())
        val save = pos; pos = 0
        get(out, 0, out.size)
        pos = save
        return out
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): IntBuffer =
            IntBuffer(NioMem.allocate(capacity * 4), 0, capacity, false)

        fun wrap(array: IntArray): IntBuffer {
            val b = IntBuffer(NioMem.allocate(array.size * 4), 0, array.size, true)
            b.bo = ByteOrder.nativeOrder()
            b.put(array, 0, array.size)
            b.pos = 0
            return b
        }
    }
}

class ShortBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 2

    fun get(): Short = mem.getShortAt(at(nextIndex(1)), !isBig)
    fun get(index: Int): Short { checkIndex(index); return mem.getShortAt(at(index), !isBig) }
    fun put(v: Short): ShortBuffer { mem.putShortAt(at(nextIndex(1)), v, !isBig); return this }
    fun put(index: Int, v: Short): ShortBuffer { checkIndex(index); mem.putShortAt(at(index), v, !isBig); return this }

    fun get(dst: ShortArray): ShortBuffer = get(dst, 0, dst.size)

    fun get(dst: ShortArray, offset: Int, length: Int): ShortBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) dst.usePinned { copyOut(mem, at(p), it.addressOf(offset), length * 2) }
        else for (i in 0 until length) dst[offset + i] = mem.getShortAt(at(p + i), !isBig)
        return this
    }

    fun put(src: ShortArray): ShortBuffer = put(src, 0, src.size)

    fun put(src: ShortArray, offset: Int, length: Int): ShortBuffer {
        val p = nextIndex(length)
        if (length == 0) return this
        if (nativeOrder) src.usePinned { copyIn(mem, at(p), it.addressOf(offset), length * 2) }
        else for (i in 0 until length) mem.putShortAt(at(p + i), src[offset + i], !isBig)
        return this
    }

    fun slice(): ShortBuffer = ShortBuffer(mem, at(pos), remaining(), isDirect()).also { it.bo = bo }

    fun duplicate(): ShortBuffer = ShortBuffer(mem, byteOffset, capacity(), isDirect()).also {
        it.bo = bo; it.pos = pos; it.lim = lim; it.markPos = markPos
    }

    fun array(): ShortArray {
        val out = ShortArray(capacity())
        val save = pos; pos = 0
        get(out, 0, out.size)
        pos = save
        return out
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): ShortBuffer =
            ShortBuffer(NioMem.allocate(capacity * 2), 0, capacity, false)

        fun wrap(array: ShortArray): ShortBuffer {
            val b = ShortBuffer(NioMem.allocate(array.size * 2), 0, array.size, true)
            b.bo = ByteOrder.nativeOrder()
            b.put(array, 0, array.size)
            b.pos = 0
            return b
        }
    }
}

class LongBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 8

    fun get(): Long = mem.getLongAt(at(nextIndex(1)), !isBig)
    fun get(index: Int): Long { checkIndex(index); return mem.getLongAt(at(index), !isBig) }
    fun put(v: Long): LongBuffer { mem.putLongAt(at(nextIndex(1)), v, !isBig); return this }
    fun put(index: Int, v: Long): LongBuffer { checkIndex(index); mem.putLongAt(at(index), v, !isBig); return this }

    fun get(dst: LongArray): LongBuffer {
        for (i in dst.indices) dst[i] = get()
        return this
    }

    fun put(src: LongArray): LongBuffer {
        for (v in src) put(v)
        return this
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): LongBuffer =
            LongBuffer(NioMem.allocate(capacity * 8), 0, capacity, false)
    }
}

class DoubleBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 8

    fun get(): Double = Double.fromBits(mem.getLongAt(at(nextIndex(1)), !isBig))
    fun put(v: Double): DoubleBuffer { mem.putLongAt(at(nextIndex(1)), v.toRawBits(), !isBig); return this }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): DoubleBuffer =
            DoubleBuffer(NioMem.allocate(capacity * 8), 0, capacity, false)
    }
}

class CharBuffer internal constructor(mem: NioMem, byteOffset: Int, cap: Int, direct: Boolean) :
    Buffer(mem, byteOffset, cap, direct) {

    override val elemSize: Int get() = 2

    fun get(): Char = mem.getShortAt(at(nextIndex(1)), !isBig).toInt().toChar()
    fun get(index: Int): Char { checkIndex(index); return mem.getShortAt(at(index), !isBig).toInt().toChar() }
    fun put(v: Char): CharBuffer { mem.putShortAt(at(nextIndex(1)), v.code.toShort(), !isBig); return this }

    override fun toString(): String {
        val sb = StringBuilder(remaining())
        for (i in pos until lim) sb.append(mem.getShortAt(at(i), !isBig).toInt().toChar())
        return sb.toString()
    }

    private val isBig: Boolean get() = bo === ByteOrder.BIG_ENDIAN

    companion object {
        fun allocate(capacity: Int): CharBuffer =
            CharBuffer(NioMem.allocate(capacity * 2), 0, capacity, false)
    }
}
