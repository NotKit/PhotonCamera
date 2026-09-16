/* settings/TunableRegistry.java lists the classes the ANDROID preference screen
 * walks for @Tunable fields.  This port has no preference screen and no
 * reflection (see TunableInjectorKn.kt), and one of the entries -- the Compose
 * CameraScreenHost -- lives in src/linuxMain, out of reach from here.  So the
 * registry is empty: TunableSettingsManager still resets, exports and imports
 * every pref_tunable_* key by prefix, which never needed the class list. */
package com.particlesdevs.photoncamera.settings

object TunableRegistry {
    val TUNABLE_CLASSES: Array<kotlin.reflect.KClass<*>> = emptyArray()
}
