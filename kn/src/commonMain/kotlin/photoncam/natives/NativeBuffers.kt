@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import java.nio.Buffer
import java.nio.ByteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/*
 * The only place this lane touches java.nio, which the `gl` lane owns
 * (src/commonMain/kotlin/java/nio/Buffers.kt).
 *
 * Every buffer that crosses into the app's native code is a *direct* buffer:
 * allocator.cpp mallocs the memory and hands back its address, dngCreator and
 * ncnnMl read and write it in place.  So the contract needed is two things -
 * the address of a buffer, and a buffer over an address - and this file is the
 * whole of it.
 *
 * [nativeBase] is element zero, not `Buffer.pointer()`, which is the current
 * position: JNI's GetDirectBufferAddress is position-independent and the C
 * indexes from the start, so anything else would silently shift a buffer that
 * was handed over unrewound.
 */

internal fun Buffer?.nativeBase(): CPointer<ByteVar>? =
    if (this == null) null else (mem.ptr + byteOffset)

internal fun Buffer?.nativeCapacity(): Long = this?.capacity()?.toLong() ?: 0L

internal fun Buffer?.nativeFloats(): CPointer<FloatVar>? = this.nativeBase()?.reinterpret()

/** Wrap `capacity` bytes of native memory at [ptr]; null when the call failed. */
internal fun wrapNative(ptr: COpaquePointer?, capacity: Int): ByteBuffer? =
    if (ptr == null) null else ByteBuffer.wrapPointer(ptr, capacity)

/*
 * Array pinning.  A Java array argument becomes a pointer plus a count; an
 * absent or empty array becomes a null pointer, which every wrapper treats the
 * way the JNI code treated a null jarray.
 */

internal inline fun <R> DoubleArray?.pinned(block: (CPointer<DoubleVar>?, Int) -> R): R =
    if (this == null || isEmpty()) block(null, 0)
    else usePinned { p: Pinned<DoubleArray> -> block(p.addressOf(0), size) }

internal inline fun <R> FloatArray?.pinned(block: (CPointer<FloatVar>?, Int) -> R): R =
    if (this == null || isEmpty()) block(null, 0)
    else usePinned { p: Pinned<FloatArray> -> block(p.addressOf(0), size) }

internal inline fun <R> ShortArray?.pinned(block: (CPointer<ShortVar>?, Int) -> R): R =
    if (this == null || isEmpty()) block(null, 0)
    else usePinned { p: Pinned<ShortArray> -> block(p.addressOf(0), size) }

internal inline fun <R> ByteArray?.pinned(block: (CPointer<ByteVar>?, Int) -> R): R =
    if (this == null || isEmpty()) block(null, 0)
    else usePinned { p: Pinned<ByteArray> -> block(p.addressOf(0), size) }
