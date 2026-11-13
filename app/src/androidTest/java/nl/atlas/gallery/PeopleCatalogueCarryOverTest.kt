package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #31: after a process restart (a new host over a reopened store), the first
 * gallery publication under the same full photo grant serves the last
 * catalogue at once. An unchanged inventory needs no restage; a changed one
 * restages behind it. A changed grant, selected-photos access (no token) or
 * a revoke never carries anything over. Invented rows only; nothing is scanned.
 */
@RunWith(AndroidJUnit4::class)
class PeopleCatalogueCarryOverTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-carry-" + UUID.randomUUID()).apply { mkdirs() }
    private val file = File(dir, "faces.db")
    private val stores = mutableListOf<FaceIndexStore>()
    private lateinit var store: FaceIndexStore

    @Volatile private var granted = true
    @Volatile private var token: String? = "images"

    /** Survives "process death" like the app's SharedPreferences. */
    private class Memory : PeopleOptInMemory {
        var optIn: PeopleOptIn? = null
        var visual = false
        var published: PublishedFaceScope? = null
        override fun load() = optIn
        override fun save(value: PeopleOptIn?) { optIn = value }
        override fun loadVisual() = visual
        override fun saveVisual(enabled: Boolean) { visual = enabled }
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
    private val inventory = (1L..6L).map(::photo)

    /** A new process: every store connection closed, then a fresh host. */
    private fun relaunch(): PeopleDiscoveryHost {
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
                    OnnxFaceDetector.fromAssets(target.assets),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { FaceFrameReader { _, _ -> throw AssertionError("nothing is scanned") } },
            power = { PeoplePowerState(charging = false, batteryNotLow = true, storageNotLow = true) },
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

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    /** Opts in and publishes once under the current grant; returns the epoch. */
    private fun firstLaunch(): String {
        val host = relaunch()
        host.start("gallery-1", inventory)
        val epoch = requireNotNull(host.displayEpoch("gallery-1"))
        assertTrue(epoch.startsWith("atlas-face-"))
        assertEquals(epoch, memory.published?.epoch)
        return epoch
    }

    @Test fun anUnchangedLibraryIsServedFromTheLastCatalogueWithoutARestage() {
        val epoch = firstLaunch()
        val host = relaunch()
        assertFalse("nothing is readable before the first publication", host.serves("gallery-2"))
        assertNull(store.activeEpoch())
        host.onGalleryPublication("gallery-2") { inventory }
        assertTrue(host.serves("gallery-2"))
        assertEquals("the same catalogue, not a restage", epoch, host.displayEpoch("gallery-2"))
        assertEquals(epoch, store.activeEpoch())
        assertNotNull(store.progress(epoch))
        assertNotNull(host.status("gallery-2").ui)
        assertEquals(epoch, memory.published?.epoch)
    }

    @Test fun aChangedLibraryRestagesBehindTheCarriedCatalogue() {
        val epoch = firstLaunch()
        val host = relaunch()
        host.onGalleryPublication("gallery-2") { inventory + photo(7) }
        val next = requireNotNull(host.displayEpoch("gallery-2"))
        assertNotEquals("a changed library gets a fresh catalogue", epoch, next)
        assertEquals(next, store.activeEpoch())
        assertEquals(7, store.progress(next)!!.pending)
        assertEquals(next, memory.published?.epoch)
        // The next relaunch carries the new one.
        val again = relaunch()
        again.onGalleryPublication("gallery-3") { inventory + photo(7) }
        assertEquals(next, again.displayEpoch("gallery-3"))
    }

    @Test fun aDifferentGrantRestagesBlind() {
        val epoch = firstLaunch()
        token = "images+videos"
        val host = relaunch()
        host.onGalleryPublication("gallery-2") { inventory }
        assertNotEquals(epoch, host.displayEpoch("gallery-2"))
        assertNull("the old catalogue is gone, not dormant", store.reviveDormantEpoch())
    }

    @Test fun selectedPhotosAccessNeverCarriesACatalogue() {
        val epoch = firstLaunch()
        token = null // selected-photos access: the set can change under one token
        val host = relaunch()
        host.onGalleryPublication("gallery-2") { inventory }
        assertNotEquals(epoch, host.displayEpoch("gallery-2"))
        assertNull("no token, nothing stored for the next relaunch", memory.published)
    }

    @Test fun aRevokeWhileStoppedLeavesNothingToCarry() {
        firstLaunch()
        granted = false
        val revoked = relaunch()
        revoked.onGalleryPublication(null) { inventory }
        assertNull(memory.published)
        assertNull(memory.optIn)
        granted = true
        val host = relaunch()
        host.onGalleryPublication("gallery-2") { inventory }
        assertFalse("a new grant is not a new opt-in", host.serves("gallery-2"))
        assertNull(store.reviveDormantEpoch())
    }

    @Test fun theSameProcessKeepsServingAcrossAnUnchangedRefresh() {
        val host = relaunch()
        host.start("gallery-1", inventory)
        val epoch = host.displayEpoch("gallery-1")
        host.onGalleryPublication("gallery-2") { inventory }
        assertEquals("a refresh with nothing new keeps the catalogue", epoch,
            host.displayEpoch("gallery-2"))
        host.onGalleryPublication("gallery-3") { inventory.drop(1) }
        val next = host.displayEpoch("gallery-3")
        assertNotEquals(epoch, next)
        assertEquals(5, store.progress(next!!)!!.pending)
    }
}
