package nl.atlas.gallery.featureintents

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.galleryItemHintOrNull
import nl.atlas.gallery.readGalleryMediaGrant

/**
 * BENCH-2 (#573) A2: swipeable neighbours for an ordinary VIEW or REVIEW of a
 * MediaStore item.
 *
 * The #573 revision allows neighbours "only for independently readable
 * MediaStore rows" and never for secure review. So this reads with ATLAS's
 * OWN media grant, never through the caller's URI grant: the folder query
 * returns only rows ATLAS may already read, and with no grant of its own it
 * returns nothing and the viewer stays on the single item. Nothing here
 * starts a catalogue scan or asks for a permission.
 */
internal class NeighbourRow(
    val id: Long,
    val isVideo: Boolean,
    val takenAtMillis: Long,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMillis: Long
)

internal object ExternalNeighbourPlan {
    /** Rows each side of the opened item; a folder of 20k still opens at once. */
    const val WINDOW = 500

    /**
     * The opened item's folder in gallery order (newest first, id breaks ties),
     * cut to [window] rows each side. Null when the opened item is not among
     * [rows] (ATLAS can't read it itself) or it has no neighbour at all.
     */
    fun window(
        rows: List<NeighbourRow>,
        currentId: Long,
        currentIsVideo: Boolean,
        window: Int = WINDOW
    ): List<NeighbourRow>? {
        val ordered = rows.distinctBy { it.isVideo to it.id }
            .sortedWith(compareByDescending<NeighbourRow> { it.takenAtMillis }.thenByDescending { it.id })
        val at = ordered.indexOfFirst { it.id == currentId && it.isVideo == currentIsVideo }
        if (at < 0 || ordered.size < 2) return null
        return ordered.subList((at - window).coerceAtLeast(0), (at + window + 1).coerceAtMost(ordered.size))
    }
}

internal object ExternalNeighbours {
    /**
     * The viewer's list for [current], or null to keep the single item. [current]
     * keeps the caller's exact URI string so the pager opens on it.
     */
    fun load(context: Context, entry: ExternalEntry.Viewer, current: MediaRecord): List<MediaRecord>? {
        if (entry.kind.secure) return null
        val hint = runCatching { galleryItemHintOrNull(Uri.parse(entry.uri)) }.getOrNull() ?: return null
        val grant = readGalleryMediaGrant(context)
        if (!grant.hasAny) return null
        val resolver = context.contentResolver
        return runCatching {
            // ATLAS's own read of the row, through a collection query that the
            // caller's single-item grant does not cover.
            val itemCollection = if (hint.video) MediaStore.Video.Media.getContentUri(hint.volume)
                else MediaStore.Images.Media.getContentUri(hint.volume)
            val bucket = resolver.query(itemCollection, arrayOf(MediaStore.MediaColumns.BUCKET_ID),
                "${MediaStore.MediaColumns._ID} = ?", arrayOf(hint.id.toString()), null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            } ?: return null
            val rows = buildList {
                if (grant.images || grant.selected) addAll(folderRows(context, hint.volume, bucket, video = false))
                if (grant.videos || grant.selected) addAll(folderRows(context, hint.volume, bucket, video = true))
            }
            val window = ExternalNeighbourPlan.window(rows, hint.id, hint.video) ?: return null
            window.map { row ->
                if (row.id == hint.id && row.isVideo == hint.video) current
                else MediaRecord(
                    id = row.id,
                    uri = ContentUris.withAppendedId(
                        if (row.isVideo) MediaStore.Video.Media.getContentUri(hint.volume)
                        else MediaStore.Images.Media.getContentUri(hint.volume), row.id).toString(),
                    name = row.name,
                    relativePath = row.relativePath,
                    sizeBytes = row.sizeBytes,
                    takenAtMillis = row.takenAtMillis,
                    isVideo = row.isVideo,
                    durationMillis = row.durationMillis,
                    volumeName = hint.volume
                )
            }
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }
    }

    private fun folderRows(context: Context, volume: String, bucket: Long, video: Boolean): List<NeighbourRow> {
        val collection = if (video) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add("datetaken")
            if (video) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        // A denied type (photos only, say) throws or returns null: no rows, not a failure.
        return runCatching {
            context.contentResolver.query(collection, projection,
                "${MediaStore.MediaColumns.BUCKET_ID} = ?", arrayOf(bucket.toString()), null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val path = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val taken = cursor.getColumnIndex("datetaken")
                val duration = if (video) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
                fun long(index: Int) = if (index < 0 || cursor.isNull(index)) 0L else cursor.getLong(index)
                fun text(index: Int) = if (index < 0 || cursor.isNull(index)) "" else cursor.getString(index)
                buildList {
                    while (cursor.moveToNext()) {
                        val rowId = long(id).takeIf { it > 0L } ?: continue
                        val takenAt = long(taken).takeIf { it > 0L } ?: (long(modified) * 1000L)
                        add(NeighbourRow(rowId, video, takenAt, text(name), text(path), long(size), long(duration)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}
