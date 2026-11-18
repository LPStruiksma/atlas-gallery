package nl.atlas.gallery.featureidentity.save

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.save.AiEditLineageStore
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityVariation
import nl.atlas.gallery.featureidentity.engine.PerceptualFingerprint
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic pixels and opaque fictional ids only. */
@RunWith(AndroidJUnit4::class)
class IdentityEditSaveAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val folder = "Pictures/ATLAS-Identity-Synthetic/"

    @After fun cleanup() { created.reversed().forEach { resolver.delete(it, null, null) } }

    private fun image(name: String, color: Int): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += uri
        Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            try { bitmap.eraseColor(color); resolver.openOutputStream(uri)!!.use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null))
        return uri
    }

    private fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test fun fakeEngineCandidatePublishesCopyWithPrivateIdentityLineage() = runBlocking {
        val source = image("fictional-source.png", Color.RED)
        val candidateUri = image("fictional-candidate.png", Color.BLUE)
        val originalBytes = bytes(source)
        val candidate = IdentityCandidate(
            "candidate-0", 0, EditImage("synthetic", 12, 8, ImageFormat.PNG), null,
            IdentityVariation(42L, listOf(0), 0.85), PerceptualFingerprint(List(64) { it }),
            "atlas-fake-identity", "1", EngineRoute.LOCAL, IdentityEditMode.FACE
        )
        val saved = IdentityEditSave(context).save(IdentityEditSave.Request(
            source, candidateUri, "synthetic-r1", candidate,
            "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210"
        ))
        created += saved.uri
        assertArrayEquals(originalBytes, bytes(source))
        assertNotEquals(source, saved.uri)
        assertEquals("0", resolver.query(saved.uri,
            arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        val identity = requireNotNull(AiEditLineageStore(context).get(saved.uri.toString())!!.identity)
        assertEquals("face", identity.kind)
        assertEquals(42L, identity.seed)
        resolver.openInputStream(saved.uri)!!.use { stream ->
            val exif = ExifInterface(stream)
            assertEquals("ATLAS AI Edit", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
            assertEquals("ATLAS AI identity edit", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        }
    }

    @Test fun previousSchemaMigratesAndStillReadsWithNullIdentity() {
        val file = File(context.cacheDir, "identity-lineage-migration-${System.nanoTime()}.sqlite")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""CREATE TABLE edit_lineage(edited_uri TEXT PRIMARY KEY NOT NULL,
                    original_uri TEXT NOT NULL, original_revision TEXT NOT NULL,
                    provider TEXT NOT NULL, model TEXT NOT NULL, model_version TEXT NOT NULL,
                    prompt TEXT NOT NULL, settings_json TEXT NOT NULL, created_at INTEGER NOT NULL,
                    candidate_parent_uri TEXT)""")
                db.execSQL("""INSERT INTO edit_lineage VALUES
                    ('content://media/edited','content://media/source','r1','old-provider',
                    'old-model','v1','','{}',7,NULL)""")
            }
            val legacy = requireNotNull(AiEditLineageStore(context, file).get("content://media/edited"))
            assertEquals("old-provider", legacy.provider)
            assertNull(legacy.identity)
        } finally {
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
