package android.opengl

open class GLUtilsApi {
    fun getEGLErrorString(error: Int): String = when (error) {
        EGL14.EGL_SUCCESS -> "EGL_SUCCESS"
        EGL14.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED"
        EGL14.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS"
        EGL14.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC"
        EGL14.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE"
        EGL14.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG"
        EGL14.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT"
        EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
        EGL14.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY"
        EGL14.EGL_BAD_MATCH -> "EGL_BAD_MATCH"
        EGL14.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP"
        EGL14.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW"
        EGL14.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER"
        EGL14.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE"
        EGL14.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST"
        else -> "0x" + error.toString(16)
    }
}

object GLUtils : GLUtilsApi() {
    object Companion : GLUtilsApi()
}

/** android.opengl.Matrix, column-major 4x4, as in AOSP. */
open class MatrixApi {
    fun setIdentityM(sm: FloatArray, smOffset: Int) {
        for (i in 0 until 16) sm[smOffset + i] = 0.0f
        for (i in 0 until 16 step 5) sm[smOffset + i] = 1.0f
    }

    fun setRotateM(rm: FloatArray, rmOffset: Int, a: Float, x0: Float, y0: Float, z0: Float) {
        var x = x0; var y = y0; var z = z0
        rm[rmOffset + 3] = 0.0f
        rm[rmOffset + 7] = 0.0f
        rm[rmOffset + 11] = 0.0f
        rm[rmOffset + 12] = 0.0f
        rm[rmOffset + 13] = 0.0f
        rm[rmOffset + 14] = 0.0f
        rm[rmOffset + 15] = 1.0f
        val r = a * (kotlin.math.PI.toFloat() / 180.0f)
        val s = kotlin.math.sin(r)
        val c = kotlin.math.cos(r)
        if (1.0f == x && 0.0f == y && 0.0f == z) {
            rm[rmOffset + 5] = c; rm[rmOffset + 10] = c
            rm[rmOffset + 6] = s; rm[rmOffset + 9] = -s
            rm[rmOffset + 1] = 0.0f; rm[rmOffset + 2] = 0.0f
            rm[rmOffset + 4] = 0.0f; rm[rmOffset + 8] = 0.0f
            rm[rmOffset + 0] = 1.0f
        } else if (0.0f == x && 1.0f == y && 0.0f == z) {
            rm[rmOffset + 0] = c; rm[rmOffset + 10] = c
            rm[rmOffset + 8] = s; rm[rmOffset + 2] = -s
            rm[rmOffset + 1] = 0.0f; rm[rmOffset + 4] = 0.0f
            rm[rmOffset + 6] = 0.0f; rm[rmOffset + 9] = 0.0f
            rm[rmOffset + 5] = 1.0f
        } else if (0.0f == x && 0.0f == y && 1.0f == z) {
            rm[rmOffset + 0] = c; rm[rmOffset + 5] = c
            rm[rmOffset + 1] = s; rm[rmOffset + 4] = -s
            rm[rmOffset + 2] = 0.0f; rm[rmOffset + 6] = 0.0f
            rm[rmOffset + 8] = 0.0f; rm[rmOffset + 9] = 0.0f
            rm[rmOffset + 10] = 1.0f
        } else {
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (1.0f != len) {
                val recipLen = 1.0f / len
                x *= recipLen; y *= recipLen; z *= recipLen
            }
            val nc = 1.0f - c
            val xy = x * y; val yz = y * z; val zx = z * x
            val xs = x * s; val ys = y * s; val zs = z * s
            rm[rmOffset + 0] = x * x * nc + c
            rm[rmOffset + 4] = xy * nc - zs
            rm[rmOffset + 8] = zx * nc + ys
            rm[rmOffset + 1] = xy * nc + zs
            rm[rmOffset + 5] = y * y * nc + c
            rm[rmOffset + 9] = yz * nc - xs
            rm[rmOffset + 2] = zx * nc - ys
            rm[rmOffset + 6] = yz * nc + xs
            rm[rmOffset + 10] = z * z * nc + c
        }
    }

    fun multiplyMM(result: FloatArray, resultOffset: Int, lhs: FloatArray, lhsOffset: Int,
                   rhs: FloatArray, rhsOffset: Int) {
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0f
                for (k in 0 until 4) sum += lhs[lhsOffset + k * 4 + j] * rhs[rhsOffset + i * 4 + k]
                result[resultOffset + i * 4 + j] = sum
            }
        }
    }
}

object Matrix : MatrixApi() {
    object Companion : MatrixApi()
}
