/* THE TWO @Tunable / @SensorConfig INJECTORS, PORTED BY HAND.
 *
 * settings/TunableInjector.java and settings/SensorConfigInjector.java walk
 * clazz.getDeclaredFields(), read the annotation off each one and Field.set()
 * the value from SharedPreferences.  Kotlin/Native has no reflection at all --
 * no Field, no annotation values at run time -- so those two files are in
 * drop.txt and these are what the port links instead.
 *
 * WHAT THIS MEANS, CONCRETELY: every @Tunable and @SensorConfig field keeps
 * the value its Java initialiser gives it.  A value the user changed in the
 * settings screen is stored (TunableSettingsManager still reads and writes the
 * pref_tunable_* keys) but is NOT applied to the field.
 *
 * TO MAKE IT REAL, the binding has to become explicit: each class in
 * TunableRegistry.TUNABLE_CLASSES grows a method that reads its own tunables
 * off SettingsManager by key.  That is ~168 fields across processing/, so it
 * is the gl lane's to do, and until it does, this is the seam.
 *
 * ONE DIVERGENCE FROM THE JAVA WORTH KNOWING: the Java resolves
 * `defaultValue == -999999f` (the annotation's "use the field's initial
 * value") to annotation.min(), not to the initialiser -- so on Android those
 * fields are clamped to min on the first inject and here they are not.
 * Where the two builds are diffed, that is the difference to expect.
 */
package com.particlesdevs.photoncamera.settings

import com.particlesdevs.photoncamera.util.Log

object TunableInjector {
    private const val TAG = "TunableInjector"

    fun inject(target: Any?) {
        if (target == null) return
        Log.d(TAG, "no reflection on this port: " +
            "${target::class.simpleName} keeps its @Tunable field initialisers")
    }
}

object SensorConfigInjector {
    private const val TAG = "SensorConfigInjector"

    fun applyToSensor(sensorId: String?, target: Any?) {
        if (target == null || sensorId.isNullOrEmpty()) return
        Log.d(TAG, "no reflection on this port: " +
            "${target::class.simpleName} keeps its @SensorConfig field initialisers for $sensorId")
    }
}
