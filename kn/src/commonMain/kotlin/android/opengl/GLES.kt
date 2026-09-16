@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.opengl

import java.nio.Buffer
import java.nio.ByteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

// android.opengl.GLES20/30/31 over the system libGLESv2 (cinterop/gles.def).
//
// Shape: the members live in an open Api class and the GLESnn *object* extends
// it, because j2k rewrites `import static android.opengl.GLES20.GL_X` to
// `import android.opengl.GLES20.Companion.GL_X` and `import static ...glX` to
// `import android.opengl.GLES20.glX`.  An object cannot have a companion, so
// GLESnn carries a nested `object Companion` over the same Api class; both
// spellings then resolve, as do the qualified `GLES20.glX(...)` calls.
//
// Android's entry points take int where GL takes GLenum/GLuint and take
// (array, offset) pairs where GL takes a pointer; the conversions are here.

private fun gb(b: Boolean): UByte = if (b) 1u else 0u

open class GLES20Api {
// ---- gles20
    val GL_ES_VERSION_2_0: Int = 0x1
    val GL_DEPTH_BUFFER_BIT: Int = 0x100
    val GL_STENCIL_BUFFER_BIT: Int = 0x400
    val GL_COLOR_BUFFER_BIT: Int = 0x4000
    val GL_FALSE: Int = 0x0
    val GL_TRUE: Int = 0x1
    val GL_POINTS: Int = 0x0
    val GL_LINES: Int = 0x1
    val GL_LINE_LOOP: Int = 0x2
    val GL_LINE_STRIP: Int = 0x3
    val GL_TRIANGLES: Int = 0x4
    val GL_TRIANGLE_STRIP: Int = 0x5
    val GL_TRIANGLE_FAN: Int = 0x6
    val GL_ZERO: Int = 0x0
    val GL_ONE: Int = 0x1
    val GL_SRC_COLOR: Int = 0x300
    val GL_ONE_MINUS_SRC_COLOR: Int = 0x301
    val GL_SRC_ALPHA: Int = 0x302
    val GL_ONE_MINUS_SRC_ALPHA: Int = 0x303
    val GL_DST_ALPHA: Int = 0x304
    val GL_ONE_MINUS_DST_ALPHA: Int = 0x305
    val GL_DST_COLOR: Int = 0x306
    val GL_ONE_MINUS_DST_COLOR: Int = 0x307
    val GL_FUNC_ADD: Int = 0x8006
    val GL_BLEND: Int = 0xbe2
    val GL_ARRAY_BUFFER: Int = 0x8892
    val GL_ELEMENT_ARRAY_BUFFER: Int = 0x8893
    val GL_STREAM_DRAW: Int = 0x88e0
    val GL_STATIC_DRAW: Int = 0x88e4
    val GL_DYNAMIC_DRAW: Int = 0x88e8
    val GL_BUFFER_SIZE: Int = 0x8764
    val GL_BUFFER_USAGE: Int = 0x8765
    val GL_CULL_FACE: Int = 0xb44
    val GL_FRONT: Int = 0x404
    val GL_BACK: Int = 0x405
    val GL_FRONT_AND_BACK: Int = 0x408
    val GL_TEXTURE_2D: Int = 0xde1
    val GL_DEPTH_TEST: Int = 0xb71
    val GL_STENCIL_TEST: Int = 0xb90
    val GL_DITHER: Int = 0xbd0
    val GL_SCISSOR_TEST: Int = 0xc11
    val GL_NO_ERROR: Int = 0x0
    val GL_INVALID_ENUM: Int = 0x500
    val GL_INVALID_VALUE: Int = 0x501
    val GL_INVALID_OPERATION: Int = 0x502
    val GL_OUT_OF_MEMORY: Int = 0x505
    val GL_CW: Int = 0x900
    val GL_CCW: Int = 0x901
    val GL_VIEWPORT: Int = 0xba2
    val GL_VENDOR: Int = 0x1f00
    val GL_RENDERER: Int = 0x1f01
    val GL_VERSION: Int = 0x1f02
    val GL_EXTENSIONS: Int = 0x1f03
    val GL_NEAREST: Int = 0x2600
    val GL_LINEAR: Int = 0x2601
    val GL_NEAREST_MIPMAP_NEAREST: Int = 0x2700
    val GL_LINEAR_MIPMAP_NEAREST: Int = 0x2701
    val GL_NEAREST_MIPMAP_LINEAR: Int = 0x2702
    val GL_LINEAR_MIPMAP_LINEAR: Int = 0x2703
    val GL_TEXTURE_MAG_FILTER: Int = 0x2800
    val GL_TEXTURE_MIN_FILTER: Int = 0x2801
    val GL_TEXTURE_WRAP_S: Int = 0x2802
    val GL_TEXTURE_WRAP_T: Int = 0x2803
    val GL_TEXTURE: Int = 0x1702
    val GL_TEXTURE0: Int = 0x84c0
    val GL_TEXTURE1: Int = 0x84c1
    val GL_TEXTURE2: Int = 0x84c2
    val GL_TEXTURE3: Int = 0x84c3
    val GL_TEXTURE4: Int = 0x84c4
    val GL_TEXTURE5: Int = 0x84c5
    val GL_TEXTURE6: Int = 0x84c6
    val GL_TEXTURE7: Int = 0x84c7
    val GL_TEXTURE8: Int = 0x84c8
    val GL_TEXTURE9: Int = 0x84c9
    val GL_TEXTURE10: Int = 0x84ca
    val GL_TEXTURE11: Int = 0x84cb
    val GL_TEXTURE12: Int = 0x84cc
    val GL_TEXTURE13: Int = 0x84cd
    val GL_TEXTURE14: Int = 0x84ce
    val GL_TEXTURE15: Int = 0x84cf
    val GL_TEXTURE16: Int = 0x84d0
    val GL_TEXTURE17: Int = 0x84d1
    val GL_TEXTURE18: Int = 0x84d2
    val GL_TEXTURE19: Int = 0x84d3
    val GL_TEXTURE20: Int = 0x84d4
    val GL_TEXTURE21: Int = 0x84d5
    val GL_TEXTURE22: Int = 0x84d6
    val GL_TEXTURE23: Int = 0x84d7
    val GL_TEXTURE24: Int = 0x84d8
    val GL_TEXTURE25: Int = 0x84d9
    val GL_TEXTURE26: Int = 0x84da
    val GL_TEXTURE27: Int = 0x84db
    val GL_TEXTURE28: Int = 0x84dc
    val GL_TEXTURE29: Int = 0x84dd
    val GL_TEXTURE30: Int = 0x84de
    val GL_TEXTURE31: Int = 0x84df
    val GL_ACTIVE_TEXTURE: Int = 0x84e0
    val GL_REPEAT: Int = 0x2901
    val GL_CLAMP_TO_EDGE: Int = 0x812f
    val GL_MIRRORED_REPEAT: Int = 0x8370
    val GL_FLOAT: Int = 0x1406
    val GL_BYTE: Int = 0x1400
    val GL_UNSIGNED_BYTE: Int = 0x1401
    val GL_SHORT: Int = 0x1402
    val GL_UNSIGNED_SHORT: Int = 0x1403
    val GL_INT: Int = 0x1404
    val GL_UNSIGNED_INT: Int = 0x1405
    val GL_FIXED: Int = 0x140c
    val GL_UNSIGNED_SHORT_5_6_5: Int = 0x8363
    val GL_UNSIGNED_SHORT_4_4_4_4: Int = 0x8033
    val GL_UNSIGNED_SHORT_5_5_5_1: Int = 0x8034
    val GL_ALPHA: Int = 0x1906
    val GL_RGB: Int = 0x1907
    val GL_RGBA: Int = 0x1908
    val GL_LUMINANCE: Int = 0x1909
    val GL_LUMINANCE_ALPHA: Int = 0x190a
    val GL_FRAGMENT_SHADER: Int = 0x8b30
    val GL_VERTEX_SHADER: Int = 0x8b31
    val GL_MAX_VERTEX_ATTRIBS: Int = 0x8869
    val GL_MAX_TEXTURE_SIZE: Int = 0xd33
    val GL_MAX_TEXTURE_IMAGE_UNITS: Int = 0x8872
    val GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: Int = 0x8b4d
    val GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS: Int = 0x8b4c
    val GL_MAX_RENDERBUFFER_SIZE: Int = 0x84e8
    val GL_COMPILE_STATUS: Int = 0x8b81
    val GL_LINK_STATUS: Int = 0x8b82
    val GL_VALIDATE_STATUS: Int = 0x8b83
    val GL_INFO_LOG_LENGTH: Int = 0x8b84
    val GL_SHADER_SOURCE_LENGTH: Int = 0x8b88
    val GL_CURRENT_PROGRAM: Int = 0x8b8d
    val GL_DELETE_STATUS: Int = 0x8b80
    val GL_ATTACHED_SHADERS: Int = 0x8b85
    val GL_ACTIVE_UNIFORMS: Int = 0x8b86
    val GL_ACTIVE_ATTRIBUTES: Int = 0x8b89
    val GL_FRAMEBUFFER: Int = 0x8d40
    val GL_RENDERBUFFER: Int = 0x8d41
    val GL_RGBA4: Int = 0x8056
    val GL_RGB5_A1: Int = 0x8057
    val GL_RGB565: Int = 0x8d62
    val GL_DEPTH_COMPONENT16: Int = 0x81a5
    val GL_STENCIL_INDEX8: Int = 0x8d48
    val GL_RENDERBUFFER_WIDTH: Int = 0x8d42
    val GL_RENDERBUFFER_HEIGHT: Int = 0x8d43
    val GL_RENDERBUFFER_INTERNAL_FORMAT: Int = 0x8d44
    val GL_COLOR_ATTACHMENT0: Int = 0x8ce0
    val GL_DEPTH_ATTACHMENT: Int = 0x8d00
    val GL_STENCIL_ATTACHMENT: Int = 0x8d20
    val GL_NONE: Int = 0x0
    val GL_FRAMEBUFFER_COMPLETE: Int = 0x8cd5
    val GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT: Int = 0x8cd6
    val GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT: Int = 0x8cd7
    val GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS: Int = 0x8cd9
    val GL_FRAMEBUFFER_UNSUPPORTED: Int = 0x8cdd
    val GL_FRAMEBUFFER_BINDING: Int = 0x8ca6
    val GL_RENDERBUFFER_BINDING: Int = 0x8ca7
    val GL_INVALID_FRAMEBUFFER_OPERATION: Int = 0x506
    val GL_TEXTURE_BINDING_2D: Int = 0x8069
    val GL_ARRAY_BUFFER_BINDING: Int = 0x8894
    val GL_ELEMENT_ARRAY_BUFFER_BINDING: Int = 0x8895

    fun glActiveTexture(texture: Int) = photoncam.gles.glActiveTexture(texture.toUInt())
    fun glAttachShader(program: Int, shader: Int) = photoncam.gles.glAttachShader(program.toUInt(), shader.toUInt())
    fun glBindBuffer(target: Int, buffer: Int) = photoncam.gles.glBindBuffer(target.toUInt(), buffer.toUInt())
    fun glBindFramebuffer(target: Int, framebuffer: Int) = photoncam.gles.glBindFramebuffer(target.toUInt(), framebuffer.toUInt())
    fun glBindRenderbuffer(target: Int, renderbuffer: Int) = photoncam.gles.glBindRenderbuffer(target.toUInt(), renderbuffer.toUInt())
    fun glBindTexture(target: Int, texture: Int) = photoncam.gles.glBindTexture(target.toUInt(), texture.toUInt())
    fun glBlendFunc(sfactor: Int, dfactor: Int) = photoncam.gles.glBlendFunc(sfactor.toUInt(), dfactor.toUInt())
    fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) =
        photoncam.gles.glBufferData(target.toUInt(), size.toLong(), data?.pointer(), usage.toUInt())
    fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer?) =
        photoncam.gles.glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), data?.pointer())
    fun glClear(mask: Int) = photoncam.gles.glClear(mask.toUInt())
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        photoncam.gles.glClearColor(red, green, blue, alpha)
    fun glCompileShader(shader: Int) = photoncam.gles.glCompileShader(shader.toUInt())
    fun glCreateProgram(): Int = photoncam.gles.glCreateProgram().toInt()
    fun glCreateShader(type: Int): Int = photoncam.gles.glCreateShader(type.toUInt()).toInt()
    fun glCullFace(mode: Int) = photoncam.gles.glCullFace(mode.toUInt())
    fun glDeleteProgram(program: Int) = photoncam.gles.glDeleteProgram(program.toUInt())
    fun glDeleteShader(shader: Int) = photoncam.gles.glDeleteShader(shader.toUInt())
    fun glDisable(cap: Int) = photoncam.gles.glDisable(cap.toUInt())
    fun glDisableVertexAttribArray(index: Int) = photoncam.gles.glDisableVertexAttribArray(index.toUInt())
    fun glDrawArrays(mode: Int, first: Int, count: Int) = photoncam.gles.glDrawArrays(mode.toUInt(), first, count)
    fun glEnable(cap: Int) = photoncam.gles.glEnable(cap.toUInt())
    fun glEnableVertexAttribArray(index: Int) = photoncam.gles.glEnableVertexAttribArray(index.toUInt())
    fun glFinish() = photoncam.gles.glFinish()
    fun glFlush() = photoncam.gles.glFlush()
    fun glFramebufferRenderbuffer(target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int) =
        photoncam.gles.glFramebufferRenderbuffer(target.toUInt(), attachment.toUInt(), renderbuffertarget.toUInt(), renderbuffer.toUInt())
    fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) =
        photoncam.gles.glFramebufferTexture2D(target.toUInt(), attachment.toUInt(), textarget.toUInt(), texture.toUInt(), level)
    fun glGenerateMipmap(target: Int) = photoncam.gles.glGenerateMipmap(target.toUInt())
    fun glGetError(): Int = photoncam.gles.glGetError().toInt()
    fun glLineWidth(width: Float) = photoncam.gles.glLineWidth(width)
    fun glLinkProgram(program: Int) = photoncam.gles.glLinkProgram(program.toUInt())
    fun glPixelStorei(pname: Int, param: Int) = photoncam.gles.glPixelStorei(pname.toUInt(), param)
    fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) =
        photoncam.gles.glRenderbufferStorage(target.toUInt(), internalformat.toUInt(), width, height)
    fun glScissor(x: Int, y: Int, width: Int, height: Int) = photoncam.gles.glScissor(x, y, width, height)
    fun glTexParameterf(target: Int, pname: Int, param: Float) =
        photoncam.gles.glTexParameterf(target.toUInt(), pname.toUInt(), param)
    fun glTexParameteri(target: Int, pname: Int, param: Int) =
        photoncam.gles.glTexParameteri(target.toUInt(), pname.toUInt(), param)
    fun glUseProgram(program: Int) = photoncam.gles.glUseProgram(program.toUInt())
    fun glValidateProgram(program: Int) = photoncam.gles.glValidateProgram(program.toUInt())
    fun glViewport(x: Int, y: Int, width: Int, height: Int) = photoncam.gles.glViewport(x, y, width, height)

    fun glUniform1f(location: Int, x: Float) = photoncam.gles.glUniform1f(location, x)
    fun glUniform2f(location: Int, x: Float, y: Float) = photoncam.gles.glUniform2f(location, x, y)
    fun glUniform3f(location: Int, x: Float, y: Float, z: Float) = photoncam.gles.glUniform3f(location, x, y, z)
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = photoncam.gles.glUniform4f(location, x, y, z, w)
    fun glUniform1i(location: Int, x: Int) = photoncam.gles.glUniform1i(location, x)
    fun glUniform2i(location: Int, x: Int, y: Int) = photoncam.gles.glUniform2i(location, x, y)
    fun glUniform3i(location: Int, x: Int, y: Int, z: Int) = photoncam.gles.glUniform3i(location, x, y, z)
    fun glUniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = photoncam.gles.glUniform4i(location, x, y, z, w)

    fun glGetAttribLocation(program: Int, name: String): Int =
        photoncam.gles.glGetAttribLocation(program.toUInt(), name)

    fun glGetUniformLocation(program: Int, name: String): Int =
        photoncam.gles.glGetUniformLocation(program.toUInt(), name)

    fun glGetString(name: Int): String? =
        photoncam.gles.glGetString(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    fun glShaderSource(shader: Int, string: String) = memScoped {
        val holder = allocArray<CPointerVar<ByteVar>>(1)
        holder[0] = string.cstr.getPointer(this)
        photoncam.gles.glShaderSource(shader.toUInt(), 1, holder, null)
    }

    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) =
        params.usePinned { photoncam.gles.glGetShaderiv(shader.toUInt(), pname.toUInt(), it.addressOf(offset)) }

    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) =
        params.usePinned { photoncam.gles.glGetProgramiv(program.toUInt(), pname.toUInt(), it.addressOf(offset)) }

    fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) =
        params.usePinned { photoncam.gles.glGetIntegerv(pname.toUInt(), it.addressOf(offset)) }

    fun glGetShaderInfoLog(shader: Int): String = memScoped {
        val len = alloc<IntVar>()
        photoncam.gles.glGetShaderiv(shader.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), len.ptr)
        if (len.value <= 0) return@memScoped ""
        val text = allocArray<ByteVar>(len.value + 1)
        photoncam.gles.glGetShaderInfoLog(shader.toUInt(), len.value, null, text)
        text.toKString()
    }

    fun glGetProgramInfoLog(program: Int): String = memScoped {
        val len = alloc<IntVar>()
        photoncam.gles.glGetProgramiv(program.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), len.ptr)
        if (len.value <= 0) return@memScoped ""
        val text = allocArray<ByteVar>(len.value + 1)
        photoncam.gles.glGetProgramInfoLog(program.toUInt(), len.value, null, text)
        text.toKString()
    }

    // The gen/delete families take (n, int[], offset); GL takes a GLuint*, and
    // an IntArray pins to one because GLuint and Int are the same 32 bits.
    fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) =
        buffers.usePinned { photoncam.gles.glGenBuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        framebuffers.usePinned { photoncam.gles.glGenFramebuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) =
        renderbuffers.usePinned { photoncam.gles.glGenRenderbuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glGenTextures(n: Int, textures: IntArray, offset: Int) =
        textures.usePinned { photoncam.gles.glGenTextures(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) =
        buffers.usePinned { photoncam.gles.glDeleteBuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        framebuffers.usePinned { photoncam.gles.glDeleteFramebuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glDeleteRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) =
        renderbuffers.usePinned { photoncam.gles.glDeleteRenderbuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) =
        textures.usePinned { photoncam.gles.glDeleteTextures(n, it.addressOf(offset).reinterpret<UIntVar>()) }

    fun glUniform1fv(location: Int, count: Int, v: FloatArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform1fv(location, count, it.addressOf(offset)) }
    fun glUniform2fv(location: Int, count: Int, v: FloatArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform2fv(location, count, it.addressOf(offset)) }
    fun glUniform3fv(location: Int, count: Int, v: FloatArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform3fv(location, count, it.addressOf(offset)) }
    fun glUniform4fv(location: Int, count: Int, v: FloatArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform4fv(location, count, it.addressOf(offset)) }
    fun glUniform1iv(location: Int, count: Int, v: IntArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform1iv(location, count, it.addressOf(offset)) }
    fun glUniform2iv(location: Int, count: Int, v: IntArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform2iv(location, count, it.addressOf(offset)) }
    fun glUniform3iv(location: Int, count: Int, v: IntArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform3iv(location, count, it.addressOf(offset)) }
    fun glUniform4iv(location: Int, count: Int, v: IntArray, offset: Int) =
        v.usePinned { photoncam.gles.glUniform4iv(location, count, it.addressOf(offset)) }
    fun glUniformMatrix2fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        value.usePinned { photoncam.gles.glUniformMatrix2fv(location, count, gb(transpose), it.addressOf(offset)) }
    fun glUniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        value.usePinned { photoncam.gles.glUniformMatrix3fv(location, count, gb(transpose), it.addressOf(offset)) }
    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        value.usePinned { photoncam.gles.glUniformMatrix4fv(location, count, gb(transpose), it.addressOf(offset)) }

    fun glVertexAttribPointer(indx: Int, size: Int, type: Int, normalized: Boolean, stride: Int, ptr: Buffer?) =
        photoncam.gles.glVertexAttribPointer(indx.toUInt(), size, type.toUInt(), gb(normalized), stride, ptr?.pointer())

    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer?) =
        photoncam.gles.glReadPixels(x, y, width, height, format.toUInt(), type.toUInt(), pixels?.pointer())

    fun glTexImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int,
                     border: Int, format: Int, type: Int, pixels: Buffer?) =
        photoncam.gles.glTexImage2D(target.toUInt(), level, internalformat, width, height,
            border, format.toUInt(), type.toUInt(), pixels?.pointer())

    fun glTexSubImage2D(target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int,
                        format: Int, type: Int, pixels: Buffer?) =
        photoncam.gles.glTexSubImage2D(target.toUInt(), level, xoffset, yoffset, width, height,
            format.toUInt(), type.toUInt(), pixels?.pointer())

    fun glCheckFramebufferStatus(target: Int): Int = photoncam.gles.glCheckFramebufferStatus(target.toUInt()).toInt()
}

object GLES20 : GLES20Api() {
    object Companion : GLES20Api()
}

open class GLES30Api : GLES20Api() {
// ---- gles30
    val GL_READ_BUFFER: Int = 0xc02
    val GL_UNPACK_ROW_LENGTH: Int = 0xcf2
    val GL_UNPACK_SKIP_ROWS: Int = 0xcf3
    val GL_UNPACK_SKIP_PIXELS: Int = 0xcf4
    val GL_PACK_ROW_LENGTH: Int = 0xd02
    val GL_PACK_SKIP_ROWS: Int = 0xd03
    val GL_PACK_SKIP_PIXELS: Int = 0xd04
    val GL_HALF_FLOAT: Int = 0x140b
    val GL_R8: Int = 0x8229
    val GL_R8I: Int = 0x8231
    val GL_R8UI: Int = 0x8232
    val GL_R8_SNORM: Int = 0x8f94
    val GL_R16F: Int = 0x822d
    val GL_R16I: Int = 0x8233
    val GL_R16UI: Int = 0x8234
    val GL_R32F: Int = 0x822e
    val GL_R32I: Int = 0x8235
    val GL_R32UI: Int = 0x8236
    val GL_RG8: Int = 0x822b
    val GL_RG8I: Int = 0x8237
    val GL_RG8UI: Int = 0x8238
    val GL_RG16F: Int = 0x822f
    val GL_RG16I: Int = 0x8239
    val GL_RG16UI: Int = 0x823a
    val GL_RG32F: Int = 0x8230
    val GL_RG32I: Int = 0x823b
    val GL_RG32UI: Int = 0x823c
    val GL_RGB8: Int = 0x8051
    val GL_RGB8I: Int = 0x8d8f
    val GL_RGB8UI: Int = 0x8d7d
    val GL_RGB16F: Int = 0x881b
    val GL_RGB16I: Int = 0x8d89
    val GL_RGB16UI: Int = 0x8d77
    val GL_RGB32F: Int = 0x8815
    val GL_RGB32I: Int = 0x8d83
    val GL_RGB32UI: Int = 0x8d71
    val GL_RGBA8: Int = 0x8058
    val GL_RGBA8I: Int = 0x8d8e
    val GL_RGBA8UI: Int = 0x8d7c
    val GL_RGBA16F: Int = 0x881a
    val GL_RGBA16I: Int = 0x8d88
    val GL_RGBA16UI: Int = 0x8d76
    val GL_RGBA32F: Int = 0x8814
    val GL_RGBA32I: Int = 0x8d82
    val GL_RGBA32UI: Int = 0x8d70
    val GL_SRGB8: Int = 0x8c41
    val GL_SRGB8_ALPHA8: Int = 0x8c43
    val GL_RGB10_A2: Int = 0x8059
    val GL_R11F_G11F_B10F: Int = 0x8c3a
    val GL_RGB9_E5: Int = 0x8c3d
    val GL_RED: Int = 0x1903
    val GL_RG: Int = 0x8227
    val GL_RED_INTEGER: Int = 0x8d94
    val GL_RG_INTEGER: Int = 0x8228
    val GL_RGB_INTEGER: Int = 0x8d98
    val GL_RGBA_INTEGER: Int = 0x8d99
    val GL_UNSIGNED_INT_24_8: Int = 0x84fa
    val GL_UNSIGNED_INT_2_10_10_10_REV: Int = 0x8368
    val GL_UNSIGNED_INT_10F_11F_11F_REV: Int = 0x8c3b
    val GL_DEPTH_COMPONENT24: Int = 0x81a6
    val GL_DEPTH_COMPONENT32F: Int = 0x8cac
    val GL_DEPTH24_STENCIL8: Int = 0x88f0
    val GL_DEPTH_STENCIL: Int = 0x84f9
    val GL_READ_FRAMEBUFFER: Int = 0x8ca8
    val GL_DRAW_FRAMEBUFFER: Int = 0x8ca9
    val GL_READ_FRAMEBUFFER_BINDING: Int = 0x8caa
    val GL_DRAW_FRAMEBUFFER_BINDING: Int = 0x8ca6
    val GL_TEXTURE_3D: Int = 0x806f
    val GL_TEXTURE_2D_ARRAY: Int = 0x8c1a
    val GL_MAP_READ_BIT: Int = 0x1
    val GL_MAP_WRITE_BIT: Int = 0x2
    val GL_MAP_INVALIDATE_RANGE_BIT: Int = 0x4
    val GL_MAP_INVALIDATE_BUFFER_BIT: Int = 0x8
    val GL_MAP_FLUSH_EXPLICIT_BIT: Int = 0x10
    val GL_MAP_UNSYNCHRONIZED_BIT: Int = 0x20
    val GL_UNIFORM_BUFFER: Int = 0x8a11
    val GL_PIXEL_PACK_BUFFER: Int = 0x88eb
    val GL_PIXEL_UNPACK_BUFFER: Int = 0x88ec
    val GL_COPY_READ_BUFFER: Int = 0x8f36
    val GL_COPY_WRITE_BUFFER: Int = 0x8f37
    val GL_MAX_DRAW_BUFFERS: Int = 0x8824
    val GL_MAX_COLOR_ATTACHMENTS: Int = 0x8cdf
    val GL_MAX_3D_TEXTURE_SIZE: Int = 0x8073
    val GL_MAX_ARRAY_TEXTURE_LAYERS: Int = 0x88ff
    val GL_NUM_EXTENSIONS: Int = 0x821d

    fun glBindBufferBase(target: Int, index: Int, buffer: Int) =
        photoncam.gles.glBindBufferBase(target.toUInt(), index.toUInt(), buffer.toUInt())
    fun glTexStorage2D(target: Int, levels: Int, internalformat: Int, width: Int, height: Int) =
        photoncam.gles.glTexStorage2D(target.toUInt(), levels, internalformat.toUInt(), width, height)
    fun glUnmapBuffer(target: Int): Boolean = photoncam.gles.glUnmapBuffer(target.toUInt()).toInt() != 0
    fun glUniform1ui(location: Int, v0: Int) = photoncam.gles.glUniform1ui(location, v0.toUInt())
    fun glUniform2ui(location: Int, v0: Int, v1: Int) = photoncam.gles.glUniform2ui(location, v0.toUInt(), v1.toUInt())
    fun glUniform3ui(location: Int, v0: Int, v1: Int, v2: Int) =
        photoncam.gles.glUniform3ui(location, v0.toUInt(), v1.toUInt(), v2.toUInt())
    fun glUniform4ui(location: Int, v0: Int, v1: Int, v2: Int, v3: Int) =
        photoncam.gles.glUniform4ui(location, v0.toUInt(), v1.toUInt(), v2.toUInt(), v3.toUInt())
    fun glUniform1uiv(location: Int, count: Int, value: IntArray, offset: Int) =
        value.usePinned { photoncam.gles.glUniform1uiv(location, count, it.addressOf(offset).reinterpret<UIntVar>()) }
    fun glDrawBuffers(n: Int, bufs: IntArray, offset: Int) =
        bufs.usePinned { photoncam.gles.glDrawBuffers(n, it.addressOf(offset).reinterpret<UIntVar>()) }

    /** Android hands back a direct ByteBuffer over the mapping; so does this. */
    fun glMapBufferRange(target: Int, offset: Int, length: Int, access: Int): Buffer? {
        val p = photoncam.gles.glMapBufferRange(target.toUInt(), offset.toLong(), length.toLong(), access.toUInt())
            ?: return null
        return ByteBuffer.wrapPointer(p, length)
    }
}

object GLES30 : GLES30Api() {
    object Companion : GLES30Api()
}

open class GLES31Api : GLES30Api() {
// ---- gles31
    val GL_COMPUTE_SHADER: Int = 0x91b9
    val GL_MAX_COMPUTE_WORK_GROUP_COUNT: Int = 0x91be
    val GL_MAX_COMPUTE_WORK_GROUP_SIZE: Int = 0x91bf
    val GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS: Int = 0x90eb
    val GL_MAX_COMPUTE_SHARED_MEMORY_SIZE: Int = 0x8262
    val GL_DISPATCH_INDIRECT_BUFFER: Int = 0x90ee
    val GL_SHADER_STORAGE_BUFFER: Int = 0x90d2
    val GL_SHADER_STORAGE_BUFFER_BINDING: Int = 0x90d3
    val GL_MAX_SHADER_STORAGE_BLOCK_SIZE: Int = 0x90de
    val GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS: Int = 0x90dc
    val GL_ATOMIC_COUNTER_BUFFER: Int = 0x92c0
    val GL_READ_ONLY: Int = 0x88b8
    val GL_WRITE_ONLY: Int = 0x88b9
    val GL_READ_WRITE: Int = 0x88ba
    val GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT: Int = 0x1
    val GL_ELEMENT_ARRAY_BARRIER_BIT: Int = 0x2
    val GL_UNIFORM_BARRIER_BIT: Int = 0x4
    val GL_TEXTURE_FETCH_BARRIER_BIT: Int = 0x8
    val GL_SHADER_IMAGE_ACCESS_BARRIER_BIT: Int = 0x20
    val GL_COMMAND_BARRIER_BIT: Int = 0x40
    val GL_PIXEL_BUFFER_BARRIER_BIT: Int = 0x80
    val GL_TEXTURE_UPDATE_BARRIER_BIT: Int = 0x100
    val GL_BUFFER_UPDATE_BARRIER_BIT: Int = 0x200
    val GL_FRAMEBUFFER_BARRIER_BIT: Int = 0x400
    val GL_TRANSFORM_FEEDBACK_BARRIER_BIT: Int = 0x800
    val GL_ATOMIC_COUNTER_BARRIER_BIT: Int = 0x1000
    val GL_SHADER_STORAGE_BARRIER_BIT: Int = 0x2000
    val GL_ALL_BARRIER_BITS: Int = -1
    val GL_VERTEX_SHADER_BIT: Int = 0x1
    val GL_FRAGMENT_SHADER_BIT: Int = 0x2
    val GL_COMPUTE_SHADER_BIT: Int = 0x20
    val GL_ALL_SHADER_BITS: Int = -1
    val GL_PROGRAM_SEPARABLE: Int = 0x8258
    val GL_ACTIVE_PROGRAM: Int = 0x8259
    val GL_PROGRAM_PIPELINE_BINDING: Int = 0x825a

    fun glDispatchCompute(num_groups_x: Int, num_groups_y: Int, num_groups_z: Int) =
        photoncam.gles.glDispatchCompute(num_groups_x.toUInt(), num_groups_y.toUInt(), num_groups_z.toUInt())
    fun glMemoryBarrier(barriers: Int) = photoncam.gles.glMemoryBarrier(barriers.toUInt())
    fun glBindImageTexture(unit: Int, texture: Int, level: Int, layered: Boolean, layer: Int, access: Int, format: Int) =
        photoncam.gles.glBindImageTexture(unit.toUInt(), texture.toUInt(), level, gb(layered), layer,
            access.toUInt(), format.toUInt())
}

object GLES31 : GLES31Api() {
    object Companion : GLES31Api()
}

/** The OES external-texture enums the preview shader binds. */
open class GLES11ExtApi : GLES20Api() {
// ---- ext
    val GL_TEXTURE_EXTERNAL_OES: Int = 0x8d65
    val GL_TEXTURE_BINDING_EXTERNAL_OES: Int = 0x8d67
    val GL_SAMPLER_EXTERNAL_OES: Int = 0x8d66
    val GL_REQUIRED_TEXTURE_IMAGE_UNITS_OES: Int = 0x8d68
}

object GLES11Ext : GLES11ExtApi() {
    object Companion : GLES11ExtApi()
}
