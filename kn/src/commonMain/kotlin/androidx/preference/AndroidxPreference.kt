/* androidx.preference.PreferenceManager: the default SharedPreferences file
 * only.  The Preference widget tree is UI -- the Compose settings screen in
 * ui-compose replaces it -- so Preference, PreferenceScreen, PreferenceCategory
 * and the rest are ABSENT here and their generators are not converted. */
package androidx.preference

object PreferenceManager {
    fun getDefaultSharedPreferences(context: android.content.Context): android.content.SharedPreferences =
        context.getSharedPreferences(getDefaultSharedPreferencesName(context), android.content.Context.MODE_PRIVATE)

    fun getDefaultSharedPreferencesName(context: android.content.Context): String =
        context.getPackageName() + "_preferences"
}
