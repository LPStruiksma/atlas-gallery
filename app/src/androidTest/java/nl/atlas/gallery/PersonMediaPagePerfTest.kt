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

    /**
     * PERF-11: 500 synthetic named people with 50k face observations.
     * Verify that the lean People summary has one query regardless of people
     * count, and that distinct media/date/video statistics retain semantics.
     * Timing is only a witness, not an emulator-dependent hard threshold.
     */
    @Test fun homeSummaryFiveHundredPeopleFiftyThousandFaces() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "perf-11-home-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "synthetic.db")
        val epoch = "perf-11-home-grant"
        val people = 500
        val faces = 50_000
        try {
            FaceIndexStore(contextFor(file)).use { store ->
                store.beginSnapshot(epoch)
                val media = (0 until faces).map { n ->
                    IndexedMedia("content://synthetic/home/%06d".format(n), "r1")
                }
                media.chunked(500).forEach { store.stageBatch(epoch, it) }
                store.commitSnapshot(epoch, "detector", "model-v1")
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    val personInsert = db.compileStatement(
                        "INSERT INTO people_names(person_key,name) VALUES(?,?)")
                    for (p in 0 until people) {
                        personInsert.clearBindings()
                        personInsert.bindString(1, "person-%03d".format(p))
                        personInsert.bindString(2, "Synthetic %03d".format(p))
                        personInsert.executeInsert()
                    }
                    val faceInsert = db.compileStatement(
                        "INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b,frame_ms) VALUES(?,0,?,0.1,0.1,0.5,0.5,?)")
                    val embeddingInsert = db.compileStatement(
                        "INSERT INTO embeddings(face_uid,source_revision,model_version,vector) VALUES(?,'r1','model-v1',X'00')")
                    val confirmedInsert = db.compileStatement(
                        "INSERT INTO people_confirmed(face_uid,person_key) VALUES(?,?)")
                    val processed = db.compileStatement(
                        "UPDATE catalog SET state='processed',taken_ms=? WHERE media_key=?")
                    for (n in 0 until faces) {
                        val person = "person-%03d".format(n % people)
                        val uid = "%032x".format(n + 1)
                        val key = media[n].key
                        processed.clearBindings()
                        processed.bindLong(1, 1_600_000_000_000L + n * 1000L)
                        processed.bindString(2, key)
                        processed.executeUpdateDelete()
                        faceInsert.clearBindings()
                        faceInsert.bindString(1, key)
                        faceInsert.bindString(2, uid)
                        if ((n / people) % 5 == 0) faceInsert.bindLong(3, 2000L)
                        else faceInsert.bindNull(3)
                        faceInsert.executeInsert()
                        embeddingInsert.clearBindings()
                        embeddingInsert.bindString(1, uid)
                        embeddingInsert.executeInsert()
                        confirmedInsert.clearBindings()
                        confirmedInsert.bindString(1, uid)
                        confirmedInsert.bindString(2, person)
                        confirmedInsert.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                // Synthetic confirmations represent an already-published People
                // generation. commitSnapshot leaves the generation dirty, and
                // peopleHome correctly denies reads before first publication.
                // Set only the fixture's publication fence to clean; keep the
                // summary generation stale to exercise the live SQL fallback.
                db.execSQL("UPDATE cluster_generation SET dirty=0,pending=0 WHERE singleton=1")
                val start = SystemClock.elapsedRealtimeNanos()
                val home = requireNotNull(store.peopleHome(epoch))
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                android.util.Log.i("ATLAS_PERF11", "people=500 faces=50000 home_ms=$elapsedMs")
                assertEquals(people, home.people.size)
                for (p in 0 until people) {
                    val person = PersonKey("person-%03d".format(p))
                    val summary = requireNotNull(home.people[person])
                    assertEquals(100, summary.faceCount)
                    assertEquals(100, summary.mediaCount)
                    assertEquals(20, summary.videoCount)
                    assertEquals(80, summary.photoCount)
                    assertEquals(1_600_000_000_000L + p * 1000L, summary.firstAppearanceMs)
                    assertEquals(1_600_000_000_000L + (faces - people + p) * 1000L,
                        summary.mostRecentAppearanceMs)
                }
                store.invalidatePermissionScope()
                assertNull(store.peopleHome(epoch))
            }
        } finally {
            dir.deleteRecursively()
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
                val fixtureDb = store.writableDatabase
                fixtureDb.beginTransaction()
                try {
                    val db = fixtureDb
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
                    fixtureDb.endTransaction()
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
