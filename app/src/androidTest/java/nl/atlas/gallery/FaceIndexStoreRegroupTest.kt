package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #579 on a real SQLite file: membership published under other grouping
 * rules (here one "person" per face, under an unknown fingerprint) is
 * regrouped from the stored vectors by the next publication, without a rescan. Invented keys and axis-built vectors only.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreRegroupTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private val photos = List(6) { IndexedMedia("atlas-face-media-v1:synthetic-$it", "rev-1") }

    /** Three faces per person, every pair at cosine 0.55: one group under the default rules. */
    private fun vector(photo: Int): FloatArray {
        val axis = (photo / 3) * 3
        val v = FloatArray(SFaceModel.DIMENSION)
        when (photo % 3) {
            0 -> v[axis] = 1f
            1 -> { v[axis] = 0.55f; v[axis + 1] = sqrt(1f - 0.55f * 0.55f) }
            else -> {
                val y = (0.55f - 0.55f * 0.55f) / sqrt(1f - 0.55f * 0.55f)
                v[axis] = 0.55f; v[axis + 1] = y
                v[axis + 2] = sqrt(1f - 0.55f * 0.55f - y * y)
            }
        }
        return v
    }

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

    private fun grant(store: FaceIndexStore, epoch: String) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, photos)
        store.commitSnapshot(epoch, "detector-1", model)
    }

    private fun groupSizes(store: FaceIndexStore, epoch: String): List<Int> =
        requireNotNull(store.clusterState(epoch)).unknownClusters.map { it.members.size }.sorted()

    @Test fun olderMembershipIsRegroupedFromStoredVectorsOnOpen() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "regroup-579-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "face_fixture.db")
        try {
            FaceIndexStore(contextFor(file)).let { store ->
                grant(store, "epoch-1")
                for (i in photos.indices) {
                    val lease = requireNotNull(store.claim("epoch-1", 1_000L + i))
                    assertTrue(store.completeEmbeddings(lease, listOf(box),
                        listOf(vector(photos.indexOf(lease.media))), model))
                }
                assertTrue(store.materializeClusters("epoch-1"))
                assertEquals(listOf(3, 3), groupSizes(store, "epoch-1"))
                store.close()
            }
            // The same rules never regroup: reopening leaves People readable at once.
            FaceIndexStore(contextFor(file)).let { store ->
                val db = store.writableDatabase
                db.rawQuery("SELECT dirty FROM cluster_generation", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
                }
                db.rawQuery("SELECT fingerprint FROM cluster_policy", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(ClusterPolicy.DEFAULT.fingerprint, c.getString(0))
                }
                store.close()
            }
            // What a build from before #579 left behind: no fingerprint, and
            // every face its own "person".
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE cluster_members SET anchor_uid=face_uid")
                db.execSQL("UPDATE cluster_policy SET fingerprint=NULL WHERE singleton=1")
            }
            // Opening records the rules that file was grouped under and only
            // marks it for a regroup (#580: the default is no longer those rules).
            FaceIndexStore(contextFor(file)).let { store ->
                val db = store.writableDatabase
                db.rawQuery("SELECT dirty FROM cluster_generation", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
                }
                db.rawQuery("SELECT fingerprint FROM cluster_policy", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(ClusterPolicy.SFACE_CONSERVATIVE.fingerprint, c.getString(0))
                }
                db.rawQuery("SELECT COUNT(*) FROM cluster_members", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(6, c.getInt(0))
                }
                store.close()
            }
            FaceIndexStore(contextFor(file)).let { store ->
                grant(store, "epoch-2") // same photos, unchanged: nothing to rescan
                assertEquals(null, store.claim("epoch-2", 5_000L))
                assertTrue(store.materializeClusters("epoch-2"))
                assertEquals(listOf(3, 3), groupSizes(store, "epoch-2"))
                // People home lists both unnamed groups of 3, with their face count.
                assertEquals(listOf(3, 3),
                    requireNotNull(store.peopleHome("epoch-2")).unnamed.map { it.faceCount })
                store.close()
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT fingerprint FROM cluster_policy", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(ClusterPolicy.DEFAULT.fingerprint, c.getString(0))
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * #580: a regroup split into parts (#508) stays pending after its first
     * part, although that part already recorded the new rules, so a host that
     * was killed in between picks the rest up at its next publication.
     */
    @Test fun aRegroupInterruptedBetweenPartsStaysPending() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "regroup-580-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "face_fixture.db")
        try {
            FaceIndexStore(contextFor(file)).let { store ->
                grant(store, "epoch-1")
                for (i in photos.indices) {
                    val lease = requireNotNull(store.claim("epoch-1", 1_000L + i))
                    assertTrue(store.completeEmbeddings(lease, listOf(box),
                        listOf(vector(photos.indexOf(lease.media))), model))
                }
                assertTrue(store.materializeClusters("epoch-1"))
                store.close()
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE cluster_policy SET fingerprint='older-rules' WHERE singleton=1")
            }
            FaceIndexStore(contextFor(file), publicationFaceLimit = 2).let { store ->
                grant(store, "epoch-2")
                assertTrue(store.regroupPending("epoch-2"))
                assertTrue(store.materializeClusters("epoch-2")) // first part: 2 of 6 faces
                assertTrue(store.clusterPublicationContinues("epoch-2"))
                store.close()
            }
            // The process died here. The new rules are already recorded.
            FaceIndexStore(contextFor(file), publicationFaceLimit = 2).let { store ->
                store.writableDatabase.rawQuery("SELECT fingerprint FROM cluster_policy", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(ClusterPolicy.DEFAULT.fingerprint, c.getString(0))
                }
                grant(store, "epoch-3") // the restarted app's first gallery publication
                assertTrue(store.regroupPending("epoch-3"))
                assertEquals(null, store.peopleHome("epoch-3"))
                var parts = 0
                do { assertTrue(store.materializeClusters("epoch-3")); parts++ }
                while (store.clusterPublicationContinues("epoch-3") && parts < 10)
                assertEquals(false, store.regroupPending("epoch-3"))
                assertEquals(listOf(3, 3), groupSizes(store, "epoch-3"))
                store.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
