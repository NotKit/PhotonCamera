@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.opengl

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import photoncam.gles.EGLConfigVar

/** android.opengl's EGL handles: a wrapper round one EGL pointer, as on Android. */
abstract class EGLObjectHandle internal constructor(internal val handle: COpaquePointer?) {
    fun getNativeHandle(): Long = handle.toLong()
    override fun equals(other: Any?): Boolean =
        other is EGLObjectHandle && other::class == this::class && other.handle == handle
    override fun hashCode(): Int = handle.toLong().hashCode()
}

class EGLDisplay internal constructor(handle: COpaquePointer?) : EGLObjectHandle(handle)
class EGLConfig internal constructor(handle: COpaquePointer?) : EGLObjectHandle(handle)
class EGLContext internal constructor(handle: COpaquePointer?) : EGLObjectHandle(handle)
class EGLSurface internal constructor(handle: COpaquePointer?) : EGLObjectHandle(handle)

open class EGL14Api {
    val EGL_FALSE: Int = 0
    val EGL_TRUE: Int = 1
    val EGL_DEFAULT_DISPLAY: Int = 0
    val EGL_ALPHA_SIZE: Int = 0x3021
    val EGL_BIND_TO_TEXTURE_RGBA: Int = 0x303A
    val EGL_BLUE_SIZE: Int = 0x3022
    val EGL_CONFIG_CAVEAT: Int = 0x3027
    val EGL_CONFIG_ID: Int = 0x3028
    val EGL_CONTEXT_CLIENT_VERSION: Int = 0x3098
    val EGL_DEPTH_SIZE: Int = 0x3025
    val EGL_GREEN_SIZE: Int = 0x3023
    val EGL_HEIGHT: Int = 0x3056
    val EGL_NONE: Int = 0x3038
    val EGL_OPENGL_ES2_BIT: Int = 0x0004
    val EGL_OPENGL_ES3_BIT_KHR: Int = 0x0040
    val EGL_PBUFFER_BIT: Int = 0x0001
    val EGL_RED_SIZE: Int = 0x3024
    val EGL_RENDERABLE_TYPE: Int = 0x3040
    val EGL_SAMPLES: Int = 0x3031
    val EGL_SAMPLE_BUFFERS: Int = 0x3032
    val EGL_STENCIL_SIZE: Int = 0x3026
    val EGL_SURFACE_TYPE: Int = 0x3033
    val EGL_WIDTH: Int = 0x3057
    val EGL_WINDOW_BIT: Int = 0x0004

    val EGL_SUCCESS: Int = 0x3000
    val EGL_NOT_INITIALIZED: Int = 0x3001
    val EGL_BAD_ACCESS: Int = 0x3002
    val EGL_BAD_ALLOC: Int = 0x3003
    val EGL_BAD_ATTRIBUTE: Int = 0x3004
    val EGL_BAD_CONFIG: Int = 0x3005
    val EGL_BAD_CONTEXT: Int = 0x3006
    val EGL_BAD_CURRENT_SURFACE: Int = 0x3007
    val EGL_BAD_DISPLAY: Int = 0x3008
    val EGL_BAD_MATCH: Int = 0x3009
    val EGL_BAD_NATIVE_PIXMAP: Int = 0x300A
    val EGL_BAD_NATIVE_WINDOW: Int = 0x300B
    val EGL_BAD_PARAMETER: Int = 0x300C
    val EGL_BAD_SURFACE: Int = 0x300D
    val EGL_CONTEXT_LOST: Int = 0x300E

    val EGL_NO_DISPLAY: EGLDisplay = EGLDisplay(null)
    val EGL_NO_CONTEXT: EGLContext = EGLContext(null)
    val EGL_NO_SURFACE: EGLSurface = EGLSurface(null)

    fun eglGetDisplay(displayId: Int): EGLDisplay =
        EGLDisplay(photoncam.gles.eglGetDisplay(displayId.toLong().toCPointer<CPointed>()))

    fun eglInitialize(dpy: EGLDisplay?, major: IntArray, majorOffset: Int,
                      minor: IntArray, minorOffset: Int): Boolean = memScoped {
        val ma = alloc<IntVar>()
        val mi = alloc<IntVar>()
        val ok = photoncam.gles.eglInitialize(dpy?.handle, ma.ptr, mi.ptr)
        major[majorOffset] = ma.value
        minor[minorOffset] = mi.value
        ok.toInt() != 0
    }

    fun eglTerminate(dpy: EGLDisplay?): Boolean = photoncam.gles.eglTerminate(dpy?.handle).toInt() != 0

    fun eglGetError(): Int = photoncam.gles.eglGetError()

    /**
     * Android's form: the caller's EGLConfig[] is filled in, and num_config[0]
     * gets the count.  A null configs array is the "just count them" call.
     */
    fun eglChooseConfig(dpy: EGLDisplay?, attrib_list: IntArray, attribListOffset: Int,
                        configs: Array<EGLConfig>?, configsOffset: Int, config_size: Int,
                        num_config: IntArray, num_configOffset: Int): Boolean = memScoped {
        val slots = if (config_size > 0) allocArray<EGLConfigVar>(config_size) else null
        val count = alloc<IntVar>()
        val ok = attrib_list.usePinned {
            photoncam.gles.eglChooseConfig(dpy?.handle, it.addressOf(attribListOffset),
                slots, config_size, count.ptr)
        }
        num_config[num_configOffset] = count.value
        if (configs != null && slots != null) {
            for (i in 0 until minOf(config_size, count.value)) {
                configs[configsOffset + i] = EGLConfig(slots[i])
            }
        }
        ok.toInt() != 0
    }

    fun eglCreateContext(dpy: EGLDisplay?, config: EGLConfig?, share_context: EGLContext?,
                         attrib_list: IntArray, offset: Int): EGLContext =
        EGLContext(attrib_list.usePinned {
            photoncam.gles.eglCreateContext(dpy?.handle, config?.handle, share_context?.handle,
                it.addressOf(offset))
        })

    fun eglDestroyContext(dpy: EGLDisplay?, ctx: EGLContext?): Boolean =
        photoncam.gles.eglDestroyContext(dpy?.handle, ctx?.handle).toInt() != 0

    fun eglCreatePbufferSurface(dpy: EGLDisplay?, config: EGLConfig?,
                                attrib_list: IntArray, offset: Int): EGLSurface =
        EGLSurface(attrib_list.usePinned {
            photoncam.gles.eglCreatePbufferSurface(dpy?.handle, config?.handle, it.addressOf(offset))
        })

    fun eglDestroySurface(dpy: EGLDisplay?, surface: EGLSurface?): Boolean =
        photoncam.gles.eglDestroySurface(dpy?.handle, surface?.handle).toInt() != 0

    fun eglMakeCurrent(dpy: EGLDisplay?, draw: EGLSurface?, read: EGLSurface?, ctx: EGLContext?): Boolean =
        photoncam.gles.eglMakeCurrent(dpy?.handle, draw?.handle, read?.handle, ctx?.handle).toInt() != 0

    fun eglSwapBuffers(dpy: EGLDisplay?, surface: EGLSurface?): Boolean =
        photoncam.gles.eglSwapBuffers(dpy?.handle, surface?.handle).toInt() != 0

    fun eglQuerySurface(dpy: EGLDisplay?, surface: EGLSurface?, attribute: Int,
                        value: IntArray, offset: Int): Boolean =
        value.usePinned {
            photoncam.gles.eglQuerySurface(dpy?.handle, surface?.handle, attribute, it.addressOf(offset))
        }.toInt() != 0
}

object EGL14 : EGL14Api() {
    object Companion : EGL14Api()
}
