/* The BR ids the app's @Bindable getters notify with.  The data-binding
 * compiler generates these from the getter names; j2k rewrites the import to
 * the app package, which is where this lives. */
package com.particlesdevs.photoncamera

object BR {
    const val _all: Int = 0
    const val currentCameraId: Int = 1
    const val dummyAspectRatio: Int = 2
    const val histogramModel: Int = 3
    const val screenAspectRatio: Int = 4
}
