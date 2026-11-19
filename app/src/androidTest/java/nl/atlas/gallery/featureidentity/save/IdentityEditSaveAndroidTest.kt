package nl.atlas.gallery.featureidentity.save

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import nl.atlas.gallery.featureidentity.engine.EngineLoadResult
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.FakeIdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityEditRequest
import nl.atlas.gallery.featureidentity.engine.IdentityEngineResult
import nl.atlas.gallery.featureidentity.engine.IdentityPreferences
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.IdentityVariation
import nl.atlas.gallery.featureidentity.engine.TargetFace
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

    private fun image(name: String, color: Int, width: Int, height: Int): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += uri
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            try { bitmap.eraseColor(color); resolver.openOutputStream(uri)!!.use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null))
        return uri
    }

    /** Publishes [png] as a MediaStore row: the saver accepts only content:// candidates. */
    private fun pngRow(name: String, png: ByteArray): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(png) }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null))
        return uri
    }

    private fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun decodedPixels(uri: Uri): Triple<Int, Int, IntArray> {
        val bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Triple(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }

    @Test fun fakeEngineCandidatePublishesCopyWithPrivateIdentityLineage() = runBlocking<Unit> {
        val width = 64
        val height = 48
        val source = image("fictional-source.png", Color.RED, width, height)
        val originalBytes = bytes(source)

        // Run the real synthetic engine on the source's pixels and save exactly what it wrote.
        val engine = FakeIdentityEditEngine()
        val variation = IdentityVariation(42L, listOf(0), 0.85)
        val request = IdentityEditRequest(
            source = EditImage("synthetic-source", width, height, ImageFormat.PNG, List(width * height) { Color.RED }),
            target = TargetFace(FaceBox(16, 8, 48, 40), listOf(
                FacePoint(24f, 18f), FacePoint(40f, 18f), FacePoint(32f, 26f),
                FacePoint(26f, 34f), FacePoint(38f, 34f))),
            references = IdentityReferenceSet("0123456789abcdef0123456789abcdef",
                "fedcba9876543210fedcba9876543210", listOf(EditImage("synthetic-ref-a", 8, 8, ImageFormat.PNG))),
            mode = IdentityEditMode.FACE,
            preferences = IdentityPreferences(),
            variation = variation
        )
        val workspace = File(context.cacheDir, "identity-save-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val warm = (engine.load() as EngineLoadResult.Loaded).engine
            val result = warm.use { it.generate(request, workspace) { false } } as IdentityEngineResult.Success
            val candidateFile = requireNotNull(result.file)
            val candidate = IdentityCandidate(
                "candidate-0", 0, result.image, candidateFile, variation, result.fingerprint,
                engine.id, engine.version, engine.capabilities().route, request.mode
            )
            val enginePixels = result.image.pixels.toIntArray()
            // The engine's own PNG bytes, unchanged, behind a content URI (AiEditCandidate requires one).
            val candidateUri = pngRow("fictional-candidate.png", candidateFile.readBytes())
            // The edit region really differs from the source, so a pixel match below is meaningful.
            assertTrue(enginePixels.any { it != Color.RED })

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
            // The published copy is the accepted engine output, pixel for pixel.
            val (savedWidth, savedHeight, savedPixels) = decodedPixels(saved.uri)
            assertEquals(width, savedWidth)
            assertEquals(height, savedHeight)
            assertArrayEquals(enginePixels, savedPixels)

            val identity = requireNotNull(AiEditLineageStore(context).get(saved.uri.toString())!!.identity)
            assertEquals("face", identity.kind)
            assertEquals("atlas-fake-identity", identity.engineId)
            assertEquals(42L, identity.seed)
            resolver.openInputStream(saved.uri)!!.use { stream ->
                val exif = ExifInterface(stream)
                assertEquals("ATLAS AI Edit", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                assertEquals("ATLAS AI identity edit", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
            }
        } finally {
            workspace.deleteRecursively()
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
