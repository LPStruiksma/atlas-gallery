package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.search.FaceMatchReason
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.search.FaceQueryStatus
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-F1 carry-over (#468) on API35: FaceIndexStore.faceSearchCorpus on a real
 * SQLite database. Revoke and epoch change, a stale source revision, a
 * NOT_A_FACE review and a dirty cluster publication. Invented media keys and
 * axis-aligned synthetic vectors only; no photos or biometric data.
 */
@RunWith(AndroidJUnit4::class)
class FaceSearchCorpusAndroidTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private fun box(n: Int) = IndexedFaceBox(0.1f, 0.05f * n, 0.5f, 0.05f * n + 0.4f)
    private fun media(n: Int, rev: Int = 1) =
        IndexedMedia("content://synthetic/primary/image/$n#photo", "generation:$rev")
    private fun vector(axis: Int) = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }
    private fun photoOf(key: String) = key.substringAfterLast('/').substringBefore('#').toInt()

    private fun contextFor(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
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
    }

    private inline fun withStore(test: (FaceIndexStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "vsf2b-corpus-" + UUID.randomUUID())
        check(dir.mkdirs())
        try { FaceIndexStore(contextFor(File(dir, "faces.db"))).use(test) }
        finally { dir.deleteRecursively() }
    }

    private fun grant(store: FaceIndexStore, epoch: String, rows: List<IndexedMedia>) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, rows)
        store.commitSnapshot(epoch, "detector-1", model)
    }

    /** Photos 1..3 share axis 0 (one person); any other photo is a stranger on axis 5. */
    private fun scan(store: FaceIndexStore, epoch: String, publish: Boolean = true) {
        var now = 1000L
        while (true) {
            val lease = store.claim(epoch, now++) ?: break
            val n = photoOf(lease.media.key)
            assertTrue(store.completeEmbeddings(lease, listOf(box(n)),
                listOf(vector(if (n <= 3) 0 else 5)), model))
        }
        if (publish) assertTrue(store.materializeClusters(epoch))
    }

    private fun search(corpus: FaceSearchCorpus?, axis: Int = 0) =
        FaceQueryEngine.search(corpus, FaceQuery(model, listOf(vector(axis))))

    private fun FaceSearchCorpus.photos(axis: Int = 0) =
        search(this, axis).hits.map { photoOf(it.media.key) }.toSet()

    private fun faceOf(store: FaceIndexStore, epoch: String, n: Int, rev: Int = 1) =
        store.embeddingRecords(epoch, media(n, rev).key).single().nativeFaceUid

    @Test fun corpusIsEpochGatedAndRevokeHidesIt() = withStore { store ->
        grant(store, "epoch-A", (1..4).map { media(it) })
        scan(store, "epoch-A")
        val corpus = store.faceSearchCorpus("epoch-A")!!
        assertEquals(model, corpus.modelVersion)
        assertEquals(4, corpus.faceCount)
        assertTrue(corpus.clustersPublished)
        val result = search(corpus)
        assertEquals(FaceQueryStatus.OK, result.status)
        assertEquals(setOf(1, 2, 3), result.hits.map { photoOf(it.media.key) }.toSet())
        assertTrue(result.hits.all { it.tier == FaceMatchTier.SAME_PERSON })
        // The stored box comes back with its media and revision.
        val hit = result.hits.first { photoOf(it.media.key) == 2 }
        assertEquals(media(2), hit.media)
        assertEquals(box(2), hit.faces.single().box)

        // Read-only: reading the corpus changes no review generation.
        val generation = store.peopleReviewGeneration("epoch-A")
        store.faceSearchCorpus("epoch-A")
        assertEquals(generation, store.peopleReviewGeneration("epoch-A"))

        assertNull("an unknown epoch reads nothing", store.faceSearchCorpus("epoch-other"))
        store.invalidatePermissionScope()
        assertNull("revoke hides the corpus", store.faceSearchCorpus("epoch-A"))
        assertEquals(FaceQueryStatus.UNAVAILABLE, search(null).status)

        // A fresh grant publishes it again under the new epoch only.
        grant(store, "epoch-B", (1..4).map { media(it) })
        assertNull("the old epoch stays dead", store.faceSearchCorpus("epoch-A"))
        assertEquals(4, store.faceSearchCorpus("epoch-B")!!.faceCount)
    }

    @Test fun staleRevisionLeavesTheCorpusUntilRescanned() = withStore { store ->
        grant(store, "epoch-A", (1..4).map { media(it) })
        scan(store, "epoch-A")
        assertEquals(setOf(1, 2, 3), store.faceSearchCorpus("epoch-A")!!.photos())

        // Photo 2 was edited: a new epoch sees revision 2, the old face is stale.
        grant(store, "epoch-B", listOf(media(1), media(2, rev = 2), media(3), media(4)))
        val stale = store.faceSearchCorpus("epoch-B")!!
        assertEquals(3, stale.faceCount)
        assertEquals(setOf(1, 3), stale.photos())
        assertTrue(search(stale).hits.none { it.media.key == media(2).key })

        // After the rescan the photo is back at its new revision only.
        scan(store, "epoch-B")
        val fresh = store.faceSearchCorpus("epoch-B")!!
        assertEquals(4, fresh.faceCount)
        val hit = search(fresh).hits.single { it.media.key == media(2).key }
        assertEquals(media(2, rev = 2), hit.media)
    }

    @Test fun notAFaceReviewRemovesTheObservationAndUndoRestoresIt() = withStore { store ->
        grant(store, "epoch-A", (1..4).map { media(it) })
        scan(store, "epoch-A")
        val face = faceOf(store, "epoch-A", 2)
        assertTrue(store.reviewObservation("epoch-A", face, "NOT_A_FACE"))
        val reviewed = store.faceSearchCorpus("epoch-A")!!
        assertEquals(3, reviewed.faceCount)
        assertEquals(setOf(1, 3), reviewed.photos())
        assertTrue(search(reviewed).hits.flatMap { it.faces }.none { it.face.localHex == face })

        // LATER is still a face; UNREVIEWED (Undo) restores it.
        assertTrue(store.reviewObservation("epoch-A", face, "LATER"))
        assertEquals(4, store.faceSearchCorpus("epoch-A")!!.faceCount)
        assertTrue(store.reviewObservation("epoch-A", face, "NOT_A_FACE"))
        assertTrue(store.reviewObservation("epoch-A", face, "UNREVIEWED"))
        assertEquals(setOf(1, 2, 3), store.faceSearchCorpus("epoch-A")!!.photos())
    }

    @Test fun dirtyPublicationSearchesWithoutClusterExpansion() = withStore { store ->
        grant(store, "epoch-A", (1..5).map { media(it) })
        scan(store, "epoch-A", publish = false)
        val dirty = store.faceSearchCorpus("epoch-A")!!
        assertFalse("pending publication: no partial membership", dirty.clustersPublished)
        assertEquals(5, dirty.faceCount)
        val direct = search(dirty)
        assertEquals(setOf(1, 2, 3), direct.hits.map { photoOf(it.media.key) }.toSet())
        assertTrue(direct.hits.flatMap { it.faces }.none { it.reason == FaceMatchReason.CLUSTER })

        assertTrue(store.materializeClusters("epoch-A"))
        val published = store.faceSearchCorpus("epoch-A")!!
        assertTrue(published.clustersPublished)
        assertEquals(setOf(1, 2, 3), published.photos())
        assertEquals(setOf(4, 5), published.photos(axis = 5))

        // A new scan result makes clusters dirty again until the next publication.
        grant(store, "epoch-B", (1..6).map { media(it) })
        scan(store, "epoch-B", publish = false)
        assertFalse(store.faceSearchCorpus("epoch-B")!!.clustersPublished)
        assertEquals(6, store.faceSearchCorpus("epoch-B")!!.faceCount)
    }
}
