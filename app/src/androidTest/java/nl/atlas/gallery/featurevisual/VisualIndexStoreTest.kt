package nl.atlas.gallery.featurevisual

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SQLite API35 tests, but only synthetic media keys/vectors. The 150k
 * witness is opt-in so it does not consume the normal emulator job.
 */
@RunWith(AndroidJUnit4::class)
class VisualIndexStoreTest {
    private val first = VisualMedia("content://synthetic/primary/1", "generation:1")
    private val second = VisualMedia("content://synthetic/primary/2", "generation:1", 1500L)
    private val axisA = floatArrayOf(1f, 0f, 0f, 0f)
    private val axisB = floatArrayOf(0f, 1f, 0f, 0f)

    private fun dbContext(file: File): Context {
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

    private inline fun withFile(test: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-visual-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "visual_fixture.db")
        try { test(dbContext(file), file) } finally { dir.deleteRecursively() }
    }

    private fun publish(
        store: VisualIndexStore, epoch: String, version: String,
        media: List<VisualMedia> = listOf(first, second), dimension: Int = 4
    ) {
        store.beginSnapshot(epoch)
        media.chunked(VisualIndexStore.MAX_BATCH).forEach { store.stageBatch(epoch, it) }
        store.commitSnapshot(epoch, version, dimension)
    }

    private fun claim(store: VisualIndexStore, epoch: String, version: String,
                      media: List<VisualMedia> = listOf(first, second)): List<VisualIndexLease> =
        store.claimAuthorized(epoch, version, media, media.size)

    private fun save(store: VisualIndexStore, leases: List<VisualIndexLease>): Boolean =
        store.completeBatch(leases.mapIndexed { i, lease ->
            VisualVectorWrite(lease, if (i % 2 == 0) axisA else axisB)
        })

    @Test fun reopenedEpochIsDormantUntilRevivedOnceWithSameModel() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            publish(store, "visual-a", "model-a")
            assertEquals(2, store.progress("visual-a")?.total)
        }
        VisualIndexStore(context).use { reopened ->
            assertNull(reopened.progress("visual-a"))
            assertTrue(reopened.loadActiveVectors("visual-a", "model-a").isEmpty())
            assertNull("wrong model must not revive", reopened.reviveDormantEpoch("model-b", 4))
            assertNull("wrong dimension must not revive", reopened.reviveDormantEpoch("model-a", 8))
            assertEquals("visual-a", reopened.reviveDormantEpoch("model-a", 4))
            assertEquals(2, reopened.progress("visual-a")?.total)
            assertNull("revival is single-use", reopened.reviveDormantEpoch("model-a", 4))
        }
    }

    @Test fun revocationAndNewPublicationDropDormantVisualEpoch() = withFile { context, _ ->
        VisualIndexStore(context).use { publish(it, "visual-a", "model-a") }
        VisualIndexStore(context).use { reopened ->
            assertNull(reopened.progress("visual-a"))
            reopened.invalidatePermissionScope()
            assertNull(reopened.reviveDormantEpoch("model-a", 4))
            publish(reopened, "visual-b", "model-a")
        }
        VisualIndexStore(context).use { reopened ->
            assertNull(reopened.progress("visual-b"))
            publish(reopened, "visual-c", "model-a")
            assertNull("new snapshot discards the dormant predecessor",
                reopened.reviveDormantEpoch("model-a", 4))
            assertNull(reopened.progress("visual-b"))
            assertNotNull(reopened.progress("visual-c"))
        }
    }

    @Test fun versionBumpAndChangedRevisionRequireReembedding() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            publish(store, "grant-A", "siglip-v1")
            assertTrue(save(store, claim(store, "grant-A", "siglip-v1")))
            assertEquals(2, store.loadActiveVectors("grant-A", "siglip-v1").size)
            // Same source and model survives a freshly authorised grant.
            publish(store, "grant-B", "siglip-v1")
            assertTrue(claim(store, "grant-B", "siglip-v1").isEmpty())
            assertEquals(2, store.loadActiveVectors("grant-B", "siglip-v1").size)
            // A new model is a different vector space, not a cache hit.
            publish(store, "grant-C", "siglip-v2")
            assertTrue(store.loadActiveVectors("grant-C", "siglip-v1").isEmpty())
            assertTrue(store.loadActiveVectors("grant-C", "siglip-v2").isEmpty())
            assertEquals(2, claim(store, "grant-C", "siglip-v2").size)
            store.invalidatePermissionScope()
            val revised = first.copy(revision = "generation:2")
            publish(store, "grant-D", "siglip-v2", listOf(revised))
            assertEquals(1, claim(store, "grant-D", "siglip-v2", listOf(revised)).size)
            assertTrue(store.loadActiveVectors("grant-D", "siglip-v2").isEmpty())
        }
    }

    @Test fun revocationAndStaleBatchCannotLeakOrOverwriteFreshGrant() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            publish(store, "grant-A", "v1")
            val old = claim(store, "grant-A", "v1")
            store.invalidatePermissionScope()
            assertTrue(store.loadActiveVectors("grant-A", "v1").isEmpty())
            assertFalse(save(store, old))
            assertEquals(0, store.releaseBatch(old))
            publish(store, "grant-B", "v1")
            assertFalse(save(store, old))
            val current = claim(store, "grant-B", "v1")
            assertEquals(2, current.size)
            assertTrue(save(store, current))
            assertEquals(2, store.loadActiveVectors("grant-B", "v1").size)
            assertTrue(store.loadActiveVectors("grant-A", "v1").isEmpty())
            // Old ABA token cannot replace or hide a newer live epoch.
            try {
                store.beginSnapshot("grant-A")
                fail("Replayed epoch accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(2, store.loadActiveVectors("grant-B", "v1").size)
        }
    }

    @Test fun crashBetweenBatchesKeepsCommittedRowsButRevokesOldAccess() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            publish(store, "grant-A", "v1")
            val a = claim(store, "grant-A", "v1", listOf(first))
            assertTrue(save(store, a))
            // A claimed frame remains uncommitted when the process dies.
            assertEquals(1, claim(store, "grant-A", "v1", listOf(second)).size)
        }
        VisualIndexStore(context).use { reopened ->
            assertTrue(reopened.loadActiveVectors("grant-A", "v1").isEmpty())
            try {
                reopened.beginSnapshot("grant-A")
                fail("Epoch was reused after process restart")
            } catch (_: IllegalArgumentException) { }
            publish(reopened, "grant-B", "v1")
            val pending = claim(reopened, "grant-B", "v1")
            assertEquals(listOf(second), pending.map { it.media })
            assertTrue(save(reopened, pending))
            assertEquals(2, reopened.loadActiveVectors("grant-B", "v1").size)
        }
    }

    @Test fun inconsistentStagedInventoryCannotPublishPartialPermissionScope() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            store.beginSnapshot("grant-A")
            store.stageBatch("grant-A", listOf(first))
            try {
                store.stageBatch("grant-A", listOf(first.copy(revision = "different")))
                fail("Conflicting source revisions accepted")
            } catch (_: IllegalArgumentException) { }
            try {
                store.commitSnapshot("grant-A", "v1", 4)
                fail("Invalid staged snapshot published")
            } catch (_: IllegalArgumentException) { }
            publish(store, "grant-B", "v1", listOf(first))
            assertEquals(1, claim(store, "grant-B", "v1", listOf(first)).size)
        }
    }

    @Test fun ignoredSecondCompletionRollsBackFirstVector() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            publish(store, "grant-A", "v1")
            val leases = claim(store, "grant-A", "v1")
            store.writableDatabase.execSQL("""CREATE TRIGGER ignore_second_visual
                BEFORE UPDATE ON catalog
                WHEN NEW.media_key='${second.key}' AND NEW.state='processed'
                BEGIN SELECT RAISE(IGNORE); END""")
            try {
                save(store, leases)
                fail("Silently ignored update must roll back whole batch")
            } catch (_: IllegalStateException) { }
            assertTrue(store.loadActiveVectors("grant-A", "v1").isEmpty())
            store.writableDatabase.execSQL("DROP TRIGGER ignore_second_visual")
            assertTrue(save(store, leases))
            assertEquals(2, store.loadActiveVectors("grant-A", "v1").size)
        }
    }

    @Test fun keysetPagesRetainVideoFrameIdentity() = withFile { context, _ ->
        VisualIndexStore(context).use { store ->
            val frames = listOf(
                VisualMedia("content://synthetic/video/1", "r1", 0),
                VisualMedia("content://synthetic/video/1", "r1", 2_000),
                VisualMedia("content://synthetic/video/1", "r1", 4_000)
            )
            publish(store, "grant-A", "v1", frames)
            assertTrue(save(store, claim(store, "grant-A", "v1", frames)))
            val pages = mutableListOf<Long?>()
            var after: nl.atlas.gallery.featurevisual.store.VisualVectorCursor? = null
            while (true) {
                val page = store.loadActiveVectors("grant-A", "v1", after, limit = 1)
                if (page.isEmpty()) break
                pages += page.single().media.frameMs
                after = page.single().cursor
            }
            assertEquals(listOf(0L, 2_000L, 4_000L), pages)
        }
    }

    @Test fun scale150kSyntheticVectorsAndPagedLoadTiming() = withFile { context, file ->
        assumeTrue("Opt in with -e atlasScale150k true",
            InstrumentationRegistry.getArguments().getString("atlasScale150k") == "true")
        val count = 150_000
        val dimension = 768
        VisualIndexStore(context).use { store ->
            val began = SystemClock.elapsedRealtime()
            store.beginSnapshot("scale-A")
            for (start in 0 until count step 1024) {
                store.stageBatch("scale-A", (start until minOf(count,start + 1024)).map {
                    VisualMedia("content://synthetic/image/$it", "r1")
                })
            }
            store.commitSnapshot("scale-A", "synthetic-768-v1", dimension)
            val indexedStart = SystemClock.elapsedRealtime()
            for (start in 0 until count step 512) {
                val candidates = (start until minOf(count, start + 512)).map {
                    VisualMedia("content://synthetic/image/$it", "r1")
                }
                val leases = store.claimAuthorized("scale-A", "synthetic-768-v1",
                    candidates, candidates.size)
                assertEquals(candidates.size, leases.size)
                assertTrue(store.completeBatch(leases.mapIndexed { offset, lease ->
                    val index = start + offset
                    VisualVectorWrite(lease,
                        FloatArray(dimension) { if (it == index % dimension) 1f else 0f })
                }))
            }
            val loadStart = SystemClock.elapsedRealtime()
            var loaded = 0
            var after: nl.atlas.gallery.featurevisual.store.VisualVectorCursor? = null
            while (true) {
                val page = store.loadActiveVectors("scale-A", "synthetic-768-v1",
                    after, limit = 256)
                if (page.isEmpty()) break
                loaded += page.size
                after = page.last().cursor
            }
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val bytes = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
                .filter { it.exists() }.sumOf { it.length() }
            val report = "ATLAS_VISUAL_SCALE rows=$loaded dimension=$dimension " +
                "stage_ms=${indexedStart - began} " +
                "index_ms=${loadStart - indexedStart} load_ms=$loadMs db_bytes=$bytes"
            Log.i("ATLAS_VISUAL_SCALE", report)
            println(report)
            assertEquals(count, loaded)
        }
    }
}
