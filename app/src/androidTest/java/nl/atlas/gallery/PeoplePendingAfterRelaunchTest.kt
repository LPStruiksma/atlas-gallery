package nl.atlas.gallery

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #75: the "No people" flash after a relaunch, made deterministic. A reopened
 * store marks its people data for the #580 refill; the publication serves the
 * carried catalogue first and regroups after. A status read in that window
 * (the UI restarts its read on any change) must not decide "No people".
 * The host's onCarried seam reads status exactly there. Invented rows and
 * axis-aligned synthetic vectors only; nothing is scanned or decoded.
 */
@RunWith(AndroidJUnit4::class)
class PeoplePendingAfterRelaunchTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-pending-" + UUID.randomUUID()).apply { mkdirs() }
    private val file = File(dir, "faces.db")
    private val stores = mutableListOf<FaceIndexStore>()
    private lateinit var store: FaceIndexStore
    private val ana = PersonKey("person-ana")

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
    private val inventory = (1L..4L).map(::photo)

    private fun relaunch(onCarried: () -> Unit = {}): PeopleDiscoveryHost {
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
            photoGrantCurrent = { true },
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
            photoGrantToken = { "images" },
            onCarried = onCarried
        )
    }

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun vector(axis: Int) = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }

    /** Opts in, "scans" photos 1..3 as one face and 4 as a stranger, names Ana. */
    private fun namedLibrary(): String {
        val host = relaunch()
        host.start("gallery-1", inventory)
        val epoch = requireNotNull(host.displayEpoch("gallery-1"))
        var now = 1000L
        while (true) {
            val lease = store.claim(epoch, now++) ?: break
            val axis = if (lease.media.key.contains("/4#")) 5 else 0
            assertTrue(store.completeEmbeddings(lease, listOf(IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)),
                listOf(vector(axis)), SFaceModel.EMBEDDER_VERSION))
        }
        assertTrue(store.materializeClusters(epoch))
        val stored = requireNotNull(store.peopleReview(epoch))
        // The production SFace metric was fitted to real embeddings; axis-aligned
        // synthetic vectors do not promise an automatic three-face cluster.
        // Explicitly join the invented observations as a human would, keeping
        // the relaunch/pending regression independent of that metric.
        val faceByPhoto = store.readableDatabase.rawQuery(
            """SELECT f.media_key, f.face_uid FROM faces f
                JOIN catalog c ON c.media_key=f.media_key
                WHERE c.state='processed'""", null
        ).use { cursor ->
            val ids = HashMap<String, nl.atlas.gallery.featureface.NativeFaceObservationId>()
            while (cursor.moveToNext()) {
                ids[cursor.getString(0)] =
                    nl.atlas.gallery.featureface.NativeFaceObservationId(cursor.getString(1))
            }
            (1L..4L).associateWith { id ->
                requireNotNull(ids[photo(id).toDurableFaceKey().canonicalMediaKey])
            }
        }
        assertEquals(4, faceByPhoto.size)
        val samePerson = (1L..3L).map { faceByPhoto.getValue(it) }
        var reviewed = PeopleClusterReviewState(epoch, stored.generation, stored.clusters, stored.names)
        for (next in samePerson.drop(1)) {
            val first = requireNotNull(reviewed.clusters.clusterOf(samePerson.first()))
            val other = requireNotNull(reviewed.clusters.clusterOf(next))
            if (first != other) reviewed = requireNotNull(reviewPeopleCluster(
                reviewed, PeopleReviewAction.JoinUnknownClusters(first, other)))
        }
        val cluster = reviewed.clusters.unknownClusters.single {
            it.members.containsAll(samePerson) && it.members.size == 3
        }
        val named = requireNotNull(reviewPeopleCluster(
            reviewed, PeopleReviewAction.NameCluster(cluster.id, ana, "Ana")))
        assertNotNull(store.commitPeopleReview(epoch, stored.generation, named.clusters, named.names))
        assertEquals("Ana", host.status("gallery-1").people?.people?.get(ana)?.name)
        return epoch
    }

    @Test fun aStatusReadBeforeTheRegroupNeverDecidesNoPeople() {
        val epoch = namedLibrary()
        // As a build that left people_matched behind: the next open refills it (#580).
        store.writableDatabase.execSQL(
            "UPDATE people_matched_generation SET revision=-1 WHERE singleton=1")
        var host: PeopleDiscoveryHost? = null
        var seen: PeopleDiscoveryStatus? = null
        var served = false
        host = relaunch(onCarried = {
            served = requireNotNull(host).serves("gallery-2")
            seen = host!!.status("gallery-2")
        })
        host.onGalleryPublication("gallery-2") { inventory }
        val window = requireNotNull(seen) { "the seam ran inside the publication" }
        assertTrue("the carried catalogue is served in that window", served)
        assertEquals(epoch, window.displayEpoch)
        assertNull("precondition: people are not readable before the regroup", window.people)
        assertTrue("served but pending is not an empty result", window.peoplePending)
        assertTrue("People keeps its restoring screen, never \"No people\"",
            peopleHomeRestoring(access = true, optedIn = true, status = window))
        // After the regroup the same publication serves Ana.
        val after = host.status("gallery-2")
        assertEquals("Ana", after.people?.people?.get(ana)?.name)
        assertFalse(after.peoplePending)
        assertFalse(peopleHomeRestoring(access = true, optedIn = true, status = after))
    }

    @Test fun aPendingReadKeepsThePeopleAlreadyShown() {
        val epoch = namedLibrary()
        val shown = relaunch().let { h ->
            h.onGalleryPublication("gallery-2") { inventory }
            h.status("gallery-2")
        }
        assertNotNull(shown.people)
        val pending = shown.copy(people = null, peoplePending = true)
        assertEquals(shown.people, holdPendingPeople(shown, pending)?.people)
        // People of another epoch are never shown for this one.
        assertNull(holdPendingPeople(shown, pending.copy(displayEpoch = "$epoch-next"))?.people)
        // A definite empty result (not pending) replaces them.
        val empty = shown.copy(people = null, peoplePending = false)
        assertNull(holdPendingPeople(shown, empty)?.people)
    }

    @Test fun theFirstScanBeforeAnyPublicationIsNotPending() {
        val host = relaunch()
        host.start("gallery-1", inventory)
        val status = host.status("gallery-1")
        assertTrue(status.people?.people.isNullOrEmpty())
        assertFalse("nothing published yet is an empty People page", status.peoplePending)
        assertFalse(peopleHomeRestoring(access = true, optedIn = true, status = status))
    }
}
