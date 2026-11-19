package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #31: the last published epoch survives a reopen only as a dormant epoch.
 * It is unreadable until the host revives it after re-checking the grant,
 * and any invalidation or new snapshot drops it for good. Invented keys only.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexDormantEpochTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "face-dormant-" + UUID.randomUUID()).apply { mkdirs() }
    private val file = File(dir, "faces.db")
    private val opened = mutableListOf<FaceIndexStore>()
    private val a = IndexedMedia("content://synthetic/primary/image/1#photo", "generation:1")
    private val b = IndexedMedia("content://synthetic/primary/image/2#photo", "generation:1")

    private fun open(): FaceIndexStore = FaceIndexStore(object : ContextWrapper(target) {
        override fun getDatabasePath(name: String): File = file
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(
            file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }).also { opened += it }

    /** Closes every connection, like a process death, and opens a fresh one. */
    private fun reopen(): FaceIndexStore {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        return open()
    }

    private fun publish(store: FaceIndexStore, epoch: String, rows: List<IndexedMedia>,
                        keep: Boolean = false) {
        store.beginSnapshot(epoch, keepActiveReadable = keep)
        store.stageBatch(epoch, rows)
        store.commitSnapshot(epoch, "detector-1")
    }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test fun reopenKeepsTheLastEpochDormantUntilRevivedOnce() {
        publish(open(), "epoch-A", listOf(a, b))
        val store = reopen()
        assertNull("nothing is readable after a reopen", store.activeEpoch())
        assertNull(store.progress("epoch-A"))
        assertEquals("epoch-A", store.reviveDormantEpoch())
        assertEquals("epoch-A", store.activeEpoch())
        assertEquals(2, store.progress("epoch-A")?.pending)
        assertNull("a dormant epoch revives once", store.reviveDormantEpoch())
        // A second reopen without a new publication keeps the same dormant epoch.
        val again = reopen()
        assertEquals("epoch-A", again.reviveDormantEpoch())
    }

    @Test fun twoReopensInARowKeepTheDormantEpoch() {
        publish(open(), "epoch-A", listOf(a))
        reopen()
        val store = reopen()
        assertEquals("epoch-A", store.reviveDormantEpoch())
    }

    @Test fun permissionInvalidationDropsTheDormantEpoch() {
        publish(open(), "epoch-A", listOf(a))
        reopen().invalidatePermissionScope()
        assertNull(reopen().reviveDormantEpoch())
    }

    @Test fun aBlindSnapshotDropsTheDormantEpoch() {
        publish(open(), "epoch-A", listOf(a))
        val store = reopen()
        store.beginSnapshot("epoch-B")
        assertNull(store.reviveDormantEpoch())
        store.invalidateSnapshotIfCurrent("epoch-B")
        assertNull(reopen().reviveDormantEpoch())
    }

    @Test fun keepReadableServesTheOldCatalogueUntilTheNewOneCommits() {
        val store = open()
        publish(store, "epoch-A", listOf(a))
        store.beginSnapshot("epoch-B", keepActiveReadable = true)
        store.stageBatch("epoch-B", listOf(a, b))
        assertEquals("epoch-A", store.activeEpoch())
        assertEquals("old catalogue still reads while B stages", 1,
            store.progress("epoch-A")?.pending)
        assertNull(store.progress("epoch-B"))
        store.commitSnapshot("epoch-B", "detector-1")
        assertEquals("epoch-B", store.activeEpoch())
        assertNull(store.progress("epoch-A"))
        assertEquals(2, store.progress("epoch-B")?.pending)
    }

    @Test fun aFailedStagedBehindPublicationHidesBothEpochs() {
        val store = open()
        publish(store, "epoch-A", listOf(a))
        store.beginSnapshot("epoch-B", keepActiveReadable = true)
        // The publisher's failure path: fail closed, the carried catalogue too.
        store.invalidateSnapshotIfCurrent("epoch-B")
        assertNull(store.activeEpoch())
        assertNull(store.progress("epoch-A"))
        assertNull(reopen().reviveDormantEpoch())
    }

    @Test fun revivedEpochCanBeReplacedBehindItself() {
        publish(open(), "epoch-A", listOf(a))
        val store = reopen()
        assertNotNull(store.reviveDormantEpoch())
        publish(store, "epoch-B", listOf(a, b), keep = true)
        assertEquals("epoch-B", store.activeEpoch())
        assertEquals("epoch-B", reopen().reviveDormantEpoch())
    }
}
