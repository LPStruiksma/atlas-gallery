package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * PERF-11: synthetic-only SQL scale witness for bounded person media reads.
 * No actual phone photos, face vectors, names or grants are imported.
 * Times are logged for Review, not asserted against emulator noise.
 */
@RunWith(AndroidJUnit4::class)
class PersonMediaPagePerfTest {
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

    @Test fun personKeysetPagesFiveThousandMediaAndRespectsEpoch() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "perf-11-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "synthetic.db")
        val epoch = "perf-11-grant"
        val person = PersonKey("synthetic-person")
        val count = 5_000
        try {
            FaceIndexStore(contextFor(file)).use { store ->
                val media = (0 until count).map { n ->
                    IndexedMedia("content://synthetic/media/%06d".format(n), "r1")
                }
                store.beginSnapshot(epoch)
                media.chunked(500).forEach { store.stageBatch(epoch, it) }
                store.commitSnapshot(epoch, "detector", "model-v1")
                // SQL fixture builds only opaque, synthetic index rows. No
                // scanner runs and no fabricated biometric vectors are used.
                store.writableDatabase.beginTransaction()
                try {
                    val db = store.writableDatabase
                    db.execSQL("INSERT INTO people_names(person_key,name) VALUES(?,?)",
                        arrayOf(person.value, "Synthetic"))
                    val faceInsert = db.compileStatement(
                        "INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b) VALUES(?,0,?,0.1,0.1,0.5,0.5)")
                    val embeddingInsert = db.compileStatement(
                        "INSERT INTO embeddings(face_uid,source_revision,model_version,vector) VALUES(?,?,'model-v1',X'00')")
                    val confirmInsert = db.compileStatement(
                        "INSERT INTO people_confirmed(face_uid,person_key) VALUES(?,?)")
                    val processed = db.compileStatement(
                        "UPDATE catalog SET state='processed' WHERE media_key=?")
                    for (n in media.indices) {
                        val uid = "%032x".format(n + 1)
                        val key = media[n].key
                        processed.clearBindings(); processed.bindString(1, key); processed.executeUpdateDelete()
                        faceInsert.clearBindings(); faceInsert.bindString(1, key)
                        faceInsert.bindString(2, uid); faceInsert.executeInsert()
                        embeddingInsert.clearBindings(); embeddingInsert.bindString(1, uid)
                        embeddingInsert.bindString(2, "r1"); embeddingInsert.executeInsert()
                        confirmInsert.clearBindings(); confirmInsert.bindString(1, uid)
                        confirmInsert.bindString(2, person.value); confirmInsert.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    store.writableDatabase.endTransaction()
                }
                // The same read fence as the current production API.
                // Earlier helper epochs must not become readable after revoke.
                val started = SystemClock.elapsedRealtimeNanos()
                val observed = ArrayList<IndexedMedia>(count)
                var cursor: String? = null
                while (true) {
                    val page = store.personMediaPage(epoch, person, cursor, 96)
                    assertTrue(page.size <= 96)
                    if (page.isEmpty()) break
                    observed += page
                    cursor = page.last().key
                }
                val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                android.util.Log.i("ATLAS_PERF11", "person_rows=$count page_size=96 query_ms=$ms")
                assertEquals(media, observed)
                assertTrue(store.personMediaPage(epoch, person, cursor, 96).isEmpty())
                assertEquals(media.take(96), store.personMediaPage(epoch, person, pageSize=96))
                assertEquals(media.take(500), store.personMediaPage(epoch, person, pageSize=500))
                store.invalidatePermissionScope()
                assertTrue(store.personMediaPage(epoch, person).isEmpty())
                assertTrue(store.personMediaPage("invalid-epoch", person).isEmpty())
                try {
                    store.personMediaPage(epoch, person, pageSize=513)
                    fail("oversized page accepted")
                } catch (_: IllegalArgumentException) { }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
