package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API35 synthetic SQLite recovery witnesses for #424, folded into D2-5p (#419). No real photos, media
 * permissions, user index, or shared database: every test owns a cache DB.
 * A helper reopen models a process death, not an actual kill of instrumentation.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreRecoveryAndroidTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val a = IndexedMedia("content://synthetic/recovery/a", "r1")
    private val b = IndexedMedia("content://synthetic/recovery/b", "r1")
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.6f, 0.8f)
    private fun vector(axis: Int = 0): FloatArray =
        FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }

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

    private inline fun fixture(block: (Context, FaceIndexStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "face-recovery-" + UUID.randomUUID())
        check(dir.mkdirs())
        try {
            val context = contextFor(File(dir, "fixture.db"))
            FaceIndexStore(context).use { block(context, it) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun snapshot(
        store: FaceIndexStore, epoch: String, media: List<IndexedMedia>,
        embedder: String = model
    ) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, media)
        store.commitSnapshot(epoch, "detector-v1", embedder)
    }

    private fun embed(store: FaceIndexStore, epoch: String, axis: Int = 0): String {
        val lease = requireNotNull(store.claim(epoch, 1_000))
        assertTrue(store.completeEmbeddings(lease, listOf(box), listOf(vector(axis)), model))
        val uid = store.embeddingRecords(epoch, lease.media.key).single().nativeFaceUid
        // #39: a dirty generation serves at most the last publication, never the new face.
        assertFalse("A pending face became visible before publication",
            store.clusterState(epoch)?.contains(NativeFaceObservationId(uid)) == true)
        return uid
    }

    private fun rows(store: FaceIndexStore): List<Pair<String, String>> =
        store.readableDatabase.rawQuery(
            "SELECT face_uid,anchor_uid FROM cluster_members ORDER BY face_uid", null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }

    private fun generation(store: FaceIndexStore): Pair<Int, Int> =
        store.readableDatabase.rawQuery(
            "SELECT dirty,pending FROM cluster_generation WHERE singleton=1", null
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) to cursor.getInt(1)
        }

    @Test fun dirtyCompletionSurvivesHelperReopenButCannotRestoreGrant() = fixture { context, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        embed(store, "epoch-a")
        assertEquals(1, generation(store).first)
        store.close() // simulate abrupt worker/process abandonment before publication
        FaceIndexStore(context).use { reopened ->
            assertNull(reopened.clusterState("epoch-a"))
            assertTrue(reopened.embeddingRecords("epoch-a", a.key).isEmpty())
            assertNull(reopened.progress("epoch-a"))
            assertFalse(reopened.materializeClusters("epoch-a"))
            snapshot(reopened, "epoch-b", listOf(a, b))
            assertNull(reopened.clusterState("epoch-b"))
            assertTrue(reopened.materializeClusters("epoch-b"))
            assertEquals(1, reopened.clusterState("epoch-b")?.faces?.size)
            assertEquals(0, generation(reopened).first)
        }
    }

    @Test fun interruptedCoalescingAndFailedPublicationNeverExposePartialMembers() = fixture { _, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        embed(store, "epoch-a", 0)
        embed(store, "epoch-a", 1)
        assertEquals(2, generation(store).second)
        assertTrue(rows(store).isEmpty())
        assertNull(store.clusterState("epoch-a"))
        // The first insert can succeed in the transaction; the next fails.
        // SQLite must roll the entire generation back, including deletion.
        store.writableDatabase.execSQL(
            """CREATE TRIGGER stop_second_member BEFORE INSERT ON cluster_members
               WHEN (SELECT COUNT(*) FROM cluster_members) >= 1
               BEGIN SELECT RAISE(ABORT, 'synthetic partial publish'); END"""
        )
        try {
            try {
                store.materializeClusters("epoch-a")
                fail("A partly written cluster generation was published")
            } catch (_: android.database.sqlite.SQLiteException) {
                // Injected SQLite ABORT is the expected rollback witness.
            }
            assertTrue(rows(store).isEmpty())
            assertEquals(1, generation(store).first)
            assertNull(store.clusterState("epoch-a"))
        } finally {
            store.writableDatabase.execSQL("DROP TRIGGER stop_second_member")
        }
        assertTrue(store.materializeClusters("epoch-a"))
        assertEquals(2, rows(store).size)
        assertEquals(2, store.clusterState("epoch-a")?.faces?.size)
        assertEquals(0 to 0, generation(store))
        assertTrue(store.materializeClusters("epoch-a")) // idempotent clean publication
    }

    @Test fun failedReplacementRetainsOldRowsAndServesOnlyTheLastPublication() = fixture { _, store ->
        snapshot(store, "epoch-a", listOf(a))
        embed(store, "epoch-a")
        assertTrue(store.materializeClusters("epoch-a"))
        val priorRows = rows(store)
        snapshot(store, "epoch-b", listOf(a, b))
        embed(store, "epoch-b", 1)
        // #39: the last publication (photo a only) stays readable while b waits.
        assertEquals(1, store.clusterState("epoch-b")?.faces?.size)
        store.writableDatabase.execSQL(
            """CREATE TRIGGER reject_members BEFORE INSERT ON cluster_members
               BEGIN SELECT RAISE(ABORT, 'synthetic replacement rollback'); END"""
        )
        try {
            try {
                store.materializeClusters("epoch-b")
                fail("Replacement unexpectedly committed")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(priorRows, rows(store))
            assertEquals(1, store.clusterState("epoch-b")?.faces?.size)
            assertNull(store.clusterState("epoch-a"))
        } finally {
            store.writableDatabase.execSQL("DROP TRIGGER reject_members")
        }
        assertTrue(store.materializeClusters("epoch-b"))
        assertEquals(2, store.clusterState("epoch-b")?.faces?.size)
    }

    @Test fun revokedScopeAndNewerEpochRejectStaleLeaseAndMembership() = fixture { _, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        val oldLease = requireNotNull(store.claim("epoch-a", 1_000))
        store.invalidatePermissionScope()
        assertFalse(store.completeEmbeddings(oldLease, listOf(box), listOf(vector()), model))
        assertFalse(store.materializeClusters("epoch-a"))
        assertNull(store.clusterState("epoch-a"))
        snapshot(store, "epoch-b", listOf(a, b))
        assertFalse(store.completeEmbeddings(oldLease, listOf(box), listOf(vector()), model))
        assertFalse(store.completeEmbeddings(oldLease.copy(permissionEpoch = "epoch-b"),
            listOf(box), listOf(vector()), model))
        assertFalse(store.completeEmbeddings(oldLease.copy(token = "lost"),
            listOf(box), listOf(vector()), model))
        embed(store, "epoch-b")
        assertTrue(store.materializeClusters("epoch-b"))
        assertNull(store.clusterState("epoch-a"))
        assertEquals(1, store.clusterState("epoch-b")?.faces?.size)
        try {
            store.beginSnapshot("epoch-a")
            fail("Reused old epoch became valid")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.clusterState("epoch-b")?.faces?.size)
    }

    @Test fun sourceEditDeletionAndModelBumpCannotRevalidateOldCluster() = fixture { _, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        val old = embed(store, "epoch-a")
        assertTrue(store.materializeClusters("epoch-a"))
        assertEquals(1, store.clusterState("epoch-a")?.faces?.size)
        snapshot(store, "epoch-b", listOf(a.copy(revision = "r2")))
        assertNull(store.clusterState("epoch-a"))
        assertTrue(store.embeddingRecords("epoch-b", b.key).isEmpty())
        assertTrue(store.embeddingRecords("epoch-b", a.key).isEmpty())
        // #39: no published face is left, so nothing is served until the rescan.
        assertNull(store.clusterState("epoch-b"))
        val newUid = embed(store, "epoch-b")
        assertNotEquals(old, newUid)
        assertTrue(store.materializeClusters("epoch-b"))
        assertEquals(1, store.clusterState("epoch-b")?.faces?.size)
        snapshot(store, "epoch-c", listOf(a.copy(revision = "r2")),
            "$model-next")
        assertNull(store.clusterState("epoch-c"))
        assertTrue(store.embeddingRecords("epoch-c", a.key).isEmpty())
        assertFalse(store.completeEmbeddings(
            requireNotNull(store.claim("epoch-c", 2_000)), listOf(box),
            listOf(vector()), model))
        assertTrue(store.materializeClusters("epoch-c"))
        assertEquals(0, store.clusterState("epoch-c")?.faces?.size)
        assertNull(store.clusterState("epoch-b"))
    }

    @Test fun abandonedLeaseAfterReopenCannotCompleteOrExposeOldGeneration() = fixture { context, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        val abandoned = requireNotNull(store.claim("epoch-a", 1_000, 10))
        store.close()
        FaceIndexStore(context).use { reopened ->
            assertFalse(reopened.completeEmbeddings(abandoned, listOf(box),
                listOf(vector()), model))
            assertNull(reopened.clusterState("epoch-a"))
            snapshot(reopened, "epoch-b", listOf(a, b))
            assertFalse(reopened.completeEmbeddings(abandoned, listOf(box),
                listOf(vector()), model))
            val next = requireNotNull(reopened.claim("epoch-b", 2_000, 10))
            assertNotEquals(abandoned.token, next.token)
            assertFalse(reopened.completeEmbeddings(next.copy(token = "lost"),
                listOf(box), listOf(vector()), model))
            assertTrue(reopened.completeEmbeddings(next, listOf(box), listOf(vector()), model))
            assertNull(reopened.clusterState("epoch-b"))
            assertTrue(reopened.materializeClusters("epoch-b"))
            assertEquals(1, reopened.clusterState("epoch-b")?.faces?.size)
        }
    }

    @Test fun publishedSeedSurvivesReopenAndOnlyChangedRowsAreRewritten() = fixture { context, store ->
        val c = IndexedMedia("content://synthetic/recovery/c", "r1")
        snapshot(store, "epoch-a", listOf(a, b, c))
        embed(store, "epoch-a", 0)
        embed(store, "epoch-a", 5)
        assertTrue(store.materializeClusters("epoch-a"))
        val published = rows(store)
        assertEquals(2, published.size)
        // Third photo completes, then the process dies before publication.
        embed(store, "epoch-a", 9)
        assertEquals(1 to 1, generation(store))
        store.close()
        FaceIndexStore(context).use { reopened ->
            assertNull(reopened.clusterState("epoch-a"))
            snapshot(reopened, "epoch-b", listOf(a, b, c))
            // #39: the two published faces are served while the third waits.
            assertEquals(2, reopened.clusterState("epoch-b")?.faces?.size)
            assertTrue(reopened.materializeClusters("epoch-b"))
            val after = rows(reopened)
            // Published rows are the seed: kept as they were (distinct axes,
            // so no anchor moves); only the pending face is written.
            assertTrue(after.containsAll(published))
            assertEquals(3, after.size)
            val work = requireNotNull(reopened.lastPublicationWork)
            assertEquals("one member row + the cluster generation row + the people review " +
                "generation row (#434) + the People home summary generation row (#532) + " +
                "the people_matched generation row (#580)",
                5L, work.rowsWritten)
            assertEquals(3, reopened.clusterState("epoch-b")?.faces?.size)
            // Clean generation: a second publication is a no-op write.
            assertTrue(reopened.materializeClusters("epoch-b"))
            assertEquals(0L, requireNotNull(reopened.lastPublicationWork).rowsWritten)
        }
    }

    @Test fun cadenceDefersPublicationButFinishedScanPublishes() = fixture { _, store ->
        snapshot(store, "epoch-a", listOf(a, b))
        embed(store, "epoch-a", 0)
        assertFalse(store.publishClustersIfDue("epoch-a", scanFinished = false))
        assertNull(store.clusterState("epoch-a"))
        assertFalse(store.publishClustersIfDue("epoch-other", scanFinished = true))
        assertTrue(store.publishClustersIfDue("epoch-a", scanFinished = true))
        assertEquals(1, store.clusterState("epoch-a")?.faces?.size)
        assertFalse("clean generation has nothing to publish",
            store.publishClustersIfDue("epoch-a", scanFinished = true))
        assertEquals(1, store.clusterState("epoch-a")?.faces?.size)
    }
}
