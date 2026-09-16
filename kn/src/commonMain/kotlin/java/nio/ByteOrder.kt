@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.nio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value

class ByteOrder private constructor(private val label: String) {
    override fun toString(): String = label

    companion object {
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")

        private val NATIVE: ByteOrder = memScoped {
            val probe = alloc<IntVar>()
            probe.value = 1
            if (probe.ptr.reinterpret<ByteVar>()[0].toInt() == 1) LITTLE_ENDIAN else BIG_ENDIAN
        }

        fun nativeOrder(): ByteOrder = NATIVE
    }
}
