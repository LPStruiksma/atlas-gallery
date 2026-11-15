package nl.atlas.gallery.featureaiedit.save

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.atlas.gallery.GalleryViewModel
import nl.atlas.gallery.clearViewerMetadataCache
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Save ONLY an accepted candidate, always as a new MediaStore image. Never opens
 * the original for writing, requests storage permissions or makes a network call.
 *
 * The remote/UI adapter owns temporary candidate cleanup. Call from a coroutine;
 * publishing, EXIF and journal IO run on IO; callbacks run on Main.
 */
internal class AiEditSaver(
    private val context: Context,
    private val lineageStore: AiEditLineageStore = AiEditLineageStore(context),
    /** Deterministic fault injection for instrumented tests, after journaling. */
    private val afterInsert: (Uri) -> Unit = {}
) {
    private val resolver: ContentResolver = context.contentResolver

    private data class SourceFacts(
        val name: String, val relativePath: String, val volume: String,
        val dateTaken: Long?
    )

    private fun sourceFacts(uri: Uri): SourceFacts {
        check(uri.scheme == "content" && uri.authority == "media")
        val columns = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "Selected original is no longer available" }
            val mime = cursor.getString(4)
            check(mime?.startsWith("image/") == true) { "AI Edit source must be an image" }
            val path = cursor.getString(1)
            val volume = cursor.getString(2)
            check(!path.isNullOrBlank() && !volume.isNullOrBlank())
            check(volume in MediaStore.getExternalVolumeNames(context)) {
                "Original media volume is no longer mounted"
            }
            SourceFacts(
                name = cursor.getString(0) ?: error("Original has no name"),
                relativePath = path,
                volume = volume,
                dateTaken = if (cursor.isNull(3)) null else cursor.getLong(3)
            )
        } ?: error("Cannot inspect original MediaStore image")
    }

    private fun collection(volume: String): Uri =
        MediaStore.Images.Media.getContentUri(volume)

    private fun existingNames(source: SourceFacts): Set<String> {
        val names = HashSet<String>()
        resolver.query(MediaStore.setIncludePending(collection(source.volume)),
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            MediaStore.Images.Media.RELATIVE_PATH + " = ?",
            arrayOf(source.relativePath), null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
        } ?: error("Cannot check source folder for name collisions")
        return names
    }

    private fun compressPixels(input: Uri, format: AiEditImageFormat, output: OutputStream) {
        // ImageDecoder applies the candidate's orientation to actual pixels.
        // No downsampling: accept/save at the exact candidate output resolution.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, input)) {
                decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val compression = when (format) {
                AiEditImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                AiEditImageFormat.PNG -> Bitmap.CompressFormat.PNG
                AiEditImageFormat.WEBP ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    else error("Lossless WebP encoding requires Android 11")
            }
            check(bitmap.compress(compression, 100, output)) { "Image encoding failed" }
            output.flush()
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyExif(original: Uri, target: Uri, candidate: AiEditCandidate) {
        val originalExif = resolver.openInputStream(original)?.use { ExifInterface(it) }
            ?: error("Cannot read original capture metadata")
        // Freshly encoded pixels contain no original/candidate metadata. Explicit
        // allowlist only; candidate GPS, camera serial, maker notes, comments and
        // user-identifying metadata can never propagate by accident.
        resolver.openFileDescriptor(target, "rw")?.use { descriptor ->
            val outputExif = ExifInterface(descriptor.fileDescriptor)
            val keep = mutableListOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED
            )
            if (candidate.keepGps) keep += listOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF
            )
            for (tag in keep) originalExif.getAttribute(tag)?.let {
                outputExif.setAttribute(tag, it)
            }
            outputExif.setAttribute(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString())
            outputExif.setAttribute(ExifInterface.TAG_SOFTWARE, "ATLAS AI Edit")
            // Provider/model are already bounded by AiEditCandidate validation.
            outputExif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                candidate.provider + "/" + candidate.model)
            outputExif.saveAttributes()
        } ?: error("Cannot write new image metadata")
    }

    private fun pendingValues(source: SourceFacts, name: String,
                              format: AiEditImageFormat): ContentValues =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.RELATIVE_PATH, source.relativePath)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (source.dateTaken != null && source.dateTaken > 0L)
                put(MediaStore.Images.Media.DATE_TAKEN, source.dateTaken)
        }

    /**
     * Owns the single-writer journal sequence; a process death between insert
     * and URI registration is repaired by matching the prewritten volume/folder/name.
     */
    private fun saveBlocking(candidate: AiEditCandidate): AiEditSaveResult {
        recoverInterruptedSaves()
        val source = sourceFacts(candidate.originalUri)
        val name = AiEditNaming.next(source.name, candidate.format, existingNames(source))
        val token = UUID.randomUUID().toString()
        lineageStore.registerIntent(token, source.volume, source.relativePath, name)
        var inserted: Uri? = null
        var published = false
        try {
            inserted = resolver.insert(collection(source.volume),
                pendingValues(source, name, candidate.format))
                ?: error("MediaStore refused the new AI edit")
            lineageStore.recordInserted(token, inserted.toString())
            afterInsert(inserted)
            resolver.openOutputStream(inserted, "w")?.use {
                compressPixels(candidate.candidateUri, candidate.format, it)
            } ?: error("Cannot write accepted image")
            copyExif(candidate.originalUri, inserted, candidate)
            val lineage = AiEditLineage(
                editedUri = inserted.toString(),
                originalUri = candidate.originalUri.toString(),
                originalRevision = candidate.originalRevision,
                provider = candidate.provider,
                model = candidate.model,
                modelVersion = candidate.modelVersion,
                prompt = candidate.prompt,
                settingsJson = candidate.settingsJson,
                createdAtMillis = candidate.createdAtMillis,
                candidateParentUri = candidate.candidateParentUri?.toString()
            )
            // Persist lineage before publishing; a crash while still pending
            // rolls this back during recovery.
            lineageStore.put(lineage)
            val update = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            check(resolver.update(inserted, update, null, null) == 1) {
                "MediaStore did not publish AI edit"
            }
            published = true
            // If journal cleanup fails, next save/recovery sees a published row
            // and retains its lineage. Never delete an already published photo.
            runCatching { lineageStore.finishIntent(token, discardLineage = false) }
            return AiEditSaveResult(inserted, candidate.originalUri, name, lineage)
        } catch (failure: Throwable) {
            if (!published) {
                try {
                    val target = inserted ?: pendingItem(
                        AiEditLineageStore.PendingSave(token, source.volume,
                            source.relativePath, name, null))?.first
                    if (target != null) {
                        check(resolver.delete(target, null, null) == 1) {
                            "Could not delete failed AI edit pending row"
                        }
                    }
                    lineageStore.finishIntent(token, discardLineage = true)
                } catch (cleanupFailure: Throwable) {
                    // Keep the journal for process-death recovery on next open.
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /**
     * Bridge for future AI Edit UI: refresh the existing catalog/Coil cache
     * epoch, then focus the *new* URI while retaining the original anchor.
     * Caller should keep the focus intent until the refreshed catalog contains
     * the new URI; old viewer/editor routes are deliberately unchanged.
     */
    suspend fun save(
        candidate: AiEditCandidate,
        onRefresh: () -> Unit = {},
        onFocus: (Uri) -> Unit = {}
    ): AiEditSaveResult {
        val result = withContext(Dispatchers.IO) {
            saveMutex.withLock { saveBlocking(candidate) }
        }
        withContext(Dispatchers.Main.immediate) {
            clearViewerMetadataCache()
            onRefresh()
            onFocus(result.uri)
        }
        return result
    }

    suspend fun saveForGallery(
        candidate: AiEditCandidate, gallery: GalleryViewModel,
        onFocus: (Uri) -> Unit
    ): AiEditSaveResult = save(candidate, onRefresh = { gallery.refresh() }, onFocus = onFocus)

    /**
     * Reopen a journal after process death. A published item is never removed;
     * only this app's still-pending, exact-name row can be deleted. No original
     * URI is ever part of the journal's cleanup target.
     */
    fun recoverInterruptedSaves() {
        for (pending in lineageStore.pending()) {
            val row = pendingItem(pending)
            if (row != null && row.second) {
                check(resolver.delete(row.first, null, null) == 1) {
                    "Could not reclaim interrupted AI edit"
                }
                lineageStore.finishIntent(pending.token, discardLineage = true)
            } else {
                lineageStore.finishIntent(pending.token, discardLineage = false)
            }
        }
    }

    private fun pendingItem(pending: AiEditLineageStore.PendingSave): Pair<Uri, Boolean>? {
        // Inspect only exact folder/name on the original volume, including
        // pending rows. OWNER_PACKAGE_NAME prevents deleting other apps' data.
        val scoped = MediaStore.setIncludePending(collection(pending.volume))
        val projection = arrayOf(MediaStore.Images.Media._ID,
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME)
        return resolver.query(scoped, projection,
            MediaStore.Images.Media.RELATIVE_PATH + " = ? AND " +
                MediaStore.Images.Media.DISPLAY_NAME + " = ?",
            arrayOf(pending.relativePath, pending.displayName), null)?.use { cursor ->
            var found: Pair<Uri, Boolean>? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(2) != context.packageName) continue
                val uri = ContentUris.withAppendedId(collection(pending.volume), cursor.getLong(0))
                if (pending.insertedUri != null && uri.toString() != pending.insertedUri) continue
                found = uri to (cursor.getInt(1) != 0)
                break
            }
            found
        } ?: error("Cannot inspect interrupted MediaStore saves")
    }

    /**
     * One-off sanitized upload source for AIE-5. Returns an app-cache JPEG
     * owned by the caller, which MUST delete it in finally after the request.
     * The re-encode carries no EXIF at all (including GPS/camera/person tags).
     * Never silently sends bytes to any provider.
     */
    fun createSanitizedUploadCopy(original: Uri): File {
        sourceFacts(original) // reject non-selected/non-MediaStore inputs
        val file = File.createTempFile("atlas-ai-upload-", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { compressPixels(original, AiEditImageFormat.JPEG, it) }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    companion object {
        private val saveMutex = Mutex()
    }
}
