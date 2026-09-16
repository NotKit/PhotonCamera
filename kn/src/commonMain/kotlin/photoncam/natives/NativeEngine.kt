@file:OptIn(ExperimentalForeignApi::class)

package photoncam.natives

import kotlinx.cinterop.ExperimentalForeignApi
import photoncam.natives.abi.pc_nativeengine_available
import photoncam.natives.abi.pc_nativeengine_nativeInitialize

/**
 * com.particlesdevs.photoncamera.api.NativeEngine's three native methods
 * (app/src/main/cpp/native-engine.cpp).
 *
 * That library is an ART hidden-API bypass: it walks the runtime's own
 * ArtMethod/ArtField tables to hand back java.lang.reflect objects for camera2
 * members the platform hides.  Off Android there is no ART, no hidden API and
 * no reflection to bypass, so native-engine.cpp is not built at all and the two
 * resolvers answer null - which is exactly the signal NativeEngine.java already
 * treats as "use the standard access", its own fallback path.
 *
 * The resolvers are typed `Nothing?` rather than Method?/Field? so this lane
 * needs no java.lang.reflect shim: `Nothing?` satisfies either return type at
 * the call site, and null is the only value they can ever produce.
 */
object NativeEngine {

    /** Always false here; the bypass exists only on Android. */
    val isAvailable: Boolean get() = pc_nativeengine_available() != 0

    fun nativeInitialize() = pc_nativeengine_nativeInitialize()

    fun nativeGetCameraMethod(clazz: Any?, methodName: String?, parameterTypes: Any?): Nothing? =
        null

    fun nativeGetCameraField(clazz: Any?, fieldName: String?): Nothing? = null
}
