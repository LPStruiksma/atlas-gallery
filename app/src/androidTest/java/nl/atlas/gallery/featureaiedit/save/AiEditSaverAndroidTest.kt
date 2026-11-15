package nl.atlas.gallery.featureaiedit.save

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Only synthetic 12x8 pixels; no owner media, network or credentials. */
@RunWith(AndroidJUnit4::class)
class AiEditSaverAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = ArrayList<Uri>()
    private val folder = "Pictures/ATLAS-AI-Synthetic/"
    private val store = AiEditLineageStore(context)

    @After fun cleanup() {
        for (uri in created.reversed()) runCatching { resolver.delete(uri, null, null) }
    }

    private fun image(name: String, color: Int, withExif: Boolean = false): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        created += uri
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            resolver.openOutputStream(uri, "w")!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
        if (withExif) {
            resolver.openFileDescriptor(uri, "rw")!!.use {
                val exif = ExifInterface(it.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,21/1,0/1")
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,54/1,0/1")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                exif.saveAttributes()
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun recipe(original: Uri, candidate: Uri, keepGps: Boolean = false) =
        AiEditCandidate(
            originalUri = original, candidateUri = candidate,
            originalRevision = "synthetic-r1",
            provider = "synthetic-provider", model = "pixel-only", modelVersion = "test-v1",
            prompt = "Make these synthetic pixels blue",
            settingsJson = "{\"temperature\":0}",
            format = AiEditImageFormat.JPEG, keepGps = keepGps, createdAtMillis = 123456L
        )

    private fun bytes(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun exif(uri: Uri): ExifInterface =
        resolver.openInputStream(uri)!!.use { ExifInterface(it) }

    private fun value(uri: Uri, field: String): String = resolver.query(uri,
        arrayOf(field), null, null, null)!!.use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    @Test fun acceptedEditIsANewPublishedCopyWithLocalLineageAndNoGpsByDefault() = runBlocking {
        val original = image("source-" + UUID.randomUUID() + ".jpg", Color.RED, withExif = true)
        val candidate = image("candidate-" + UUID.randomUUID() + ".jpg", Color.BLUE, withExif = true)
        val before = bytes(original)
        var refreshed = 0
        var focused: Uri? = null
        val result = AiEditSaver(context, store).save(recipe(original, candidate),
            onRefresh = { refreshed++ }, onFocus = { focused = it })
        created += result.uri
        assertArrayEquals("Original MediaStore bytes must never change", before, bytes(original))
        assertNotEquals(original, result.uri)
        assertEquals(folder, value(result.uri, MediaStore.Images.Media.RELATIVE_PATH))
        assertTrue(value(result.uri, MediaStore.Images.Media.DISPLAY_NAME).endsWith("_AI_1.jpg"))
        assertEquals("0", value(result.uri, MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/jpeg", value(result.uri, MediaStore.Images.Media.MIME_TYPE))
        val editedExif = exif(result.uri)
        assertEquals("2020:01:02 03:04:05",
            editedExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(editedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("ATLAS AI Edit", editedExif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("synthetic-provider/pixel-only",
            editedExif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals(1, refreshed)
        assertEquals(result.uri, focused)
        assertEquals(original.toString(), store.get(result.uri.toString())!!.originalUri)
    }

    @Test fun failedPublishDeletesPendingRowAndRollsBackLineage() = runBlocking {
        val original = image("failure-" + UUID.randomUUID() + ".jpg", Color.RED)
        val candidate = image("candidate-" + UUID.randomUUID() + ".jpg", Color.BLUE)
        var inserted: Uri? = null
        val saver = AiEditSaver(context, store, afterInsert = {
            inserted = it
            throw IllegalStateException("synthetic post-insert failure")
        })
        try {
            saver.save(recipe(original, candidate))
            fail("Expected failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("synthetic"))
        }
        val uri = requireNotNull(inserted)
        assertNull("Failed copy must not remain in MediaStore",
            resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            })
        assertNull(store.get(uri.toString()))
    }

    @Test fun interruptedSaveBeforeRecordInsertedReclaimsOnlyTokenNamedRow() {
        val original = image("original-" + UUID.randomUUID() + ".jpg", Color.RED)
        val before = bytes(original)
        val volume = value(original, MediaStore.Images.Media.VOLUME_NAME)
        val finalName = "interrupted-" + UUID.randomUUID() + "_AI_1.jpg"
        val token = UUID.randomUUID().toString()
        val temporaryName = AiEditNaming.pendingName(token, AiEditImageFormat.JPEG)
        store.registerIntent(token, volume, folder, temporaryName)
        val collection = MediaStore.Images.Media.getContentUri(volume)
        fun insertPending(name: String): Uri =
            requireNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })).also { created += it }

        // An unrelated app-owned row occupies the desired *final* filename.
        // Crash after insert but before recordInserted: only the token name is
        // durable, not the inserted URI. Recovery must delete our row alone.
        val unrelated = insertPending(finalName)
        val interrupted = insertPending(temporaryName)
        AiEditSaver(context, store).recoverInterruptedSaves()

        assertNull("Interrupted token-named row must be reclaimed",
            resolver.query(MediaStore.setIncludePending(interrupted),
                arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            })
        assertEquals("Unrelated app-owned pending image must survive", "1",
            resolver.query(MediaStore.setIncludePending(unrelated),
                arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            })
        assertArrayEquals(before, bytes(original))
        assertFalse(store.pending().any { it.token == token })
    }

    @Test fun preInsertCrashCannotDeleteLegacySameNamePendingImage() {
        val original = image("legacy-original-" + UUID.randomUUID() + ".jpg", Color.RED)
        val volume = value(original, MediaStore.Images.Media.VOLUME_NAME)
        val filename = "unrelated-" + UUID.randomUUID() + "_AI_1.jpg"
        val token = UUID.randomUUID().toString()
        // A legacy intent records only an ordinary output name. No inserted
        // URI is durable, so there is no proof a same-name row belongs to it.
        store.registerIntent(token, volume, folder, filename)
        val unrelated = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(volume), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += unrelated
        AiEditSaver(context, store).recoverInterruptedSaves()
        assertEquals("A name-only legacy journal must not delete unrelated media", "1",
            resolver.query(MediaStore.setIncludePending(unrelated),
                arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            })
        assertFalse(store.pending().any { it.token == token })
    }


    @Test fun recoveryFindsRecordedUriAfterPublishRenameAndKeepsLineage() {
        val original = image("published-original-" + UUID.randomUUID() + ".jpg", Color.RED)
        val volume = value(original, MediaStore.Images.Media.VOLUME_NAME)
        val token = UUID.randomUUID().toString()
        val temporaryName = AiEditNaming.pendingName(token, AiEditImageFormat.JPEG)
        val finalName = "published-" + UUID.randomUUID() + "_AI_1.jpg"
        store.registerIntent(token, volume, folder, temporaryName)
        val published = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(volume), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, temporaryName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += published
        store.recordInserted(token, published.toString())
        store.put(AiEditLineage(
            editedUri = published.toString(), originalUri = original.toString(),
            originalRevision = "synthetic", provider = "synthetic",
            model = "synthetic", modelVersion = "test", prompt = "synthetic",
            settingsJson = "{}", createdAtMillis = 123L, candidateParentUri = null
        ))
        assertEquals(1, resolver.update(published, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null))
        AiEditSaver(context, store).recoverInterruptedSaves()
        assertEquals("0", value(published, MediaStore.Images.Media.IS_PENDING))
        assertEquals(finalName, value(published, MediaStore.Images.Media.DISPLAY_NAME))
        assertNotNull("Published lineage must survive final-name rename",
            store.get(published.toString()))
        assertFalse(store.pending().any { it.token == token })
    }

    @Test fun recoveryAfterRowDeletionAlsoRemovesDanglingLocalLineage() {
        val token = UUID.randomUUID().toString()
        val original = image("deleted-source-" + UUID.randomUUID() + ".jpg", Color.RED)
        val volume = value(original, MediaStore.Images.Media.VOLUME_NAME)
        val filename = "deleted_AI_1.jpg"
        store.registerIntent(token, volume, folder, filename)
        val pending = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(volume),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += pending
        store.recordInserted(token, pending.toString())
        store.put(AiEditLineage(
            editedUri = pending.toString(), originalUri = original.toString(),
            originalRevision = "synthetic", provider = "synthetic",
            model = "synthetic", modelVersion = "test", prompt = "synthetic",
            settingsJson = "{}", createdAtMillis = 123L, candidateParentUri = null
        ))
        assertEquals(1, resolver.delete(pending, null, null))
        AiEditSaver(context, store).recoverInterruptedSaves()
        assertNull("No lineage may outlive a deleted interrupted row",
            store.get(pending.toString()))
    }

    @Test fun explicitKeepGpsCopiesOnlySourceCoordinates() = runBlocking {
        val original = image("gps-source-" + UUID.randomUUID() + ".jpg", Color.RED, withExif = true)
        val candidate = image("gps-candidate-" + UUID.randomUUID() + ".jpg", Color.BLUE)
        val saved = AiEditSaver(context, store).save(
            recipe(original, candidate, keepGps = true))
        created += saved.uri
        assertEquals(exif(original).getAttribute(ExifInterface.TAG_GPS_LATITUDE),
            exif(saved.uri).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("ATLAS AI Edit",
            exif(saved.uri).getAttribute(ExifInterface.TAG_SOFTWARE))
    }

    @Test fun uploadCopyRemovesPersonalMetadataBeforeNetworkAdaptersReceiveIt() {
        val original = image("upload-" + UUID.randomUUID() + ".jpg", Color.RED, withExif = true)
        val temporary = AiEditSaver(context, store).createSanitizedUploadCopy(original)
        try {
            assertTrue(temporary.length() > 0)
            val stripped = ExifInterface(temporary.absolutePath)
            assertNull(stripped.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(stripped.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        } finally {
            assertTrue(temporary.delete())
        }
    }
}
