/* android.content.pm: this binary is the only package there is, so the package
 * manager answers about itself off BuildConfig and the executable's own path.
 * setComponentEnabledSetting has no launcher to affect and does nothing. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package android.content.pm

import com.particlesdevs.photoncamera.BuildConfig

class PackageInfo {
    var packageName: String = BuildConfig.APPLICATION_ID
    var versionName: String = BuildConfig.VERSION_NAME
    var versionCode: Int = BuildConfig.VERSION_CODE
    var longVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    var applicationInfo: ApplicationInfo = ApplicationInfo()
}

class ApplicationInfo {
    var packageName: String = BuildConfig.APPLICATION_ID
    var dataDir: String = ""

    /** The directory the executable itself lives in. */
    var nativeLibraryDir: String = exeDir()

    private companion object {
        fun exeDir(): String {
            val link = java.io.File("/proc/self/exe").getCanonicalPath()
            return java.io.File(link).getParent() ?: "."
        }
    }
}

object PackageManager {
    const val COMPONENT_ENABLED_STATE_DEFAULT: Int = 0
    const val COMPONENT_ENABLED_STATE_ENABLED: Int = 1
    const val COMPONENT_ENABLED_STATE_DISABLED: Int = 2
    const val DONT_KILL_APP: Int = 1
    const val PERMISSION_GRANTED: Int = 0
    const val PERMISSION_DENIED: Int = -1

    class NameNotFoundException(message: String? = null) : Exception(message)

    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName != BuildConfig.APPLICATION_ID) throw NameNotFoundException(packageName)
        return PackageInfo()
    }

    fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo = ApplicationInfo()
    fun checkPermission(permission: String, packageName: String): Int = PERMISSION_GRANTED

    /** No launcher here, so a component's enabled state is not observable. */
    fun setComponentEnabledSetting(component: android.content.ComponentName?, newState: Int, flags: Int) {}
    fun getComponentEnabledSetting(component: android.content.ComponentName?): Int =
        COMPONENT_ENABLED_STATE_DEFAULT
}

/** The colour-mode constants a window would take; an ABI. */
object ActivityInfo {
    const val COLOR_MODE_DEFAULT: Int = 0
    const val COLOR_MODE_WIDE_COLOR_GAMUT: Int = 1
    const val COLOR_MODE_HDR: Int = 2
    const val SCREEN_ORIENTATION_PORTRAIT: Int = 1
    const val SCREEN_ORIENTATION_LANDSCAPE: Int = 0
    const val SCREEN_ORIENTATION_UNSPECIFIED: Int = -1
}
