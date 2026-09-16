/* androidx.core.app.ActivityCompat: the permission and intent-sender helpers.
 * Every permission is held on this port (there is no permission model), and an
 * intent sender is run directly -- see android.app.PendingIntent. */
package androidx.core.app

object ActivityCompat {
    fun checkSelfPermission(context: android.content.Context, permission: String): Int = 0
    fun requestPermissions(activity: android.app.Activity?, permissions: Array<String>, requestCode: Int) {}
    fun shouldShowRequestPermissionRationale(activity: android.app.Activity?, permission: String): Boolean = false

    fun startIntentSenderForResult(
        activity: android.app.Activity?,
        intent: android.app.PendingIntent?,
        requestCode: Int,
        fillInIntent: android.content.Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: android.os.Bundle?,
    ) { intent?.send() }
}
