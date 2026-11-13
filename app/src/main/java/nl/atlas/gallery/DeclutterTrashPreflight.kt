package nl.atlas.gallery

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Original photos are NEVER deleted by this checker. It reads metadata just
 * before ATLAS asks Android to display its separate system Trash dialog.
 */
internal data class CurrentDeclutterMedia(
    val id: Long, val name: String, val relativePath: String,
    val sizeBytes: Long, val modifiedAtSeconds: Long, val isTrashed: Boolean
)

/** Never infer unchanged content from an unknown/zero modification timestamp. */
internal fun sameReviewedMedia(old: MediaRecord, now: CurrentDeclutterMedia): Boolean =
    old.modifiedAtSeconds > 0L &&
        now.modifiedAtSeconds > 0L &&
        old.id == now.id &&
        old.name == now.name &&
        old.relativePath == now.relativePath &&
        old.sizeBytes == now.sizeBytes &&
        old.modifiedAtSeconds == now.modifiedAtSeconds &&
        !now.isTrashed

internal object DeclutterTrashPreflight {
    private const val MAX_BATCH = 200

    /**
     * Fail closed when anything changed, vanished, was moved/trashed, lost
     * permission, or lacks trustworthy metadata. No private names or paths
     * are logged or exported. Call on Dispatchers.IO, never main thread.
     */
    fun unchanged(resolver: ContentResolver, selected: List<MediaRecord>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            selected.isEmpty() || selected.size > MAX_BATCH ||
            selected.map { it.uri }.distinct().size != selected.size) return false

        return selected.all { old ->
            val uri = Uri.parse(old.uri)
            val segments = uri.pathSegments
            val kind = if (old.isVideo) "video" else "images"
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
                segments.size != 4 || segments[1] != kind ||
                segments[2] != "media" || segments[3].toLongOrNull() != old.id ||
                old.modifiedAtSeconds <= 0L) return@all false

            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_TRASHED
            )
            try {
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@all false
                    // A direct item content URI MUST resolve to exactly one row.
                    val current = CurrentDeclutterMedia(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                            ?: return@all false,
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                            ?: return@all false,
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                        modifiedAtSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
                        isTrashed = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)) != 0
                    )
                    sameReviewedMedia(old, current) && !cursor.moveToNext()
                } ?: false
            } catch (_: RuntimeException) {
                // Provider changed permissions, schema or record mid-flight.
                false
            }
        }
    }
}
