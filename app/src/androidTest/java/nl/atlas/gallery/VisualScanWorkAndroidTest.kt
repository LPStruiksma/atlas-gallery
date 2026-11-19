package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchResult
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurevisual.ImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.store.VisualIndexProgress
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-S1c on API35: the visual index stage inside the People scan worker, with
 * the bundled face models, real app-private FaceIndexStore and VisualIndexStore
 * databases, and [SyntheticFace] drawings keyed by invented MediaStore rows.
 * No real photo is read. Image embeddings come from a deterministic fake
 * [ImageEmbedder] (one test uses the bundled SigLIP model). WorkManager is
 * replaced by a recording scheduler; each worker batch runs through
 * [runPeopleScanBatch], the function the worker runs.
 */
@RunWith(AndroidJUnit4::class)
class VisualScanWorkAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "visual-scan-work-" + UUID.randomUUID())
    private val modelCopy = File(dir, "model-copy/siglip2_b16_224_int8.onnx")
    // A per-test SharedPreferences file survives host recreation without touching
    // the real app's choices or any photo/identity data.
    private val preferenceName = "atlas-visual-test-${dir.name}"
    private val choices = PeopleOptInMemory.preferences(object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            target.getSharedPreferences(preferenceName, mode)
    })
    private val stores = mutableListOf<AutoCloseable>()

    private class RecordingScheduler : PeopleScanScheduling {
        val starts = AtomicInteger()
        val nexts = AtomicInteger()
        val laters = AtomicInteger()
        val cancels = AtomicInteger()
        override fun start() { starts.incrementAndGet() }
        override fun next() { nexts.incrementAndGet() }
        override fun later() { laters.incrementAndGet() }
        override fun cancel() { cancels.incrementAndGet() }
    }

    /** Deterministic 8-d unit vectors; counts opens, embeds and closes. */
    private inner class FakeEmbedder : ImageEmbedder {
        @Volatile var closed = false
        override val modelVersion = FAKE_MODEL
        override val dimension = 8
        override fun embed(bitmap: Bitmap): FloatArray {
            check(!closed) { "Image embedder closed" }
            val n = embeds.incrementAndGet()
            if (n == thermalTripAfterEmbeds) thermal = false
            if (n in failingEmbeds) throw Exception("synthetic runtime failure $n")
            val v = FloatArray(8) { ((bitmap.getPixel(it * 30, 240) ushr (it % 3) * 8) and 255) + 1f }
            val norm = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            return FloatArray(8) { v[it] / norm }
        }
        override fun close() { closed = true; closes.incrementAndGet() }
    }

    @Volatile private var granted = true
    @Volatile private var thermal = true
    @Volatile private var thermalTripAfterEmbeds = -1
    /** Embeds numbered here throw a checked Exception, like OrtException. */
    @Volatile private var failingEmbeds = emptySet<Int>()
    private val embeds = AtomicInteger()
    private val opens = AtomicInteger()
    private val closes = AtomicInteger()
    private val visualDecodes = AtomicInteger()
    private val corruptDecodes = AtomicInteger()
    private val embedders = mutableListOf<FakeEmbedder>()
    private var scheduler = RecordingScheduler()
    private lateinit var faces: FaceIndexStore
    private lateinit var visual: VisualIndexStore

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    private val video = photo(99).copy(
        uri = "content://media/external_primary/video/media/99", isVideo = true)

    /** Photo 3 is "corrupt": its visual decode always fails (#480). */
    private val inventory = (1L..10L).map(::photo) + video

    private fun dbContext(file: File): Context = object : ContextWrapper(target) {
        override fun getDatabasePath(name: String): File = file
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(
            file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }

    private fun synthetic(id: Int): Bitmap = SyntheticFace.bitmap(SyntheticFace.Spec(
        480, 480, 0.4f + 0.02f * id, 0.5f, 0.8f + 0.01f * id))

    /** A fresh host on the same database files: a new process after a restart. */
    private fun host(realSiglip: Boolean = false): PeopleDiscoveryHost {
        dir.mkdirs()
        faces = FaceIndexStore(dbContext(File(dir, "faces.db"))).also { stores += it }
        visual = VisualIndexStore(dbContext(File(dir, "visual.db"))).also { stores += it }
        modelCopy.parentFile!!.mkdirs()
        if (!realSiglip) modelCopy.writeBytes(ByteArray(16))
        return PeopleDiscoveryHost(
            store = faces,
            optInMemory = choices,
            photoGrantCurrent = { granted },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val uri = directory.authorizedUri(lease.media)
                    AndroidBitmapFaceFrame(synthetic(uri.substringAfterLast('/').toInt()))
                }
            },
            power = { PeoplePowerState(charging = true, batteryNotLow = true, storageNotLow = true) },
            scheduler = scheduler,
            visualStore = visual,
            visualEmbedderFactory = {
                opens.incrementAndGet()
                if (realSiglip) SiglipImageEmbedder.open(target)
                else FakeEmbedder().also { synchronized(embedders) { embedders += it } }
            },
            visualDecoderFor = { directory ->
                { media ->
                    // The production gate: only a URI the live grant resolves.
                    val uri = directory.authorizedUri(FaceMediaKey(media.key, media.revision))
                    val id = uri.substringAfterLast('/').toInt()
                    visualDecodes.incrementAndGet()
                    if (id == 3) {
                        corruptDecodes.incrementAndGet()
                        throw IOException("synthetic corrupt image")
                    }
                    synthetic(id)
                }
            },
            visualModelVersion = if (realSiglip) SiglipModel.MODEL_VERSION else FAKE_MODEL,
            visualDimension = if (realSiglip) SiglipModel.DIMENSION else 8,
            deleteVisualModelCopy = {
                if (realSiglip) true else modelCopy.parentFile!!.deleteRecursively()
            },
            visualModelCopyExists = { modelCopy.exists() }
        )
    }

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
        target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun batch(): FaceBatchResult? =
        runBlocking { runPeopleScanBatch(target, thermalAllowed = { thermal }) { false } }

    private fun faceEpoch(host: PeopleDiscoveryHost) = host.displayEpoch("gallery-7")!!

    private fun visualProgress(host: PeopleDiscoveryHost): VisualIndexProgress {
        val status = host.visualStatus(faceEpoch(host))!!
        return VisualIndexProgress(
            queued = status.total!! - status.done!!, running = 0,
            indexed = status.done - status.skipped, skipped = status.skipped)
    }

    private fun count(table: String): Int =
        visual.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { it.moveToFirst(); it.getInt(0) }

    /** Runs batches until both stages are drained; returns how many ran. */
    private fun drain(host: PeopleDiscoveryHost, max: Int = 30): Int {
        var n = 0
        while (n < max) {
            val before = scheduler.nexts.get() + scheduler.laters.get()
            batch()
            n++
            if (scheduler.nexts.get() + scheduler.laters.get() == before) break
        }
        assertEquals(0, visualProgress(host).queued)
        return n
    }

    @Test fun bothStagesRunInOneWorkerBatchAndAnUnreadablePhotoIsSkippedOnce() {
        val host = host()
        // Off by default, and nothing visual runs before the explicit opt-in.
        host.start("gallery-7", inventory)
        val off = host.visualStatus(faceEpoch(host))!!
        assertFalse(off.enabled)
        assertTrue(off.mayEnable)
        batch()
        assertEquals(0, opens.get())
        assertEquals(0, visualDecodes.get())

        assertTrue(host.setVisualIndex(true))
        val first = visualProgress(host)
        assertEquals("images only, never the video", 10, first.total)
        val facesBefore = faces.progress(faceEpoch(host))!!.processed
        batch()
        // One worker batch advanced BOTH stages.
        assertTrue("faces advanced", faces.progress(faceEpoch(host))!!.processed > facesBefore)
        assertTrue("visual advanced", visualProgress(host).done > 0)

        drain(host)
        val done = visualProgress(host)
        assertEquals(9, done.indexed)
        assertEquals(1, done.skipped)
        assertEquals("the corrupt photo was tried once, never re-queued", 1, corruptDecodes.get())
        assertEquals(9, embeds.get())
        assertEquals("one embedder per scan run", 1, opens.get())
        assertTrue("a drained queue frees the embedder", embedders.all { it.closed })
        assertEquals(9, count("vectors"))
        assertEquals(1, count("skips"))
        val f = faces.progress(faceEpoch(host))!!
        assertEquals(0, f.pending + f.running)
        // Drained: the last batch queued nothing more.
        val chained = scheduler.nexts.get() + scheduler.laters.get()
        batch()
        assertEquals(chained, scheduler.nexts.get() + scheduler.laters.get())
    }

    @Test fun bothStagesResumeAfterAProcessRestartWithoutRedoingWork() {
        val host = host()
        host.start("gallery-7", inventory)
        host.setVisualIndex(true)
        batch()
        val visualDone = visualProgress(host).done
        val facesDone = faces.progress(faceEpoch(host))!!.processed
        val embedsBefore = embeds.get()
        assertTrue(visualDone in 1..9)

        // The authoritative Android permission is still granted, but the
        // first gallery publication is pending. Hide People until publication
        // without erasing saved choices or requiring another user tap.
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        scheduler = RecordingScheduler()
        granted = true
        val restarted = host()
        assertNull("no session from a previous process", batch())
        restarted.onGalleryPublication(null) { inventory }
        assertNull("not readable before current catalogue", restarted.status(null).people)
        restarted.onGalleryPublication("gallery-7") { inventory }
        assertTrue("visual switch survives process recreation",
            restarted.visualStatus(faceEpoch(restarted))!!.enabled)
        assertEquals("vectors survive without a second tap", visualDone,
            visualProgress(restarted).done)
        assertTrue(faces.progress(faceEpoch(restarted))!!.processed >= facesDone)

        drain(restarted)
        val done = visualProgress(restarted)
        assertEquals(9, done.indexed)
        assertEquals(1, done.skipped)
        assertEquals("indexed images are not embedded again", 9, embeds.get())
        assertTrue(embeds.get() > embedsBefore)
    }

    @Test fun revokedWhileStoppedRequiresFreshPeopleAndVisualOptIn() {
        val original = host()
        original.start("gallery-7", inventory)
        assertTrue(original.setVisualIndex(true))
        assertNotNull(choices.load())
        assertTrue(choices.loadVisual())

        // No in-process scope exists at the moment of the Android permission
        // revocation. This is the privacy-critical counterpart to a pending
        // first gallery publication after restart.
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        scheduler = RecordingScheduler()
        granted = false
        val restarted = host()
        restarted.onGalleryPublication(null) { inventory }
        assertNull("People remain unreadable after revoke", restarted.status(null).people)
        assertNull("OS revoke clears saved People choice", choices.load())
        assertFalse("OS revoke clears visual choice", choices.loadVisual())

        // An OS permission grant is not a user opt-in for face discovery.
        granted = true
        restarted.onGalleryPublication("gallery-7") { inventory }
        assertFalse("must not silently rearm discovery", restarted.discoveryOptedIn())
        assertFalse("must not silently rearm visual indexing",
            restarted.visualStatus(null)!!.enabled)
        assertNull("no People until a new explicit opt-in",
            restarted.status("gallery-7").people)
    }

    // VS-S2b: Find similar reads the live visual epoch only through this accessor.
    @Test fun visualSearchSourceFollowsTheOptInScopeAndGrant() {
        val host = host()
        host.start("gallery-7", inventory)
        val epoch = faceEpoch(host)
        assertNull("off by default: nothing to search", host.visualSearchSource(epoch))
        host.setVisualIndex(true)
        drain(host)
        val source = host.visualSearchSource(epoch)!!
        assertEquals(9, source.page(null, 512).size)
        assertTrue(source.isCurrent())
        assertNull("another face scope reads nothing", host.visualSearchSource("atlas-people-other"))

        host.setVisualIndex(false)
        assertNull(host.visualSearchSource(epoch))
        assertFalse("the old source is no longer current", source.isCurrent())

        host.setVisualIndex(true)
        assertNotNull(host.visualSearchSource(epoch))
        granted = false
        host.onGalleryPublication(null) { inventory }
        assertNull("revoked: nothing to search", host.visualSearchSource(epoch))
    }

    @Test fun revokeStopsBothStagesAndClosesTheEmbedder() {
        val host = host()
        host.start("gallery-7", inventory)
        host.setVisualIndex(true)
        batch()
        val visualEpochDone = visualProgress(host).done
        assertTrue(visualEpochDone > 0)
        val embedsBefore = embeds.get()

        granted = false
        host.onGalleryPublication(null) { inventory }
        assertNull("worker refused after revoke", batch())
        assertTrue("embedder closed", embedders.all { it.closed })
        assertEquals(embedsBefore, embeds.get())
        assertFalse(host.visualStatus(null)!!.enabled)
        // No visual epoch stays active, so nothing can be claimed or read.
        assertEquals(0, visual.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM control WHERE active_epoch IS NOT NULL", null)
            .use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun optOutStopsTheStageAndCanDeleteTheIndexAndModelCopy() {
        val host = host()
        host.start("gallery-7", inventory)
        host.setVisualIndex(true)
        batch()
        assertTrue(count("vectors") > 0)
        val embedsBefore = embeds.get()

        assertTrue(host.setVisualIndex(false))
        assertTrue(embedders.all { it.closed })
        val facesBefore = faces.progress(faceEpoch(host))!!.processed
        batch()
        assertEquals("visual stage stopped", embedsBefore, embeds.get())
        assertTrue("faces keep going", faces.progress(faceEpoch(host))!!.processed >= facesBefore)

        val off = host.visualStatus(faceEpoch(host))!!
        assertFalse(off.enabled)
        assertTrue("off offers cleanup", off.mayDelete)
        assertTrue(modelCopy.exists())
        host.deleteVisualIndex()
        assertEquals(0, count("vectors"))
        assertEquals(0, count("skips"))
        assertEquals(0, count("catalog"))
        assertFalse("joined model copy deleted", modelCopy.exists())
        assertFalse(host.visualStatus(faceEpoch(host))!!.mayDelete)

        // Switching back on starts over from an empty index.
        host.setVisualIndex(true)
        assertEquals(0, visualProgress(host).done)
        assertEquals(10, visualProgress(host).total)
    }

    @Test fun thermalStopEndsTheVisualStageBeforeTheNextImage() {
        val host = host()
        host.start("gallery-7", inventory)
        host.setVisualIndex(true)
        thermalTripAfterEmbeds = 2
        batch()
        // The guard is checked before every image: the third never started.
        assertEquals(2, embeds.get())
        assertEquals(2, visualProgress(host).indexed)
        val laters = scheduler.laters.get()
        // The next batch holds at its start and asks for a delayed retry.
        batch()
        assertEquals(2, embeds.get())
        assertEquals(laters + 1, scheduler.laters.get())

        thermal = true
        drain(host)
        assertEquals(9, visualProgress(host).indexed)
    }

    /** Review #482 finding 2: an unexpected embedder failure never stalls the run. */
    @Test fun unexpectedEmbedderFailureHoldsTheStepAndKeepsTheRunAlive() {
        val host = host()
        host.start("gallery-7", inventory)
        host.setVisualIndex(true)
        failingEmbeds = setOf(1)
        val chained = scheduler.nexts.get() + scheduler.laters.get()
        assertNotNull(batch())
        assertTrue("afterBatch still queued a next batch",
            scheduler.nexts.get() + scheduler.laters.get() > chained)
        assertEquals("nothing published from the failed step", 0, visualProgress(host).done)
        failingEmbeds = emptySet()
        drain(host)
        assertEquals(9, visualProgress(host).indexed)
        assertEquals(1, visualProgress(host).skipped)
    }

    /** Review #482 finding 5: a v1 index (VS-S1b) upgrades in place and keeps its vectors. */
    @Test fun schemaV1IndexUpgradesToV2AndKeepsItsRows() {
        dir.mkdirs()
        val file = File(dir, "visual-v1.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE control(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                active_epoch TEXT, staging_epoch TEXT,
                model_version TEXT, dimension INTEGER, staged_count INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("INSERT INTO control(singleton,model_version,dimension) VALUES(1,'$FAKE_MODEL',8)")
            db.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("""CREATE TABLE stage(media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
                revision TEXT NOT NULL, PRIMARY KEY(media_key,frame_ms))""")
            db.execSQL("""CREATE TABLE catalog(
                media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL, revision TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('queued','running','processed')),
                lease_token TEXT, lease_epoch TEXT, PRIMARY KEY(media_key,frame_ms))""")
            db.execSQL("CREATE INDEX catalog_queue ON catalog(state,media_key,frame_ms)")
            db.execSQL("""CREATE TABLE vectors(
                media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
                revision TEXT NOT NULL, model_version TEXT NOT NULL,
                dimension INTEGER NOT NULL, vector BLOB NOT NULL, scale REAL NOT NULL,
                PRIMARY KEY(media_key,frame_ms),
                FOREIGN KEY(media_key,frame_ms) REFERENCES catalog(media_key,frame_ms)
                    ON DELETE CASCADE)""")
            db.execSQL("INSERT INTO catalog VALUES('content://synthetic/1',-1,'r1','processed',NULL,NULL)")
            db.execSQL("""INSERT INTO vectors VALUES('content://synthetic/1',-1,'r1',
                '$FAKE_MODEL',8,x'0102030405060708',0.01)""")
            db.version = 1
        }
        val upgraded = VisualIndexStore(dbContext(file)).also { stores += it }
        val db = upgraded.readableDatabase
        assertEquals(VisualIndexStore.SCHEMA_VERSION, db.version)
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM vectors", null)
            .use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM skips", null)
            .use { it.moveToFirst(); it.getInt(0) })
        // The same unchanged source and model keep their vector across a new snapshot.
        val epoch = "upgrade-" + UUID.randomUUID()
        upgraded.beginSnapshot(epoch)
        upgraded.stageBatch(epoch, listOf(
            nl.atlas.gallery.featurevisual.store.VisualMedia("content://synthetic/1", "r1")))
        upgraded.commitSnapshot(epoch, FAKE_MODEL, 8)
        assertEquals(VisualIndexProgress(0, 0, 1, 0), upgraded.progress(epoch))
    }

    /** The bundled SigLIP 2 model through the [ImageEmbedder] adapter, end to end. */
    @Test fun bundledSiglipEmbedderIndexesThroughTheWorker() {
        val photos = (1L..2L).map(::photo)
        val host = host(realSiglip = true)
        host.start("gallery-7", photos)
        host.setVisualIndex(true)
        drain(host)
        assertEquals(2, visualProgress(host).indexed)
        val epoch = visual.readableDatabase.rawQuery(
            "SELECT active_epoch FROM control", null).use { it.moveToFirst(); it.getString(0) }
        val vectors = visual.loadActiveVectors(epoch, SiglipModel.MODEL_VERSION)
        assertEquals(2, vectors.size)
        vectors.forEach { assertEquals(SiglipModel.DIMENSION, it.vector.size) }
        assertEquals(1, opens.get())
        host.setVisualIndex(false)
    }

    @Test fun panelIsOffByDefaultAndShowsAProgressCount() {
        val host = host()
        host.start("gallery-7", inventory)
        compose.setContent {
            val discovery = rememberPeopleDiscoveryOf(host, "gallery-7", inventory) { it }
            VisualSearchIndexPanel(discovery.status?.visual, discovery.onVisualIndex,
                discovery.onDeleteVisualIndex)
        }
        waitFor("panel") { exists("Visual search index") }
        compose.onNodeWithTag("atlas-visual-index-switch").assertIsOff()
        assertEquals(0, opens.get())
        compose.onNodeWithTag("atlas-visual-index-switch").performClick()
        waitFor("count") { exists("0 of 10 photos done") }
        compose.onNodeWithTag("atlas-visual-index-switch").assertIsOn()
        drain(host)
        waitFor("final count") { exists("10 of 10 photos done · 1 unreadable, skipped") }
        compose.onNodeWithTag("atlas-visual-index-switch").performClick()
        waitFor("delete offered") { exists("Delete visual index") }
        compose.onNodeWithTag("atlas-visual-index-delete").performClick()
        waitFor("delete done") { !exists("Delete visual index") }
        assertNotNull(host.visualStatus(faceEpoch(host)))
        assertFalse(modelCopy.exists())
    }

    // #631 (S24+): one photo without a fixed MediaStore ID switched the whole
    // index off with "Some photos lack the provenance…". It is now left out
    // and counted, like face discovery omits it, and the rest is indexed.
    @Test fun aPhotoWithoutAFixedIdIsLeftOutAndTheIndexStaysOn() {
        val noFixedId = photo(11).copy(
            uri = "content://media/external/images/media/11", volumeName = "external")
        val withOdd = listOf(noFixedId) + inventory
        val host = host()
        host.start("gallery-7", withOdd)
        compose.setContent {
            val discovery = rememberPeopleDiscoveryOf(host, "gallery-7", withOdd) { it }
            VisualSearchIndexPanel(discovery.status?.visual, discovery.onVisualIndex,
                discovery.onDeleteVisualIndex)
        }
        waitFor("panel") { exists("Visual search index") }
        assertTrue(host.setVisualIndex(true))
        val on = host.visualStatus(faceEpoch(host))!!
        assertTrue("one odd photo no longer switches the index off", on.enabled)
        assertNull(on.failure)
        assertEquals("the other ten photos are catalogued", 10, on.total)
        assertEquals(1, on.leftOut)
        drain(host)
        assertEquals(9, visualProgress(host).indexed)
        waitFor("left-out count") {
            exists("10 of 10 photos done · 1 unreadable, skipped. 1 photo left out: " +
                "Android gives it no fixed ID, so ATLAS can't find it again")
        }
        compose.onNodeWithTag("atlas-visual-index-switch").assertIsOn()
        assertFalse(exists("provenance"))
    }

    private fun exists(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(20_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $label. Screen:\n$tree", e)
        }
    }

    private companion object {
        const val FAKE_MODEL = "synthetic-visual-v1"
    }
}
