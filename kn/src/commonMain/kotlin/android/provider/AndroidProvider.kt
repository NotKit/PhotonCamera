/* android.provider.MediaStore: the column names (an ABI the app builds
 * projections out of) and the content URIs.
 *
 * ON THIS PORT THERE IS NO MEDIA DATABASE.  A query against MediaStore.Images
 * is answered by walking the external storage root for image files, which is
 * what the index would have contained anyway.  Every column the app projects
 * is derivable from the file itself, and ContentResolver does the walking --
 * see AndroidContentResolverMedia.kt. */
package android.provider

import android.net.Uri

object MediaStore {
    const val AUTHORITY: String = "media"
    const val VOLUME_EXTERNAL: String = "external"
    const val VOLUME_EXTERNAL_PRIMARY: String = "external_primary"

    interface MediaColumns {
        companion object {
            const val _ID: String = "_id"
            const val DATA: String = "_data"
            const val DISPLAY_NAME: String = "_display_name"
            const val SIZE: String = "_size"
            const val MIME_TYPE: String = "mime_type"
            const val DATE_ADDED: String = "date_added"
            const val DATE_MODIFIED: String = "date_modified"
            const val DATE_TAKEN: String = "datetaken"
            const val WIDTH: String = "width"
            const val HEIGHT: String = "height"
            const val ORIENTATION: String = "orientation"
            const val RELATIVE_PATH: String = "relative_path"
            const val BUCKET_ID: String = "bucket_id"
            const val BUCKET_DISPLAY_NAME: String = "bucket_display_name"
            const val IS_PENDING: String = "is_pending"
        }
    }

    object Images {
        object Media {
            const val _ID: String = MediaColumns._ID
            const val DATA: String = MediaColumns.DATA
            const val DISPLAY_NAME: String = MediaColumns.DISPLAY_NAME
            const val SIZE: String = MediaColumns.SIZE
            const val MIME_TYPE: String = MediaColumns.MIME_TYPE
            const val DATE_ADDED: String = MediaColumns.DATE_ADDED
            const val DATE_MODIFIED: String = MediaColumns.DATE_MODIFIED
            const val DATE_TAKEN: String = MediaColumns.DATE_TAKEN
            const val WIDTH: String = MediaColumns.WIDTH
            const val HEIGHT: String = MediaColumns.HEIGHT
            const val ORIENTATION: String = MediaColumns.ORIENTATION
            const val RELATIVE_PATH: String = MediaColumns.RELATIVE_PATH
            const val BUCKET_ID: String = MediaColumns.BUCKET_ID
            const val BUCKET_DISPLAY_NAME: String = MediaColumns.BUCKET_DISPLAY_NAME

            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/images/media")
            val INTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/internal/images/media")

            fun getContentUri(volumeName: String): Uri =
                Uri.parse("content://media/$volumeName/images/media")
        }
    }

    object Files {
        fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/file")
    }

    object Video {
        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/video/media")
        }
    }

    /**
     * On Android this asks the user to confirm deleting files the app does not
     * own.  There is no such consent step on this port -- the files are the
     * user's own on their own filesystem -- so the returned request deletes
     * them when it is started.
     */
    fun createDeleteRequest(
        resolver: android.content.ContentResolver,
        uris: List<Uri>,
    ): android.app.PendingIntent = android.app.PendingIntent {
        for (u in uris) resolver.delete(u, null, null)
    }

    fun setRequireOriginal(uri: Uri): Uri = uri
}

/** android.provider.Settings: only the names the app reads. */
object Settings {
    object System {
        const val ACCELEROMETER_ROTATION: String = "accelerometer_rotation"

        fun getInt(resolver: android.content.ContentResolver?, name: String, def: Int): Int = def
    }

    object Secure {
        const val ANDROID_ID: String = "android_id"

        fun getString(resolver: android.content.ContentResolver?, name: String): String? = null
    }

    const val ACTION_APPLICATION_DETAILS_SETTINGS: String =
        "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION: String =
        "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"
}
