/* android.content.Context on a port with no Activity and no package manager.
 *
 * A Context here is three directories -- files, cache and assets -- plus the
 * preference store and the resource table.  main() makes exactly one and hands
 * it to PhotonCamera; getApplicationContext() returns it again.
 *
 * Assets live in an `assets/` directory beside the executable (the host lane
 * stages Compose's own resources as `resources/`); $PHOTONCAMERA_ASSETS
 * overrides it.  getSystemService answers only WINDOW_SERVICE; the rest
 * (sensors, audio) are the camera and host lanes' to supply, and null is what
 * Android returns for a service this device has not got. */
package android.content

import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File

open class Context(
    filesDir: File? = null,
    cacheDir: File? = null,
    assetsDir: File? = null,
) {
    private val files: File = filesDir ?: File(defaultHome(), "files")
    private val cache: File = cacheDir ?: File(defaultHome(), "cache")
    private val prefsDir: File = File(files.getParent() ?: ".", "shared_prefs")
    private val assetsRoot: File = assetsDir ?: File(defaultAssets())
    private val prefs = LinkedHashMap<String, SharedPreferences>()
    private val systemServices = LinkedHashMap<String, Any>()
    private val resources = Resources(AssetManager(assetsRoot))

    init {
        files.mkdirs()
        cache.mkdirs()
        prefsDir.mkdirs()
    }

    open fun getApplicationContext(): Context = this
    open fun getPackageName(): String = "com.particlesdevs.photoncamera"
    open fun getFilesDir(): File = files
    open fun getCacheDir(): File = cache
    open fun getDataDir(): File = File(files.getParent() ?: ".")
    open fun getExternalFilesDir(type: String?): File? =
        File(if (type == null) files.getPath() else File(files, type).getPath()).also { it.mkdirs() }
    open fun getExternalCacheDir(): File? = cache
    open fun getNoBackupFilesDir(): File = files

    open fun getResources(): Resources = resources
    open fun getAssets(): AssetManager = resources.getAssets()
    open fun getString(resId: Int): String = resources.getString(resId)
    open fun getString(resId: Int, vararg args: Any?): String = resources.getString(resId, *args)
    open fun getText(resId: Int): CharSequence = resources.getString(resId)
    open fun getColor(resId: Int): Int = resources.getColor(resId)

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FilePreferences(File(prefsDir, "$name.xml")) }

    open fun openFileInput(name: String): java.io.InputStream =
        java.io.FileInputStream(File(files, name))

    open fun openFileOutput(name: String, mode: Int): java.io.OutputStream =
        java.io.FileOutputStream(File(files, name), mode == MODE_APPEND)

    open fun deleteFile(name: String): Boolean = File(files, name).delete()
    open fun fileList(): Array<String> = files.list() ?: emptyArray()

    fun installSystemService(name: String, service: Any) {
        systemServices[name] = service
    }

    /** Android returns null for a service this device has not got. */
    open fun getSystemService(name: String): Any? = when (name) {
        WINDOW_SERVICE -> android.view.WindowManager
        ACTIVITY_SERVICE -> systemServices[name] ?: android.app.ActivityManager()
        else -> systemServices[name]
    }

    open fun getPackageManager(): android.content.pm.PackageManager = android.content.pm.PackageManager
    open fun getApplicationInfo(): android.content.pm.ApplicationInfo =
        android.content.pm.ApplicationInfo()
    open fun getTheme(): android.content.res.Resources.Theme = resources.newTheme()

    /** No activity manager in this process: the intent is logged and dropped. */
    open fun startActivity(intent: Intent?) {
        android.util.Log.i("Context", "no activity manager: startActivity(\$intent)")
    }

    open fun getContentResolver(): ContentResolver = ContentResolver(this)

    companion object {
        const val MODE_PRIVATE: Int = 0
        const val MODE_APPEND: Int = 32768

        const val SENSOR_SERVICE: String = "sensor"
        const val AUDIO_SERVICE: String = "audio"
        const val VIBRATOR_SERVICE: String = "vibrator"
        const val CAMERA_SERVICE: String = "camera"
        const val WINDOW_SERVICE: String = "window"
        const val ACTIVITY_SERVICE: String = "activity"
        const val POWER_SERVICE: String = "power"
        const val NOTIFICATION_SERVICE: String = "notification"
        const val DISPLAY_SERVICE: String = "display"

        private fun defaultHome(): String {
            java.lang.System.getenv("PHOTONCAMERA_HOME")?.let { return it }
            val home = java.lang.System.getenv("HOME") ?: "."
            return "$home/.local/share/photoncamera"
        }

        /** `assets/` beside the executable, or $PHOTONCAMERA_ASSETS. */
        private fun defaultAssets(): String =
            java.lang.System.getenv("PHOTONCAMERA_ASSETS") ?: "assets"
    }
}

/**
 * ContentResolver: on Linux a content:// URI is a path, so opening one is
 * opening a file, and the MediaStore query is a directory walk (see
 * AndroidContentMedia.kt).  update() is the one member with nothing behind it:
 * there is no row to update when the row is a file.
 */
class ContentResolver(private val context: Context) {
    fun openInputStream(uri: android.net.Uri?): java.io.InputStream? {
        val p = uri?.getPath() ?: return null
        return if (File(p).exists()) java.io.FileInputStream(p) else null
    }

    fun openOutputStream(uri: android.net.Uri?): java.io.OutputStream? = openOutputStream(uri, "w")

    fun openOutputStream(uri: android.net.Uri?, mode: String): java.io.OutputStream? {
        val p = uri?.getPath() ?: return null
        return java.io.FileOutputStream(p, mode.contains("a"))
    }

    fun openFileDescriptor(uri: android.net.Uri?, mode: String): android.os.ParcelFileDescriptor? {
        val p = uri?.getPath() ?: return null
        return android.os.ParcelFileDescriptor.open(File(p), mode)
    }

    fun delete(uri: android.net.Uri?, selection: String?, args: Array<String>?): Int =
        if (uri != null && File(uri.getPath() ?: "").delete()) 1 else 0

    /** MediaStore is the filesystem here; see AndroidContentMedia.kt. */
    fun query(
        uri: android.net.Uri?,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor? = mediaQuery(projection, selection, selectionArgs, sortOrder)

    fun insert(uri: android.net.Uri?, values: ContentValues?): android.net.Uri? =
        if (values == null) null else mediaInsert(values)

    fun update(uri: android.net.Uri?, values: ContentValues?, sel: String?, args: Array<String>?): Int = 0

    fun getType(uri: android.net.Uri?): String? = when (uri?.getPath()?.substringAfterLast('.')) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "dng" -> "image/x-adobe-dng"
        else -> null
    }

    /** No observers in this process; the call is accepted and dropped. */
    fun notifyChange(uri: android.net.Uri?, observer: Any?) {}
}
