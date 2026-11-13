package nl.atlas.gallery

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * #43: a scan cut off by process death carries on from stored state without
 * the Activity. "Process death" here is what it leaves behind: no installed
 * provider, a closed store and a new host; only SQLite and the stored choices
 * survive. Invented drawings keyed by invented rows; no real photo is read.
 */
@RunWith(AndroidJUnit4::class)
class PeopleScanProcessDeathResumeTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-resume-" + UUID.randomUUID()).apply { mkdirs() }
    private val file = File(dir, "faces.db")
    private val stores = mutableListOf<FaceIndexStore>()
    private lateinit var store: FaceIndexStore
    @Volatile private var granted = true
    @Volatile private var token: String? = "images"
    private val decoded = AtomicInteger()

    private class Memory : PeopleOptInMemory {
        var optIn: PeopleOptIn? = null
        var published: PublishedFaceScope? = null
        override fun load() = optIn
        override fun save(value: PeopleOptIn?) { optIn = value }
        override fun loadPublished() = published
        override fun savePublished(value: PublishedFaceScope?) { published = value }
    }
    private val memory = Memory()

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0,
        takenAtMillis = 1_700_000_000_000L + id
    )
    private val inventory = (1L..PHOTOS.toLong()).map(::photo)

    /** A new process: providers gone, every connection closed, a fresh host. */
    private fun newProcess(): PeopleDiscoveryHost {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        store = FaceIndexStore(object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }).also { stores += it }
        return PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { granted },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = directory.authorizedUri(lease.media).substringAfterLast('/').toInt()
                    decoded.incrementAndGet()
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(SyntheticFace.Spec(
                        480, 480, 0.4f + 0.01f * id, 0.5f, 0.8f + 0.005f * id)))
                }
            },
            power = { PeoplePowerState(charging = true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            optInMemory = memory,
            photoGrantToken = { token }
        )
    }

    private fun batch() = runBlocking { runPeopleScanBatch(target, { true }) { false } }

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    /** Opts in, scans one bounded batch, then "dies". Returns the epoch and photos done. */
    private fun scanPartlyThenDie(): Pair<String, Int> {
        val host = newProcess()
        host.start("gallery-1", inventory)
        val epoch = requireNotNull(host.displayEpoch("gallery-1"))
        requireNotNull(batch())
        val done = store.progress(epoch)!!.processed
        assertTrue("one batch is a partial scan", done in 1 until PHOTOS)
        return epoch to done
    }

    @Test fun aScanCutOffByProcessDeathCarriesOnWithoutTheActivity() {
        val (epoch, done) = scanPartlyThenDie()
        val host = newProcess()
        assertNull("nothing runs before the worker rebuilds the scope", batch())
        assertTrue(restorePeopleScanInBackground(target, host) { inventory })
        assertTrue(PeopleScanWorkRuntime.installed())
        assertEquals("the stored catalogue, no restage", epoch, store.activeEpoch())
        var batches = 0
        while (store.progress(epoch)!!.pending > 0 && batches++ < 10) requireNotNull(batch())
        val after = store.progress(epoch)!!
        assertTrue("the scan went on from $done", after.processed > done)
        assertEquals(PHOTOS, after.processed + after.failed)
    }

    @Test fun aRevokeWhileStoppedStopsAndForgets() {
        scanPartlyThenDie()
        granted = false
        val host = newProcess()
        assertFalse(restorePeopleScanInBackground(target, host) { inventory })
        assertFalse(PeopleScanWorkRuntime.installed())
        assertNull("the opt-in is gone", memory.optIn)
        assertNull(memory.published)
        assertNull(store.reviveDormantEpoch())
    }

    @Test fun aPausedRunStaysPaused() {
        scanPartlyThenDie()
        memory.optIn = PeopleOptIn(paused = true)
        val host = newProcess()
        assertFalse(restorePeopleScanInBackground(target, host) { inventory })
        assertNull(batch())
    }

    @Test fun selectedPhotosAccessIsNotRestoredInTheBackground() {
        scanPartlyThenDie()
        token = null
        val host = newProcess()
        assertFalse(restorePeopleScanInBackground(target, host) { inventory })
        assertNull(batch())
    }

    @Test fun noTrustedInventoryMeansNoRun() {
        scanPartlyThenDie()
        val host = newProcess()
        assertFalse(restorePeopleScanInBackground(target, host) { null })
        assertNull(batch())
    }

    private companion object { const val PHOTOS = 20 }
}
