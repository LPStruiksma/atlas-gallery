package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #47: the face and visual stores run in write-ahead-log mode, so a read
 * completes (and sees the last commit) while a long write transaction is
 * open, and a reverted build without WAL still opens the same file.
 *
 * Without WAL Android gives a database one connection, so the read below
 * waits for the writer's [HOLD_MS] and the [READ_LIMIT_MS] bound fails.
 * Synthetic opaque keys only; every test has its own cache-dir file.
 */
@RunWith(AndroidJUnit4::class)
class StoreWriteAheadLogTest {
    private companion object {
        const val READ_LIMIT_MS = 5_000L
        const val HOLD_MS = 20_000L
    }

    private val faceA = IndexedMedia("content://synthetic/primary/image/1#photo", "generation:1")
    private val faceB = IndexedMedia("content://synthetic/primary/image/2#photo", "generation:1")
    private val visualA = VisualMedia("content://synthetic/primary/1", "generation:1")
    private val visualB = VisualMedia("content://synthetic/primary/2", "generation:1")

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

    private inline fun withFile(test: (File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-wal-" + UUID.randomUUID())
        check(dir.mkdirs())
        try { test(File(dir, "store_fixture.db")) } finally { dir.deleteRecursively() }
    }

    private fun journalMode(db: SQLiteDatabase): String =
        db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst()); it.getString(0) }

    private fun publishFaces(store: FaceIndexStore, epoch: String) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, listOf(faceA, faceB))
        store.commitSnapshot(epoch, "detector-1")
    }

    private fun publishVisual(store: VisualIndexStore, epoch: String) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, listOf(visualA, visualB))
        store.commitSnapshot(epoch, "siglip-v1", 4)
    }

    /**
     * Opens a write transaction on another thread, runs [uncommitted] in it,
     * then runs [read] while it is still open. The writer rolls back.
     */
    private fun <T> readDuringOpenWrite(
        db: SQLiteDatabase, uncommitted: String, read: () -> T
    ): T {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                db.beginTransaction()
                try {
                    db.execSQL(uncommitted)
                    entered.countDown()
                    release.await(HOLD_MS, TimeUnit.MILLISECONDS)
                } finally { db.endTransaction() } // never successful: rolls back
            } catch (t: Throwable) {
                writerError.set(t); entered.countDown()
            }
        }
        val reader = Executors.newSingleThreadExecutor()
        try {
            writer.start()
            check(entered.await(READ_LIMIT_MS, TimeUnit.MILLISECONDS)) { "writer never started" }
            writerError.get()?.let { throw it }
            val started = System.nanoTime()
            val result = try {
                reader.submit(read).get(READ_LIMIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                fail("read waited for the open write transaction (no WAL?)"); throw e
            }
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            android.util.Log.i("ATLAS_WAL", "read during open write: $ms ms")
            return result
        } finally {
            release.countDown()
            writer.join(HOLD_MS)
            reader.shutdownNow()
            writerError.get()?.let { throw it }
        }
    }

    @Test fun faceReadSeesLastCommitWhileAWriteTransactionIsOpen() = withFile { file ->
        FaceIndexStore(contextFor(file)).use { store ->
            publishFaces(store, "epoch-A")
            assertEquals("wal", journalMode(store.writableDatabase))
            val seen = readDuringOpenWrite(store.writableDatabase,
                "UPDATE catalog SET state='failed'") { store.progress("epoch-A") }
            // The uncommitted 'failed' rows are invisible; the last commit is read.
            assertEquals(2, seen?.pending)
            assertEquals(0, seen?.failed)
            assertEquals(0, store.progress("epoch-A")?.failed) // writer rolled back
        }
    }

    @Test fun visualReadSeesLastCommitWhileAWriteTransactionIsOpen() = withFile { file ->
        VisualIndexStore(contextFor(file)).use { store ->
            publishVisual(store, "grant-A")
            assertEquals("wal", journalMode(store.writableDatabase))
            val seen = readDuringOpenWrite(store.writableDatabase,
                "UPDATE catalog SET state='running'") { store.progress("grant-A") }
            assertEquals(2, seen?.queued)
            assertEquals(0, seen?.running)
        }
    }

    /** A plain revert: same file, same schema version, no WAL request. */
    private class RevertedHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 3) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) = fail("existing file must not be recreated")
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            fail("revert must not upgrade")
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            fail("revert must not downgrade")
    }

    private fun catalogRows(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM catalog", null).use { check(it.moveToFirst()); it.getInt(0) }

    @Test fun revertedBuildWithoutWalOpensBothFiles() = withFile { file ->
        val faceFile = File(file.parentFile, "face.db")
        val visualFile = File(file.parentFile, "visual.db")
        FaceIndexStore(contextFor(faceFile)).use { publishFaces(it, "epoch-A") }
        VisualIndexStore(contextFor(visualFile)).use { publishVisual(it, "grant-A") }

        RevertedHelper(contextFor(faceFile), "atlas_face_discovery.db").use {
            val db = it.writableDatabase
            assertEquals(3, db.version)
            assertEquals(2, catalogRows(db))
        }
        RevertedHelper(contextFor(visualFile), "atlas_visual_index.db").use {
            val db = it.writableDatabase
            assertEquals(3, db.version)
            assertEquals(2, catalogRows(db))
        }
        // And forward again: today's build reopens the reverted file in WAL.
        FaceIndexStore(contextFor(faceFile)).use { store ->
            assertEquals("wal", journalMode(store.writableDatabase))
            assertEquals(2, catalogRows(store.readableDatabase))
        }
        VisualIndexStore(contextFor(visualFile)).use { store ->
            assertEquals("wal", journalMode(store.writableDatabase))
            assertEquals(2, catalogRows(store.readableDatabase))
        }
    }
}
