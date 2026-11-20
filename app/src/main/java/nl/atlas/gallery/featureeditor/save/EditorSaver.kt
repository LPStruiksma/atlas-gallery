package nl.atlas.gallery.featureeditor.save

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.atlas.gallery.clearViewerMetadataCache
import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureaiedit.save.AiEditXmp
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID

internal sealed class EditorSaveResult {
    data class Saved(
        val uri: Uri,
        val displayName: String,
        val mode: SaveMode,
        val format: AiEditImageFormat,
        val clip: ClipOutcome,
        val hdr: HdrOutcome,
        /** The source's descriptive XMP was carried into the saved file. */
        val xmpCarried: Boolean,
    ) : EditorSaveResult()

    /**
     * Android requires the user's consent to change a photo another app created
     * (`MediaStore.createWriteRequest`, Android 11+; `RecoverableSecurityException` on 10).
     * Launch [request] and call Save again after RESULT_OK. Nothing was written.
     */
    data class NeedsWriteConsent(val request: IntentSender) : EditorSaveResult()

    /** Save over this source would change its format (HEIF and similar): offer Save as copy. */
    data class CopyRequired(val sourceMime: String, val copyFormat: AiEditImageFormat) : EditorSaveResult()
}

internal sealed class RevertResult {
    object Reverted : RevertResult()
    /** No Atlas Save is recorded for this photo (never edited, or a copy). */
    object NothingToRevert : RevertResult()
    /** The photo changed outside Atlas since the last Save; call again with `force = true`. */
    object ChangedOutside : RevertResult()
    data class NeedsWriteConsent(val request: IntentSender) : RevertResult()
}

/** What the editor opens when the user reopens an Atlas-edited photo. */
internal data class ReopenedEdit(
    val recipe: EditRecipe,
    /** Save: the original bytes kept before the first Save (decode this, not the MediaStore item). */
    val originalFile: File?,
    /** Save as copy: the source photo the recipe applies to. */
    val baseUri: Uri?,
)

/**
 * EDIT-3 (#633): the editor's one save path. **Save** writes the edited picture over the photo
 * through MediaStore after keeping its original bytes and the recipe in app-private storage;
 * **Save as copy** publishes a new item next to it; **Revert** writes the original back byte for
 * byte. See `docs/editor/SAVE.md` for the full contract, EXIF/XMP, Ultra HDR, HEIF and motion
 * photo rules.
 *
 * Never requests a permission, never touches the manifest, never writes a photo without the
 * platform's own consent. Call from a coroutine; IO runs on [Dispatchers.IO], callbacks on Main.
 */
internal class EditorSaver(
    private val context: Context,
    private val store: EditorSaveStore = EditorSaveStore(File(context.noBackupFilesDir, "editor-save")),
    /** Deterministic fault injection for instrumented tests: runs after the journal, before MediaStore. */
    private val beforeMediaWrite: (Uri) -> Unit = {},
) {
    private val resolver: ContentResolver = context.contentResolver

    private data class SourceFacts(
        val name: String, val relativePath: String, val volume: String,
        val mime: String, val dateTaken: Long?,
    )

    // ------------------------------------------------------------------ public API

    suspend fun save(
        source: Uri,
        image: EditedImageSource,
        recipe: EditRecipe,
        mode: SaveMode,
        keepLocation: Boolean = true,
        onRefresh: () -> Unit = {},
        onSaved: (EditorSaveResult.Saved) -> Unit = {},
    ): EditorSaveResult {
        val result = withContext(Dispatchers.IO) {
            mutex.withLock { saveBlocking(source, image, recipe, mode, keepLocation) }
        }
        if (result is EditorSaveResult.Saved) withContext(Dispatchers.Main.immediate) {
            clearViewerMetadataCache()
            onRefresh()
            onSaved(result)
        }
        return result
    }

    suspend fun revert(
        uri: Uri, force: Boolean = false,
        onRefresh: () -> Unit = {}, onReverted: (Uri) -> Unit = {},
    ): RevertResult {
        val result = withContext(Dispatchers.IO) { mutex.withLock { revertBlocking(uri, force) } }
        if (result == RevertResult.Reverted) withContext(Dispatchers.Main.immediate) {
            clearViewerMetadataCache()
            onRefresh()
            onReverted(uri)
        }
        return result
    }

    /** The recipe to restore when the editor opens [uri], or null for a photo Atlas has not saved. */
    suspend fun reopen(uri: Uri): ReopenedEdit? = withContext(Dispatchers.IO) {
        mutex.withLock { reopenBlocking(uri) }
    }

    /** True when Revert is available for [uri] (an unchanged Atlas Save). */
    suspend fun canRevert(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val e = store.entry(uri.toString())
            e != null && e.kind == EditorSaveStore.Kind.IN_PLACE &&
                e.state == EditorSaveStore.State.COMMITTED &&
                runCatching { EditorSaveStore.sha256(read(uri)) == e.savedSha }.getOrDefault(false)
        }
    }

    /** Finish or roll back saves interrupted by process death. Safe to call at any time. */
    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock { recoverBlocking() } }

    // ------------------------------------------------------------------ save

    private fun saveBlocking(
        source: Uri, image: EditedImageSource, recipe: EditRecipe,
        mode: SaveMode, keepLocation: Boolean,
    ): EditorSaveResult {
        recoverBlocking()
        val facts = sourceFacts(source)
        val output = OutputFormat.forSource(facts.mime, Build.VERSION.SDK_INT)
        if (mode == SaveMode.IN_PLACE) {
            if (!output.inPlace) return EditorSaveResult.CopyRequired(facts.mime, output.format)
            writeConsent(source)?.let { return EditorSaveResult.NeedsWriteConsent(it) }
        }
        val sourceBytes = read(source)
        val encoded = encode(sourceBytes, facts, image, recipe, output.format, keepLocation)
        return when (mode) {
            SaveMode.IN_PLACE -> saveInPlace(source, facts, sourceBytes, encoded, recipe, output.format)
            SaveMode.COPY -> saveCopy(source, facts, sourceBytes, encoded, recipe, output.format)
        }
    }

    private class Encoded(
        val bytes: ByteArray, val clip: ClipOutcome, val hdr: HdrOutcome, val xmpCarried: Boolean,
    )

    private fun saveInPlace(
        uri: Uri, facts: SourceFacts, current: ByteArray, encoded: Encoded,
        recipe: EditRecipe, format: AiEditImageFormat,
    ): EditorSaveResult.Saved {
        val key = uri.toString()
        store.prepareInPlace(key, current, encoded.bytes, recipe)
        try {
            beforeMediaWrite(uri)
        } catch (failure: Throwable) {
            store.abortPending(key) // MediaStore untouched
            throw failure
        }
        // From here on the journal can always finish this write (recoverBlocking).
        writeVerified(uri, encoded.bytes)
        store.commitInPlace(key)
        return EditorSaveResult.Saved(uri, facts.name, SaveMode.IN_PLACE, format,
            encoded.clip, encoded.hdr, encoded.xmpCarried)
    }

    private fun saveCopy(
        source: Uri, facts: SourceFacts, sourceBytes: ByteArray, encoded: Encoded,
        recipe: EditRecipe, format: AiEditImageFormat,
    ): EditorSaveResult.Saved {
        val name = SavePolicy.copyName(facts.name, format.extension, existingNames(facts))
        val temporary = SavePolicy.pendingName(UUID.randomUUID().toString(), format.extension)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, temporary)
            put(MediaStore.Images.Media.RELATIVE_PATH, facts.relativePath)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (facts.dateTaken != null && facts.dateTaken > 0L)
                put(MediaStore.Images.Media.DATE_TAKEN, facts.dateTaken)
        }
        var inserted: Uri? = null
        var published = false
        try {
            inserted = resolver.insert(MediaStore.Images.Media.getContentUri(facts.volume), values)
                ?: error("MediaStore refused the edited copy")
            beforeMediaWrite(inserted)
            writeVerified(inserted, encoded.bytes)
            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            check(resolver.update(inserted, publish, null, null) == 1) { "MediaStore did not publish the copy" }
            published = true
            store.recordCopy(inserted.toString(), source.toString(),
                EditorSaveStore.sha256(sourceBytes), EditorSaveStore.sha256(encoded.bytes), recipe)
            val finalName = displayName(inserted) ?: name
            return EditorSaveResult.Saved(inserted, finalName, SaveMode.COPY, format,
                encoded.clip, encoded.hdr, encoded.xmpCarried)
        } catch (failure: Throwable) {
            // Only the app-owned, token-named, still-pending row is ever deleted.
            if (!published && inserted != null) runCatching { resolver.delete(inserted, null, null) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    // ------------------------------------------------------------------ encode

    private fun encode(
        sourceBytes: ByteArray, facts: SourceFacts, image: EditedImageSource, recipe: EditRecipe,
        format: AiEditImageFormat, keepLocation: Boolean,
    ): Encoded {
        val sourceIsJpeg = JpegMetadata.isJpeg(sourceBytes)
        var hdr = HdrOutcome.NONE
        val pixels = ByteArrayOutputStream()
        var bitmap = image.base
        var tempCopy: Bitmap? = null
        try {
            if (image.hasGainmap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (format == AiEditImageFormat.JPEG) {
                    if (!bitmap.isMutable) { tempCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true); bitmap = tempCopy }
                    bitmap.gainmap = image.gainmap()
                    hdr = HdrOutcome.KEPT
                } else hdr = HdrOutcome.DROPPED_FORMAT
            }
            val compress = when (format) {
                AiEditImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                AiEditImageFormat.PNG -> Bitmap.CompressFormat.PNG
                AiEditImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSLESS else error("Lossless WebP needs Android 11")
            }
            check(bitmap.compress(compress, JPEG_QUALITY, pixels)) { "Image encoding failed" }
        } finally {
            tempCopy?.recycle()
        }

        // EXIF through the platform writer (offset-safe for MPF/Ultra HDR) on a private temp file.
        val temp = File.createTempFile("atlas-edit-", "." + format.extension, context.cacheDir)
        var bytes: ByteArray
        try {
            temp.writeBytes(pixels.toByteArray())
            if (format == AiEditImageFormat.JPEG || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                copyExif(sourceBytes, temp, image.base, keepLocation)
            }
            bytes = temp.readBytes()
        } finally {
            temp.delete()
        }

        // XMP: carry the source's descriptive packet without its old layout; add a clip descriptor.
        val sourceXmp = sourceXmp(sourceBytes, facts.mime)?.let(JpegMetadata::sanitizeForCarry)
        val carryBody = sourceXmp?.let(JpegMetadata::descriptions)
        val motion = if (sourceIsJpeg) JpegMetadata.motion(sourceBytes) else JpegMetadata.Motion.None
        var clip = when {
            motion is JpegMetadata.Motion.None -> ClipOutcome.NONE
            motion is JpegMetadata.Motion.Unsupported -> ClipOutcome.DROPPED_UNSUPPORTED
            format != AiEditImageFormat.JPEG -> ClipOutcome.DROPPED_FORMAT
            !SavePolicy.isGeometryOnly(recipe) -> ClipOutcome.DROPPED_EDITED
            else -> ClipOutcome.KEPT
        }
        var xmpCarried = false
        if (format == AiEditImageFormat.JPEG) {
            val encoderXmp = JpegMetadata.readXmp(bytes) // Ultra HDR writes its own packet
            var packet = when {
                encoderXmp != null -> if (carryBody != null)
                    JpegMetadata.appendDescriptions(encoderXmp, carryBody) ?: encoderXmp else encoderXmp
                carryBody != null -> JpegMetadata.packet(carryBody)
                else -> null
            }
            if (packet != null && carryBody != null && packet != encoderXmp) xmpCarried = true
            val clipMotion = motion as? JpegMetadata.Motion.Clip
            if (clip == ClipOutcome.KEPT && clipMotion != null) {
                val withMotion = JpegMetadata.addMotionToPacket(
                    packet ?: JpegMetadata.packet(""), clipMotion.length, clipMotion.presentationTimestampUs)
                if (withMotion == null) clip = ClipOutcome.DROPPED_FORMAT else packet = withMotion
            }
            if (packet != null && packet != encoderXmp) {
                val rewritten = if (encoderXmp != null) JpegMetadata.replaceXmp(bytes, packet)
                    else JpegMetadata.insertXmp(bytes, packet)
                if (rewritten != null) bytes = rewritten else {
                    // Could not place it safely (size, or after MPF): keep the encoder's file.
                    xmpCarried = false
                    if (clip == ClipOutcome.KEPT) clip = ClipOutcome.DROPPED_FORMAT
                }
            }
            if (clip == ClipOutcome.KEPT && clipMotion != null) {
                bytes += sourceBytes.copyOfRange(sourceBytes.size - clipMotion.length, sourceBytes.size)
            }
        } else if (carryBody != null) {
            bytes = AiEditXmp.embed(bytes, format, JpegMetadata.packet(carryBody))
            xmpCarried = true
        }
        return Encoded(bytes, clip, hdr, xmpCarried)
    }

    private fun sourceXmp(bytes: ByteArray, mime: String): String? = runCatching {
        when {
            JpegMetadata.isJpeg(bytes) -> JpegMetadata.readXmp(bytes)
            mime == "image/png" -> AiEditXmp.read(bytes, AiEditImageFormat.PNG)
            mime == "image/webp" -> AiEditXmp.read(bytes, AiEditImageFormat.WEBP)
            else -> ExifInterface(bytes.inputStream()).getAttribute(ExifInterface.TAG_XMP)
        }
    }.getOrNull()

    /**
     * Every EXIF tag the platform reader knows is carried over, except those that describe the
     * old pixel layout (orientation, dimensions, thumbnail, strip/tile offsets, subject area)
     * and the maker note, whose internal offsets break when relocated. GPS follows
     * [keepLocation]. Orientation becomes normal: rotation is in the pixels.
     */
    private fun copyExif(sourceBytes: ByteArray, target: File, base: Bitmap, keepLocation: Boolean) {
        val from = runCatching { ExifInterface(sourceBytes.inputStream()) }.getOrNull()
        val to = ExifInterface(target.absolutePath)
        if (from != null) for (tag in carriedTags) {
            if (!keepLocation && tag.startsWith("GPS")) continue
            val value = from.getAttribute(tag) ?: continue
            runCatching { to.setAttribute(tag, value) }
        }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, base.width.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, base.height.toString())
        to.saveAttributes()
    }

    // ------------------------------------------------------------------ revert / reopen

    private fun revertBlocking(uri: Uri, force: Boolean): RevertResult {
        recoverBlocking()
        val key = uri.toString()
        val e = store.entry(key)
        if (e == null || e.kind != EditorSaveStore.Kind.IN_PLACE ||
            e.state != EditorSaveStore.State.COMMITTED) return RevertResult.NothingToRevert
        val original = store.originalFile(key)
        if (!original.isFile) return RevertResult.NothingToRevert
        if (!force && EditorSaveStore.sha256(read(uri)) != e.savedSha) return RevertResult.ChangedOutside
        writeConsent(uri)?.let { return RevertResult.NeedsWriteConsent(it) }
        val bytes = original.readBytes()
        check(EditorSaveStore.sha256(bytes) == e.originalSha) { "Stored original is damaged" }
        store.markReverting(key)
        writeVerified(uri, bytes)
        store.clear(key)
        return RevertResult.Reverted
    }

    private fun reopenBlocking(uri: Uri): ReopenedEdit? {
        val key = uri.toString()
        val e = store.entry(key) ?: return null
        if (e.state != EditorSaveStore.State.COMMITTED) return null
        val current = runCatching { read(uri) }.getOrNull() ?: return null
        if (EditorSaveStore.sha256(current) != e.savedSha) return null // changed outside Atlas
        val recipe = store.recipe(key) ?: return null
        return when (e.kind) {
            EditorSaveStore.Kind.IN_PLACE -> {
                val original = store.originalFile(key)
                if (!original.isFile) null else ReopenedEdit(recipe, original, null)
            }
            EditorSaveStore.Kind.COPY -> {
                val base = e.sourceUri?.let(Uri::parse) ?: return null
                val sourceBytes = runCatching { read(base) }.getOrNull() ?: return null
                if (EditorSaveStore.sha256(sourceBytes) != e.originalSha) null
                else ReopenedEdit(recipe, null, base)
            }
        }
    }

    // ------------------------------------------------------------------ recovery

    private fun recoverBlocking() {
        for (e in store.entries()) {
            val uri = Uri.parse(e.uri)
            when (e.state) {
                EditorSaveStore.State.COMMITTED -> Unit
                EditorSaveStore.State.WRITING -> runCatching {
                    val current = runCatching { read(uri) }.getOrNull()
                    if (current == null) { store.clear(e.uri); return@runCatching }
                    val sha = EditorSaveStore.sha256(current)
                    when {
                        sha == e.pendingSha -> store.commitInPlace(e.uri)
                        sha == e.savedSha || (e.savedSha.isEmpty() && sha == e.originalSha) ->
                            store.abortPending(e.uri)
                        else -> {
                            // A partial write: finish it from the journal.
                            writeVerified(uri, store.pendingFile(e.uri).readBytes())
                            store.commitInPlace(e.uri)
                        }
                    }
                }
                EditorSaveStore.State.REVERTING -> runCatching {
                    writeVerified(uri, store.originalFile(e.uri).readBytes())
                    store.clear(e.uri)
                }
            }
        }
        reclaimPendingCopies()
    }

    /** Deletes only this app's still-pending, token-named copy rows (a crash mid Save as copy). */
    private fun reclaimPendingCopies() {
        for (volume in MediaStore.getExternalVolumeNames(context)) runCatching {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            val cursor = resolver.query(MediaStore.setIncludePending(collection),
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.OWNER_PACKAGE_NAME),
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                arrayOf(SavePolicy.PENDING_PREFIX + "%"), null) ?: return@runCatching
            val doomed = ArrayList<Uri>()
            cursor.use {
                while (it.moveToNext()) {
                    if (it.getInt(1) != 0 && it.getString(2) == context.packageName)
                        doomed += ContentUris.withAppendedId(collection, it.getLong(0))
                }
            }
            doomed.forEach { resolver.delete(it, null, null) }
        }
    }

    // ------------------------------------------------------------------ MediaStore helpers

    private fun sourceFacts(uri: Uri): SourceFacts {
        check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "Not a MediaStore item" }
        val columns = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            check(c.moveToFirst()) { "The photo is no longer available" }
            val mime = c.getString(3)
            check(mime?.startsWith("image/") == true) { "Editor save needs an image" }
            val volume = c.getString(2)
            check(!volume.isNullOrBlank() && volume in MediaStore.getExternalVolumeNames(context)) {
                "The photo's storage is no longer mounted"
            }
            SourceFacts(
                name = c.getString(0) ?: error("Photo has no name"),
                relativePath = c.getString(1) ?: error("Photo has no folder"),
                volume = volume, mime = mime,
                dateTaken = if (c.isNull(4)) null else c.getLong(4),
            )
        } ?: error("Cannot inspect the photo")
    }

    private fun existingNames(facts: SourceFacts): Set<String> {
        val names = HashSet<String>()
        resolver.query(MediaStore.setIncludePending(MediaStore.Images.Media.getContentUri(facts.volume)),
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            MediaStore.Images.Media.RELATIVE_PATH + " = ?", arrayOf(facts.relativePath), null
        )?.use { while (it.moveToNext()) it.getString(0)?.let(names::add) }
            ?: error("Cannot check the folder for name collisions")
        return names
    }

    private fun displayName(uri: Uri): String? = resolver.query(uri,
        arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun read(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read $uri")

    /** Truncate-and-write, then read back: the item must hold exactly [bytes]. */
    private fun writeVerified(uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
            ?: error("Cannot write the photo")
        check(read(uri).contentEquals(bytes)) { "MediaStore did not keep the written bytes" }
    }

    /** Null when this app may write [uri]; otherwise the platform consent request. */
    private fun writeConsent(uri: Uri): IntentSender? = try {
        resolver.openFileDescriptor(uri, "rw")?.close()
        null
    } catch (e: RecoverableSecurityException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender
        else e.userAction.actionIntent.intentSender
    } catch (e: SecurityException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender
        else throw e
    }

    companion object {
        private val mutex = Mutex()
        private const val JPEG_QUALITY = 95

        private val excludedTags = setOf(
            ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_STRIP_OFFSETS, ExifInterface.TAG_STRIP_BYTE_COUNTS,
            ExifInterface.TAG_ROWS_PER_STRIP, ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_BITS_PER_SAMPLE, ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_SAMPLES_PER_PIXEL, ExifInterface.TAG_PLANAR_CONFIGURATION,
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, ExifInterface.TAG_Y_CB_CR_POSITIONING,
            ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_SUBJECT_AREA, ExifInterface.TAG_SUBJECT_LOCATION,
            ExifInterface.TAG_XMP, ExifInterface.TAG_NEW_SUBFILE_TYPE, ExifInterface.TAG_SUBFILE_TYPE,
            ExifInterface.TAG_DEFAULT_CROP_SIZE, ExifInterface.TAG_DNG_VERSION,
        )

        /** Every public `TAG_*` the platform reader exposes, minus [excludedTags] and raw-format tags. */
        val carriedTags: List<String> by lazy {
            ExifInterface::class.java.fields.filter {
                Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                    it.name.startsWith("TAG_") && !it.name.startsWith("TAG_ORF_") &&
                    !it.name.startsWith("TAG_RW2_") && !it.name.startsWith("TAG_THUMBNAIL_")
            }.mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
                .distinct().filter { it !in excludedTags }
        }
    }
}
