package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.FaceQuality
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** COVER-2: real SQLite, synthetic faces and invented grant-scoped media keys. */
@RunWith(AndroidJUnit4::class)
class BestShotCoverAndroidTest {
    private val person = PersonKey("cover2-person")
    private val box = IndexedFaceBox(.1f, .2f, .5f, .8f)
    private val model = SFaceModel.EMBEDDER_VERSION
    private fun item(n: Int, video: Boolean = false) =
        IndexedMedia("content://synthetic/cover2/$n#${if (video) "video" else "photo"}", "revision:1")
    private fun context(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }
    private inline fun withStore(block: (FaceIndexStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "cover2-" + UUID.randomUUID())
        check(dir.mkdirs())
        try { FaceIndexStore(context(File(dir, "db"))).use(block) }
        finally { dir.deleteRecursively() }
    }
    private fun scan(store: FaceIndexStore, epoch: String, scores: Map<Int, Float?>,
                     videos: Set<Int> = emptySet()) {
        val keys = scores.keys.sorted().map { item(it, it in videos) }
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, keys)
        store.commitSnapshot(epoch, "detector-1", model)
        var clock = 1000L
        while (true) {
            val lease = store.claim(epoch, clock++) ?: break
            val n = lease.media.key.substringAfterLast('/').substringBefore('#').toInt()
            val quality = scores.getValue(n)?.let { FaceQuality(it, belowSizeFloor = false) }
            assertTrue(store.completeEmbeddings(lease, listOf(box),
                listOf(FloatArray(SFaceModel.DIMENSION) { if (it == 0) 1f else 0f }),
                model, frameMs = listOf(if (n in videos) n * 1000L else null),
                qualityScores = listOf(quality)))
        }
        assertTrue(store.materializeClusters(epoch))
    }
    private fun name(store: FaceIndexStore, epoch: String) {
        val current = requireNotNull(store.peopleReview(epoch))
        val cluster = current.clusters.unknownClusters.first()
        val named = requireNotNull(reviewPeopleCluster(
            PeopleClusterReviewState(epoch, current.generation, current.clusters, current.names),
            PeopleReviewAction.NameCluster(cluster.id, person, "Cover person")))
        assertNotNull(store.commitPeopleReview(epoch, current.generation, named.clusters, named.names))
    }
    private fun faceBySource(store: FaceIndexStore, epoch: String): Map<Int, String> {
        val confirmed = requireNotNull(store.peopleReview(epoch)).clusters.reviews.confirmed.keys
            .map { it.localHex }
        return store.faceMedia(epoch, confirmed).mapNotNull { (uid, media) ->
            media.key.substringAfterLast('/').substringBefore('#').toIntOrNull()?.let { it to uid }
        }.toMap()
    }
    private fun cover(store: FaceIndexStore, epoch: String) =
        requireNotNull(store.peopleHome(epoch)).people.getValue(person).coverFaceUid

    @Test fun photosWinBackfillUpdatesManualOverrideAndSplitFallBack() = withStore { store ->
        scan(store, "epoch-A", mapOf(1 to .65f, 2 to .99f, 3 to null), setOf(2))
        name(store, "epoch-A")
        val bySource = faceBySource(store, "epoch-A")
        assertEquals(bySource.getValue(1), cover(store, "epoch-A"))
        val before = requireNotNull(store.peopleReviewGeneration("epoch-A"))
        val pending = requireNotNull(store.pendingQualitySource("epoch-A"))
        assertEquals(item(3).key, pending.media.canonicalMediaKey)
        assertEquals(1, store.backfillQualityIfNull("epoch-A", pending,
            mapOf(bySource.getValue(3) to FaceQuality(.78f, belowSizeFloor = false))))
        assertEquals("Quality-only updates must not rewrite the review generation",
            before, store.peopleReviewGeneration("epoch-A"))
        assertEquals(bySource.getValue(3), cover(store, "epoch-A"))
        assertTrue(store.setPersonCoverOverride("epoch-A", person, bySource.getValue(1)))
        assertEquals(bySource.getValue(1), cover(store, "epoch-A"))
        assertTrue(store.setPersonCoverOverride("epoch-A", person, null))
        assertEquals(bySource.getValue(3), cover(store, "epoch-A"))
        val current = requireNotNull(store.peopleReview("epoch-A"))
        val split = requireNotNull(reviewPeopleCluster(
            PeopleClusterReviewState("epoch-A", current.generation, current.clusters, current.names),
            PeopleReviewAction.SplitPerson(person, setOf(
                nl.atlas.gallery.featureface.NativeFaceObservationId(bySource.getValue(3))))))
        assertNotNull(store.commitPeopleReview("epoch-A", current.generation,
            split.clusters, split.names))
        assertEquals(bySource.getValue(1), cover(store, "epoch-A"))
    }

    @Test fun onlyVideoGetsBestSampledFrame() = withStore { store ->
        scan(store, "epoch-V", mapOf(1 to .54f, 2 to .87f), setOf(1, 2))
        name(store, "epoch-V")
        val chosen = faceBySource(store, "epoch-V").getValue(2)
        assertEquals(chosen, cover(store, "epoch-V"))
        assertEquals(2000L, store.faceLocation("epoch-V", chosen)?.frameMs)
    }

    @Test fun marginalBackfillDoesNotDisplaceIncumbent() = withStore { store ->
        scan(store, "epoch-M", mapOf(1 to .65f, 2 to null))
        name(store, "epoch-M")
        val bySource = faceBySource(store, "epoch-M")
        val pending = requireNotNull(store.pendingQualitySource("epoch-M"))
        assertEquals(1, store.backfillQualityIfNull("epoch-M", pending,
            mapOf(bySource.getValue(2) to FaceQuality(.68f, belowSizeFloor = false))))
        assertEquals(bySource.getValue(1), cover(store, "epoch-M"))
    }
}
