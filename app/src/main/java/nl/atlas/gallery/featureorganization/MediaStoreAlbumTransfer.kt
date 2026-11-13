package nl.atlas.gallery.featureorganization

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * MediaStore side of Samsung's Copy to album / Move to album (#568).
 *
 * Copy inserts a new pending row in the target folder, streams the original
 * bytes into it, checks the byte count, then publishes it; the original is
 * never opened for writing. A failed or cancelled copy deletes its own
 * half-written row. Move only changes RELATIVE_PATH (and DISPLAY_NAME on a
 * name clash) after Android's own write-consent dialog, so bytes never move
 * through ATLAS. Call everything off the main thread.
 */
object MediaStoreAlbumTransfer {
    /** MediaStore's "external" is the primary volume; inserts need a concrete name. */
    private fun concreteVolume(volume: String): String =
        if (volume == MediaStore.VOLUME_EXTERNAL) MediaStore.VOLUME_EXTERNAL_PRIMARY else volume

    /** Display names already in the target folder that ATLAS can see. */
    fun takenNames(resolver: ContentResolver, target: AlbumTransferTarget): Set<String> {
        val names = HashSet<String>()
        val uri = MediaStore.Files.getContentUri(concreteVolume(target.volumeName))
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(target.relativePath), null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
        }
        return names
    }

    /** Copies one item; true only when a complete, published copy exists. */
    suspend fun copy(resolver: ContentResolver, step: AlbumTransferStep, target: AlbumTransferTarget): Boolean {
        val source = step.source
        val sourceUri = Uri.parse(source.uri)
        val volume = concreteVolume(target.volumeName)
        val collection = if (source.isVideo) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        val mime = resolver.getType(sourceUri)
            ?: if (source.isVideo) "video/mp4" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, step.targetName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (source.takenAtMillis > 0L) put(MediaStore.MediaColumns.DATE_TAKEN, source.takenAtMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = resolver.insert(collection, values) ?: return false
        var published = false
        try {
            val copied = resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(created, "w")?.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    total
                }
            } ?: return false
            // A short read is a broken copy, not a smaller photo.
            if (copied <= 0L || (source.sizeBytes > 0L && copied != source.sizeBytes)) return false
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (source.takenAtMillis > 0L) put(MediaStore.MediaColumns.DATE_TAKEN, source.takenAtMillis)
            }
            published = resolver.update(created, publish, null, null) == 1
            return published
        } finally {
            if (!published) withContext(NonCancellable) {
                runCatching { resolver.delete(created, null, null) }
            }
        }
    }

    /**
     * Moves one item after write consent. True only when MediaStore reports
     * the row in the target folder afterwards.
     */
    fun move(resolver: ContentResolver, step: AlbumTransferStep, target: AlbumTransferTarget): Boolean {
        val uri = Uri.parse(step.source.uri)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            if (step.targetName != step.source.name)
                put(MediaStore.MediaColumns.DISPLAY_NAME, step.targetName)
        }
        val updated = resolver.update(uri, values, null, null)
        val landed = updated == 1 && resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, null)?.use { it.moveToFirst() && it.getString(0) == target.relativePath } == true
        if (!landed) android.util.Log.i("ATLAS_TRANSFER", "move rows updated=$updated landed=false")
        return landed
    }
}
