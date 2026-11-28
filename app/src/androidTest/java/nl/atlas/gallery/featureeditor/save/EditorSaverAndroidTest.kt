package nl.atlas.gallery.featureeditor.save

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.EditorReturnState
import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EDIT-3 (#633) on a real MediaStore: synthetic pixels only (quadrant test cards, a generated
 * HEIF asset, a synthetic Ultra HDR gainmap and a fake MP4 clip). No owner media, no network.
 */
@RunWith(AndroidJUnit4::class)
class EditorSaverAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val created = ArrayList<Uri>()
    private val folder = "Pictures/Atlas-Edit-Synthetic/"
    private val storeRoot = File(context.cacheDir, "editor-save-test-" + UUID.randomUUID())
    private val store = EditorSaveStore(storeRoot)
    private fun saver(beforeWrite: (Uri) -> Unit = {}) = EditorSaver(context, store, beforeWrite)

    @After fun cleanup() {
        for (uri in created.reversed()) runCatching { resolver.delete(uri, null, null) }
        storeRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    /** Blue top-left quadrant on red: any rotation, flip or crop is visible in the pixels. */
    private fun card(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
        Canvas(this).drawRect(0f, 0f, w / 2f, h / 2f, Paint().apply { color = Color.BLUE })
    }

    private val descriptiveXmp = "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Synthetic title</rdf:li></rdf:Alt></dc:title>" +
        "</rdf:Description>"

    private fun fakeClip(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }.also {
        it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 0x18
        "ftypmp42".toByteArray().copyInto(it, 4)
    }

    /** JPEG bytes with capture EXIF, optional XMP and optional appended motion clip. */
    private fun jpegBytes(bitmap: Bitmap, xmpBody: String? = descriptiveXmp, clip: ByteArray? = null): ByteArray {
        val f = File.createTempFile("src", ".jpg", context.cacheDir)
        try {
            f.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2021:02:03 04:05:06")
                setAttribute(ExifInterface.TAG_MAKE, "SyntheticCam")
                setAttribute(ExifInterface.TAG_MODEL, "Card-1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,21/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,54/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            var bytes = f.readBytes()
            var body = xmpBody ?: ""
            if (clip != null) body += JpegMetadata.motionDescription(clip.size, 250_000L, withDirectory = true)
            if (body.isNotEmpty()) {
                bytes = JpegMetadata.readXmp(bytes)?.let { existing ->
                    // An encoder packet after MPF cannot grow safely: keep the fixture as encoded.
                    JpegMetadata.replaceXmp(bytes, JpegMetadata.appendDescriptions(existing, body)!!) ?: bytes
                } ?: JpegMetadata.insertXmp(bytes, JpegMetadata.packet(body))!!
            }
            return if (clip != null) bytes + clip else bytes
        } finally { f.delete() }
    }

    private fun insert(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        created += uri
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun exif(uri: Uri) = resolver.openInputStream(uri)!!.use { ExifInterface(it) }
    private fun column(uri: Uri, field: String): String? = resolver.query(
        MediaStore.setIncludePending(uri), arrayOf(field), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }
    private fun decode(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private fun decode(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun isBlue(c: Int) = Color.blue(c) > 150 && Color.red(c) < 100
    private fun isRed(c: Int) = Color.red(c) > 150 && Color.blue(c) < 100

    private fun render(src: Bitmap, recipe: EditRecipe): EditedImageSource =
        EditedImageSource.sdr(EditedImageSource.warpThroughPlan(src, RenderPlan.build(recipe)))

    private fun rotate(w: Int, h: Int) = EditRecipe(w, h, listOf(EditOperation.Rotate90("r1", 1)))
    private fun colour(w: Int, h: Int) = EditRecipe(w, h, listOf(EditOperation.Adjust("a1", AdjustParam.EXPOSURE, 0.3)))

    // ------------------------------------------------------------------ tests

    @Test fun saveInPlaceKeepsOriginalCarriesMetadataAndRevertIsByteIdentical() = runBlocking {
        val src = card(64, 48)
        val uri = insert("save-" + UUID.randomUUID() + ".jpg", "image/jpeg", jpegBytes(src))
        val before = bytes(uri)
        var savedUri: Uri? = null
        var refreshes = 0
        var returnState = EditorReturnState()
        val recipe = rotate(64, 48)
        val result = saver().save(uri, render(src, recipe), recipe, SaveMode.IN_PLACE,
            onRefresh = { refreshes++ },
            onSaved = { savedUri = it.uri; returnState = returnState.savedInPlace(it.uri.toString()) })
        result as EditorSaveResult.Saved
        assertEquals(uri, result.uri)
        assertEquals(uri, savedUri)
        assertEquals(1, refreshes)
        assertTrue("viewer cache key refreshes only this photo", returnState.mustReload(uri.toString()))
        assertEquals(ClipOutcome.NONE, result.clip)
        assertTrue(result.xmpCarried)

        val after = bytes(uri)
        assertFalse(before.contentEquals(after))
        val pixels = decode(uri)
        assertEquals("rotated a quarter turn", 48, pixels.width)
        assertEquals(64, pixels.height)
        assertTrue("blue moved to top-right", isBlue(pixels.getPixel(36, 8)))
        assertTrue(isRed(pixels.getPixel(8, 8)))
        val e = exif(uri)
        assertEquals("2021:02:03 04:05:06", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("SyntheticCam", e.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Card-1", e.getAttribute(ExifInterface.TAG_MODEL))
        assertNotNull("location handling as today: kept", e.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue(JpegMetadata.readXmp(after)!!.contains("Synthetic title"))

        val reopened = saver().reopen(uri)!!
        assertEquals(recipe, reopened.recipe)
        assertArrayEquals("original kept in app-private storage", before, reopened.originalFile!!.readBytes())
        assertTrue(saver().canRevert(uri))

        // Re-edit from the reopened recipe; Revert must still reach the very first original.
        val recipe2 = recipe.copy(operations = recipe.operations + EditOperation.Rotate90("r2", 1))
        saver().save(uri, render(src, recipe2), recipe2, SaveMode.IN_PLACE) as EditorSaveResult.Saved
        assertEquals(recipe2, saver().reopen(uri)!!.recipe)

        var reverted: Uri? = null
        assertEquals(RevertResult.Reverted, saver().revert(uri, onReverted = { reverted = it }))
        assertEquals(uri, reverted)
        assertArrayEquals("Revert restores the original byte for byte", before, bytes(uri))
        assertNull("Revert clears the stored recipe", saver().reopen(uri))
        assertFalse(saver().canRevert(uri))
        assertEquals(RevertResult.NothingToRevert, saver().revert(uri))
    }

    @Test fun saveAsCopyWritesANewItemNextToTheSourceAndLeavesItUntouched() = runBlocking {
        val src = card(64, 48)
        val stem = "copy-" + UUID.randomUUID()
        val uri = insert("$stem.jpg", "image/jpeg", jpegBytes(src))
        val before = bytes(uri)
        val recipe = EditRecipe(64, 48, listOf(EditOperation.Crop("c1", NormRect(0.0, 0.0, 0.5, 1.0))))
        val r = saver().save(uri, render(src, recipe), recipe, SaveMode.COPY) as EditorSaveResult.Saved
        created += r.uri
        assertNotEquals(uri, r.uri)
        assertEquals(SaveMode.COPY, r.mode)
        assertEquals("${stem}_edited.jpg", column(r.uri, MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals(folder, column(r.uri, MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("0", column(r.uri, MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/jpeg", column(r.uri, MediaStore.Images.Media.MIME_TYPE))
        assertArrayEquals("Save as copy never changes the source", before, bytes(uri))
        assertEquals("SyntheticCam", exif(r.uri).getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(32, decode(r.uri).width)

        val reopened = saver().reopen(r.uri)!!
        assertEquals(recipe, reopened.recipe)
        assertEquals(uri, reopened.baseUri)
        assertNull(reopened.originalFile)
        assertEquals("Revert applies to Save, not copies", RevertResult.NothingToRevert, saver().revert(r.uri))
        assertNull("the source itself was never saved over", saver().reopen(uri))

        val second = saver().save(uri, render(src, recipe), recipe, SaveMode.COPY) as EditorSaveResult.Saved
        created += second.uri
        assertEquals("${stem}_edited_2.jpg", column(second.uri, MediaStore.Images.Media.DISPLAY_NAME))
    }

    @Test fun heifSourceIsNeverTranscodedInPlaceAndSavesAsAJpegCopy() = runBlocking {
        val heif = instrumentation.context.assets.open("editor/synthetic-quadrant-64x48.heic").use { it.readBytes() }
        val stem = "heif-" + UUID.randomUUID()
        val uri = insert("$stem.heic", "image/heic", heif)
        val before = bytes(uri)
        val decoded = decode(uri)
        assertEquals(64, decoded.width)
        val recipe = rotate(64, 48)
        val image = render(decoded, recipe)
        val refused = saver().save(uri, image, recipe, SaveMode.IN_PLACE)
        assertEquals(EditorSaveResult.CopyRequired("image/heic", AiEditImageFormat.JPEG), refused)
        assertArrayEquals(before, bytes(uri))

        val copy = saver().save(uri, image, recipe, SaveMode.COPY) as EditorSaveResult.Saved
        created += copy.uri
        assertEquals(AiEditImageFormat.JPEG, copy.format)
        assertEquals("${stem}_edited.jpg", column(copy.uri, MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/jpeg", column(copy.uri, MediaStore.Images.Media.MIME_TYPE))
        val px = decode(copy.uri)
        assertEquals(48, px.width); assertEquals(64, px.height)
        assertTrue(isBlue(px.getPixel(36, 8)))
        assertTrue(isRed(px.getPixel(8, 56)))
        assertArrayEquals("HEIF source unchanged", before, bytes(uri))
    }

    @Test fun ultraHdrGainmapIsKeptAndAlignedAfterRotateAndCrop() = runBlocking {
        assumeTrue("Gainmap API needs Android 14", Build.VERSION.SDK_INT >= 34)
        val w = 256; val h = 192
        val base = card(w, h)
        // Synthetic gainmap: full boost where the base is blue, none elsewhere.
        val contents = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            Canvas(this).drawRect(0f, 0f, 32f, 24f, Paint().apply { color = Color.WHITE })
        }
        base.gainmap = Gainmap(contents).apply { setRatioMax(4f, 4f, 4f); displayRatioForFullHdr = 4f }
        val sourceBytes = jpegBytes(base)
        val sourceDecoded = decode(sourceBytes)
        assertNotNull("the synthetic source is a real Ultra HDR JPEG", sourceDecoded.gainmap)

        val uri = insert("hdr-" + UUID.randomUUID() + ".jpg", "image/jpeg", sourceBytes)
        val before = bytes(uri)
        val recipe = EditRecipe(w, h, listOf(EditOperation.Rotate90("r1", 1),
            EditOperation.Crop("c1", NormRect(0.0, 0.0, 1.0, 0.75))))
        val plan = RenderPlan.build(recipe)
        val image = EditedImageSource.hdr(EditedImageSource.warpThroughPlan(sourceDecoded, plan),
            EditedImageSource.transformGainmap(sourceDecoded.gainmap!!, plan))
        val r = saver().save(uri, image, recipe, SaveMode.IN_PLACE) as EditorSaveResult.Saved
        assertEquals(HdrOutcome.KEPT, r.hdr)

        val saved = decode(uri)
        val gm = need("gainmap present after save", saved.gainmap).gainmapContents
        assertEquals(plan.outputWidth, saved.width)
        assertEquals(plan.outputHeight, saved.height)
        var checked = 0
        for (gy in 0 until 8) for (gx in 0 until 8) {
            val nx = (gx + 0.5) / 8; val ny = (gy + 0.5) / 8
            val classes = listOf(-0.04, 0.0, 0.04).flatMap { dx -> listOf(-0.04, 0.0, 0.04).map { dy ->
                val c = saved.getPixel(((nx + dx).coerceIn(0.0, 0.999) * saved.width).toInt(),
                    ((ny + dy).coerceIn(0.0, 0.999) * saved.height).toInt())
                isBlue(c)
            } }.toSet()
            if (classes.size != 1) continue // near the quadrant edge
            val g = gm.getPixel((nx * gm.width).toInt(), (ny * gm.height).toInt())
            val boost = if (gm.config == Bitmap.Config.ALPHA_8) Color.alpha(g) else Color.red(g)
            if (classes.single()) assertTrue("boost under blue at $nx,$ny was $boost", boost > 128)
            else assertTrue("no boost under red at $nx,$ny was $boost", boost < 128)
            checked++
        }
        assertTrue("enough interior samples ($checked)", checked >= 24)
        assertEquals("SyntheticCam", exif(uri).getAttribute(ExifInterface.TAG_MAKE))
        if (JpegMetadata.readXmp(before)?.contains("Synthetic title") == true)
            assertTrue(JpegMetadata.readXmp(bytes(uri))!!.contains("Synthetic title"))

        assertEquals(RevertResult.Reverted, saver().revert(uri))
        assertArrayEquals(before, bytes(uri))
    }

    private fun <T> need(message: String, value: T?): T { assertNotNull(message, value); return value!! }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }

    @Test fun motionPhotoKeepsItsClipOnGeometryOnlyAndDropsItOnAColourEdit() = runBlocking {
        val src = card(64, 48)
        val clip = fakeClip(4096)
        val stem = "motion-" + UUID.randomUUID()
        val uri = insert("$stem.jpg", "image/jpeg", jpegBytes(src, clip = clip))
        val before = bytes(uri)
        assertEquals(JpegMetadata.Motion.Clip(clip.size, 250_000L), JpegMetadata.motion(before))

        val geo = rotate(64, 48)
        val kept = saver().save(uri, render(src, geo), geo, SaveMode.IN_PLACE) as EditorSaveResult.Saved
        assertEquals(ClipOutcome.KEPT, kept.clip)
        val after = bytes(uri)
        assertArrayEquals("clip re-attached unchanged", clip, after.copyOfRange(after.size - clip.size, after.size))
        assertEquals(JpegMetadata.Motion.Clip(clip.size, 250_000L), JpegMetadata.motion(after))
        assertTrue(JpegMetadata.readXmp(after)!!.contains("Synthetic title"))
        assertEquals(48, decode(uri).width)

        val col = colour(64, 48)
        val dropped = saver().save(uri, render(src, col), col, SaveMode.COPY) as EditorSaveResult.Saved
        created += dropped.uri
        assertEquals(ClipOutcome.DROPPED_EDITED, dropped.clip)
        val copyBytes = bytes(dropped.uri)
        assertEquals(JpegMetadata.Motion.None, JpegMetadata.motion(copyBytes))
        assertFalse(JpegMetadata.readXmp(copyBytes)!!.contains("MotionPhoto"))
        assertTrue("descriptive XMP still carried", JpegMetadata.readXmp(copyBytes)!!.contains("Synthetic title"))

        assertEquals(RevertResult.Reverted, saver().revert(uri))
        assertArrayEquals("Revert restores the motion photo byte for byte", before, bytes(uri))
    }

    @Test fun aSaveThatFailsBeforeMediaStoreLeavesThePhotoAndNoRecord() = runBlocking {
        val src = card(64, 48)
        val uri = insert("fail-" + UUID.randomUUID() + ".jpg", "image/jpeg", jpegBytes(src))
        val before = bytes(uri)
        val recipe = rotate(64, 48)
        val failure = runCatching {
            saver { throw IllegalStateException("synthetic fault") }
                .save(uri, render(src, recipe), recipe, SaveMode.IN_PLACE)
        }.exceptionOrNull()
        assertEquals("synthetic fault", failure?.message)
        assertArrayEquals(before, bytes(uri))
        assertNull(store.entry(uri.toString()))
        assertNull(saver().reopen(uri))
    }

    @Test fun recoveryFinishesAPartialInPlaceWriteFromTheJournal() = runBlocking {
        val src = card(64, 48)
        val uri = insert("crash-" + UUID.randomUUID() + ".jpg", "image/jpeg", jpegBytes(src))
        val before = bytes(uri)
        val recipe = rotate(64, 48)
        val next = jpegBytes(card(48, 64), xmpBody = null)
        // Simulate process death halfway through the MediaStore write.
        store.prepareInPlace(uri.toString(), before, next, recipe)
        resolver.openOutputStream(uri, "wt")!!.use { it.write(next, 0, next.size / 2) }
        saver().recover()
        assertArrayEquals("journal rolled the write forward", next, bytes(uri))
        assertEquals(recipe, saver().reopen(uri)!!.recipe)
        assertEquals(RevertResult.Reverted, saver().revert(uri))
        assertArrayEquals(before, bytes(uri))
    }

    @Test fun aPhotoOwnedByAnotherAppAsksForAndroidsWriteConsent() = runBlocking {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 33)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        val path = "/sdcard/$folder" + "consent-" + UUID.randomUUID() + ".jpg"
        val payload = jpegBytes(card(32, 24))
        // The shell, not Atlas, creates the file. UiAutomation splits commands on spaces with no
        // shell quoting, so stage the bytes in app-specific external storage (readable by the
        // shell) and copy them with plain arguments.
        val staged = File(context.getExternalFilesDir(null), "consent-stage-" + UUID.randomUUID() + ".jpg")
        staged.writeBytes(payload)
        val mkdir = shell("mkdir -p /sdcard/$folder")
        val copy = shell("cp ${staged.absolutePath} $path")
        staged.delete()
        val listing = shell("ls -l $path")
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg")) { _, u -> scanned = u; latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        val uri = need("shell-created photo is in MediaStore (mkdir=$mkdir cp=$copy ls=$listing)", scanned)
        try {
            assertNotEquals(context.packageName, column(uri, MediaStore.Images.Media.OWNER_PACKAGE_NAME))
            val recipe = rotate(32, 24)
            val result = saver().save(uri, render(card(32, 24), recipe), recipe, SaveMode.IN_PLACE)
            assertTrue("expected a consent request, got $result", result is EditorSaveResult.NeedsWriteConsent)
            // Atlas's own read of another app's photo is location-redacted, so compare on disk.
            val onDisk = shell("sha256sum $path").trim().substringBefore(' ')
            assertEquals("nothing written without consent", EditorSaveStore.sha256(payload), onDisk)
            assertNull(store.entry(uri.toString()))
        } finally {
            shell("rm -f $path")
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }
}
