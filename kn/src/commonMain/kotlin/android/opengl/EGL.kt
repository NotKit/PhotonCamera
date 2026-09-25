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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import photoncam.gles.EGLConfigVar

/** The window's EGLDisplay, at file scope because `EGL14` and `EGL14.Companion`
 *  are two instances of [EGL14Api] and both have to answer the same one. */
private var sharedHostDisplay: COpaquePointer? = null

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
    val EGL_VENDOR: Int = 0x3053
    val EGL_VERSION: Int = 0x3054
    val EGL_EXTENSIONS: Int = 0x3055
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

    /** EGL_PLATFORM_SURFACELESS_MESA, spelled out: a plain int, so using it
     * links nothing. An EGL that does not know the platform answers
     * EGL_NO_DISPLAY, which the probe below treats as "no fallback". */
    private val platformSurfacelessMesa: UInt = 0x31DDu

    /**
     * Whether eglGetDisplay(EGL_DEFAULT_DISPLAY) may fall back to the
     * surfaceless platform. False unless the host opts in at startup: on the
     * phone the answer has to come from hybris, never from a rasteriser the
     * default display did not provide.
     */
    var allowSurfacelessFallback: Boolean = false

    /**
     * The window's own EGLDisplay, set by the host before the app starts.  It
     * is the LAST resort before the surfaceless platform and the one the phone
     * actually takes -- see [defaultDisplay].
     */
    var hostDisplay: COpaquePointer?
        get() = sharedHostDisplay
        set(value) { sharedHostDisplay = value }

    fun eglGetDisplay(displayId: Int): EGLDisplay =
        EGLDisplay(
            if (displayId == EGL_DEFAULT_DISPLAY) defaultDisplay()
            else photoncam.gles.eglGetDisplay(displayId.toLong().toCPointer<CPointed>())
        )

    /**
     * Android's default display, and what to use when this process cannot have
     * one.  In order:
     *
     *  1. THE WINDOW'S display, whenever the host set one.  It is the display
     *     the frames are drawn on, so the pipeline runs on the same GPU the
     *     viewfinder does, and hybris allows exactly one EGLDisplay per
     *     process anyway: with the compositor's display live, initialising a
     *     second one returns false and every later call answers
     *     EGL_NOT_INITIALIZED (0x3001), which reads as "config count zero"
     *     three frames further on.
     *
     *     IT MUST COME FIRST BECAUSE EGL_DEFAULT_DISPLAY IS NOT ALWAYS THE
     *     PHONE'S.  glvnd picks its vendor from the environment, and Lomiri
     *     runs every click app with DISPLAY=:0 pointing at a live Xwayland --
     *     so on the phone that call answers with Mesa, the probe below sees a
     *     perfectly good swrast display, and the 12 MP merge runs on llvmpipe
     *     and dies there (SIGILL) instead of on the Adreno.
     *  2. EGL_DEFAULT_DISPLAY itself, when it initialises and does pbuffers.
     *     That is the Android path, byte for byte, and a phone with no window
     *     open yet answers it.
     *  3. The surfaceless platform -- desktop Mesa only, and gated, so no
     *     software rasteriser can ever stand in for the Adreno.
     */
    private fun defaultDisplay(): COpaquePointer? {
        val host = sharedHostDisplay
        // NOT terminated: the window is drawing on it.
        if (host != null && pbufferConfigs(host, terminate = false) > 0) return host
        val raw = photoncam.gles.eglGetDisplay(EGL_DEFAULT_DISPLAY.toLong().toCPointer<CPointed>())
        if (raw != null && pbufferConfigs(raw, terminate = true) > 0) return raw
        if (allowSurfacelessFallback) return surfacelessDisplay() ?: raw
        return raw
    }

    /** Count-only pbuffer config query. -1 unless the display inits and answers.
     *  A display the window owns must not be terminated by the probe. */
    private fun pbufferConfigs(dpy: COpaquePointer?, terminate: Boolean): Int = memScoped {
        val major = alloc<IntVar>()
        val minor = alloc<IntVar>()
        if (photoncam.gles.eglInitialize(dpy, major.ptr, minor.ptr).toInt() == 0) return -1
        val probe = intArrayOf(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE)
        val n = IntArray(1)
        val ok = eglChooseConfig(EGLDisplay(dpy), probe, 0, emptyArray(), 0, 0, n, 0)
        val count = if (ok) n[0] else -1
        if (terminate) photoncam.gles.eglTerminate(dpy)
        count
    }

    private fun surfacelessDisplay(): COpaquePointer? {
        // EGL 1.5 core: present in every -lEGL this port links, and an
        // unknown platform answers EGL_NO_DISPLAY rather than failing.
        val dpy: COpaquePointer? =
            photoncam.gles.eglGetPlatformDisplay(platformSurfacelessMesa, null, null)
        if (dpy == null) return null
        return if (pbufferConfigs(dpy, terminate = true) > 0) dpy else null
    }

    fun eglInitialize(dpy: EGLDisplay?, major: IntArray, majorOffset: Int,
                      minor: IntArray, minorOffset: Int): Boolean = memScoped {
        val ma = alloc<IntVar>()
        val mi = alloc<IntVar>()
        val ok = photoncam.gles.eglInitialize(dpy?.handle, ma.ptr, mi.ptr)
        major[majorOffset] = ma.value
        minor[minorOffset] = mi.value
        ok.toInt() != 0
    }

    /** The window's display is never terminated here: GLContext.close() ends
     *  every processing context with an eglTerminate, and on the phone that is
     *  the display the compositor's surface is drawn on. */
    fun eglTerminate(dpy: EGLDisplay?): Boolean =
        if (dpy?.handle != null && dpy.handle == sharedHostDisplay) true
        else photoncam.gles.eglTerminate(dpy?.handle).toInt() != 0

    fun eglGetError(): Int = photoncam.gles.eglGetError()

    /** EGL14's own; the names a failing display answers are the whole
     *  diagnosis when eglChooseConfig comes back with nothing. */
    fun eglQueryString(dpy: EGLDisplay?, name: Int): String? =
        photoncam.gles.eglQueryString(dpy?.handle, name)?.toKString()

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

    /** Every context this shim makes is the pipeline's -- the window's is
     *  mgwl's and never comes through here -- so it asks to be the one that
     *  yields to the compositor.  PC_GL_PRIORITY=high|medium|low|off. */
    fun eglCreateContext(dpy: EGLDisplay?, config: EGLConfig?, share_context: EGLContext?,
                         attrib_list: IntArray, offset: Int): EGLContext {
        val attribs = withContextPriority(dpy, attrib_list, offset)
        return EGLContext(attribs.usePinned {
            photoncam.gles.eglCreateContext(dpy?.handle, config?.handle, share_context?.handle,
                it.addressOf(0))
        })
    }

    private fun withContextPriority(dpy: EGLDisplay?, attrib_list: IntArray, offset: Int): IntArray {
        // The caller's list from `offset` up to and including its EGL_NONE.
        val end = (offset until attrib_list.size).firstOrNull { attrib_list[it] == EGL_NONE }
            ?: return attrib_list.copyOfRange(offset, attrib_list.size)
        val own = attrib_list.copyOfRange(offset, end)
        val level = contextPriority ?: return own + EGL_NONE
        // An EGL that does not know the attribute fails the create outright
        // with EGL_BAD_ATTRIBUTE, so it is only ever appended where advertised.
        val ext = eglQueryString(dpy, EGL_EXTENSIONS).orEmpty()
        if (!ext.split(' ').contains("EGL_IMG_context_priority")) {
            println("[pc] GL: no EGL_IMG_context_priority, the pipeline runs at the default")
            return own + EGL_NONE
        }
        println("[pc] GL: pipeline context priority 0x${level.toString(16)}")
        return own + intArrayOf(EGL_CONTEXT_PRIORITY_LEVEL_IMG, level, EGL_NONE)
    }

    /** EGL_IMG_context_priority, spelled out: naming it links nothing. */
    private val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    private val EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102
    private val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103

    private val contextPriority: Int? by lazy {
        when (platform.posix.getenv("PC_GL_PRIORITY")?.toKString()) {
            "off" -> null
            "high" -> EGL_CONTEXT_PRIORITY_HIGH_IMG
            "medium" -> EGL_CONTEXT_PRIORITY_MEDIUM_IMG
            else -> EGL_CONTEXT_PRIORITY_LOW_IMG
        }
    }

    fun eglDestroyContext(dpy: EGLDisplay?, ctx: EGLContext?): Boolean =
        photoncam.gles.eglDestroyContext(dpy?.handle, ctx?.handle).toInt() != 0

    fun eglCreatePbufferSurface(dpy: EGLDisplay?, config: EGLConfig?,
                                attrib_list: IntArray, offset: Int): EGLSurface =
        EGLSurface(attrib_list.usePinned {
            photoncam.gles.eglCreatePbufferSurface(dpy?.handle, config?.handle, it.addressOf(offset))
        })

    fun eglGetConfigAttrib(dpy: EGLDisplay?, config: EGLConfig?, attribute: Int,
                           value: IntArray, offset: Int): Boolean =
        value.usePinned {
            photoncam.gles.eglGetConfigAttrib(dpy?.handle, config?.handle, attribute, it.addressOf(offset))
        }.toInt() != 0

    /* A window surface wants a native window, and an android.view.Surface on
     * this port has none behind it (its one source would be an encoder's
     * input, and MediaCodec.kt has no encoder).  EGL's answer to an unusable
     * window is EGL_NO_SURFACE. */
    fun eglCreateWindowSurface(dpy: EGLDisplay?, config: EGLConfig?, win: Any?,
                               attrib_list: IntArray, offset: Int): EGLSurface = EGL_NO_SURFACE

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

/* EGLExt.  eglPresentationTimeANDROID stamps frames on an encoder's input
 * surface, and no surface on this port feeds an encoder (see MediaCodec.kt),
 * so it reports failure as EGL does for a surface without a consumer. */
object EGLExt {
    const val EGL_OPENGL_ES3_BIT_KHR: Int = 0x0040
    const val EGL_RECORDABLE_ANDROID: Int = 0x3142

    fun eglPresentationTimeANDROID(dpy: EGLDisplay?, surface: EGLSurface?, time: Long): Boolean = false
}
