/* android.app: Application is the Context the whole app reaches for; main()
 * makes exactly one.
 *
 * Activity exists because the Java's lifecycle plumbing names it, and NOTHING
 * ON THIS PORT EVER CREATES ONE -- there are no Android windows here, the
 * Compose scene is the host lane's.  What it does carry is real:
 * runOnUiThread posts to the main Looper, and getApplication returns the one
 * Application.  PhotonCamera.getInstance(context) therefore answers null, the
 * same as it does on Android for a non-Activity Context. */
package android.app

/** A deferred action.  On Android this crosses to the system server; here it
 *  is a callback the caller runs itself through its intent sender. */
fun interface PendingIntent {
    fun send()
    fun getIntentSender(): PendingIntent = this
}

open class Application(
    filesDir: java.io.File? = null,
    cacheDir: java.io.File? = null,
    assetsDir: java.io.File? = null,
) : android.content.Context(filesDir, cacheDir, assetsDir) {
    private val lifecycleCallbacks = ArrayList<ActivityLifecycleCallbacks>()

    open fun onCreate() {}
    open fun onTerminate() {}

    fun registerActivityLifecycleCallbacks(callbacks: ActivityLifecycleCallbacks?) {
        if (callbacks != null && callbacks !in lifecycleCallbacks) lifecycleCallbacks.add(callbacks)
    }

    fun unregisterActivityLifecycleCallbacks(callbacks: ActivityLifecycleCallbacks?) {
        lifecycleCallbacks.remove(callbacks)
    }

    interface ActivityLifecycleCallbacks {
        fun onActivityCreated(activity: Activity?, savedInstanceState: android.os.Bundle?)
        fun onActivityStarted(activity: Activity?)
        fun onActivityResumed(activity: Activity?)
        fun onActivityPaused(activity: Activity?)
        fun onActivityStopped(activity: Activity?)
        fun onActivitySaveInstanceState(activity: Activity?, outState: android.os.Bundle?)
        fun onActivityDestroyed(activity: Activity?)
    }
}

open class Activity : android.content.Context() {
    private var application: Application? = null

    open fun getApplication(): Application? = application
    internal fun attach(app: Application) { application = app }

    open fun getLocalClassName(): String = this::class.simpleName ?: "Activity"
    open fun getComponentName(): String = getPackageName() + "/" + getLocalClassName()
    open fun isFinishing(): Boolean = false
    open fun isDestroyed(): Boolean = false
    open fun finish() {}

    private val window = android.view.Window()

    open fun getWindow(): android.view.Window = window

    /** Real: the main Looper is the queue the host's main() drains. */
    fun runOnUiThread(action: java.lang.Runnable) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}
