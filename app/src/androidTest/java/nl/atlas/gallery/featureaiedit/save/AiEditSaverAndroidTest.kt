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
            format = AiEditImageFormat.PNG, keepGps = keepGps, createdAtMillis = 123456L
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
        assertTrue(value(result.uri, MediaStore.Images.Media.DISPLAY_NAME).endsWith("_AI_1.png"))
        assertEquals("0", value(result.uri, MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/png", value(result.uri, MediaStore.Images.Media.MIME_TYPE))
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

    @Test fun interruptedSaveJournalIsRecoveredWithoutTouchingOriginal() {
        val original = image("original-" + UUID.randomUUID() + ".jpg", Color.RED)
        val before = bytes(original)
        val fileName = "interrupted_AI_1.jpg"
        val volume = value(original, MediaStore.Images.Media.VOLUME_NAME)
        val token = UUID.randomUUID().toString()
        store.registerIntent(token, volume, folder, fileName)
        val pending = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(volume),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += pending
        store.recordInserted(token, pending.toString())
        AiEditSaver(context, store).recoverInterruptedSaves()
        assertNull(resolver.query(pending, arrayOf(MediaStore.Images.Media._ID),
            null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null })
        assertArrayEquals(before, bytes(original))
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
