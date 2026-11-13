package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.FaceEmbeddingVectorCodec
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-V1b (#468): the additive video columns (faces.frame_ms,
 * catalog.skip_reason) on a real SQLite file. Upgrade from the shipped v3
 * schema with synthetic People review data, restart, the rollback path (a
 * reverted v3 build opening the file), a refused downgrade that keeps every
 * row, and the frame_ms / typed-skip round trip. Invented keys and
 * axis-aligned vectors only; no media, no real biometric data.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreVideoMigrationTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val photo = IndexedMedia("atlas-face-media-v1:synthetic-photo-1", "photo-rev-1")
    private val video = IndexedMedia("atlas-face-media-v1:synthetic-video-1", "video-rev-1")
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private val photoFace = "000000000000000000000000000000a1"
    private val photoFace2 = "000000000000000000000000000000a2"
    private val ana = PersonKey("person-ana")
    private fun vector(axis: Int) = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }

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
        val dir = File(base.cacheDir, "vsv1b-migration-" + UUID.randomUUID())
        check(dir.mkdirs())
        try { test(File(dir, "face_fixture.db")) } finally { dir.deleteRecursively() }
    }

    private fun grant(store: FaceIndexStore, epoch: String, rows: List<IndexedMedia>) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, rows)
        store.commitSnapshot(epoch, "detector-1", model)
        // A commit always dirties membership; publish before any People read.
        assertTrue(store.materializeClusters(epoch))
    }

    private fun count(db: SQLiteDatabase, table: String): Int {
        require(table.matches(Regex("[a-z_]+")))
        return db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private val reviewTables = listOf("people_names", "people_confirmed", "people_cannot_link",
        "people_rejected", "face_reviews", "embeddings", "faces", "catalog", "cluster_members")

    private fun reviewCounts(db: SQLiteDatabase) = reviewTables.associateWith { count(db, it) }

    /** The v3 schema exactly as shipped before VS-V1b, with synthetic review data. */
    private fun writeShippedV3(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE control(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                active_epoch TEXT, staging_epoch TEXT, detector_version TEXT,
                embedder_version TEXT, staged_count INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("""INSERT INTO control(singleton,active_epoch,detector_version,embedder_version)
                VALUES(1,'epoch-v3','detector-1',?)""", arrayOf(model))
            old.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
            old.execSQL("INSERT INTO epoch_history(epoch) VALUES('epoch-v3')")
            old.execSQL("""CREATE TABLE catalog(
                media_key TEXT PRIMARY KEY NOT NULL,
                revision TEXT NOT NULL, detector_version TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('queued','running','processed','failed')),
                attempts INTEGER NOT NULL DEFAULT 0,
                lease TEXT, lease_epoch TEXT, lease_deadline INTEGER)""")
            old.execSQL("CREATE INDEX catalog_queue ON catalog(state, media_key)")
            old.execSQL("CREATE INDEX catalog_deadline ON catalog(state, lease_deadline, media_key)")
            old.execSQL("""CREATE TABLE faces(
                media_key TEXT NOT NULL REFERENCES catalog(media_key) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL,
                face_uid TEXT NOT NULL UNIQUE CHECK(length(face_uid)=32),
                l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
                PRIMARY KEY(media_key, ordinal))""")
            old.execSQL("""CREATE TABLE face_reviews(
                media_key TEXT NOT NULL, ordinal INTEGER NOT NULL,
                decision TEXT NOT NULL CHECK(decision IN ('LATER','NOT_A_FACE')),
                PRIMARY KEY(media_key, ordinal),
                FOREIGN KEY(media_key, ordinal) REFERENCES faces(media_key, ordinal)
                    ON DELETE CASCADE)""")
            old.execSQL("CREATE TABLE stage(media_key TEXT PRIMARY KEY NOT NULL, revision TEXT NOT NULL)")
            old.execSQL("""CREATE TABLE embeddings(
                face_uid TEXT PRIMARY KEY NOT NULL REFERENCES faces(face_uid) ON DELETE CASCADE,
                source_revision TEXT NOT NULL, model_version TEXT NOT NULL,
                vector BLOB NOT NULL)""")
            old.execSQL("""CREATE TABLE cluster_members(
                face_uid TEXT PRIMARY KEY NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                anchor_uid TEXT NOT NULL)""")
            old.execSQL("""CREATE TABLE cluster_generation(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                dirty INTEGER NOT NULL CHECK(dirty IN (0,1)) DEFAULT 0,
                pending INTEGER NOT NULL DEFAULT 0,
                published INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("INSERT INTO cluster_generation(singleton,dirty,published) VALUES(1,0,1)")
            old.execSQL("""CREATE TABLE people_names(
                person_key TEXT PRIMARY KEY NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256),
                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 80))""")
            old.execSQL("""CREATE TABLE people_confirmed(
                face_uid TEXT PRIMARY KEY NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                person_key TEXT NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256))""")
            old.execSQL("CREATE INDEX people_confirmed_person ON people_confirmed(person_key)")
            old.execSQL("""CREATE TABLE people_cannot_link(
                face_a TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                face_b TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                CHECK(face_a < face_b), PRIMARY KEY(face_a, face_b))""")
            old.execSQL("CREATE INDEX people_cannot_link_b ON people_cannot_link(face_b)")
            old.execSQL("""CREATE TABLE people_rejected(
                face_uid TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                person_key TEXT NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256),
                PRIMARY KEY(face_uid, person_key))""")
            old.execSQL("""CREATE TABLE people_review_generation(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                revision INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("INSERT INTO people_review_generation(singleton,revision) VALUES(1,7)")

            // One photo, two faces: Ana confirmed, a stranger rejected for Ana,
            // a cannot-link between them, and a LATER inbox decision.
            old.execSQL("""INSERT INTO catalog(media_key,revision,detector_version,state)
                VALUES(?,?,'detector-1','processed')""", arrayOf(photo.key, photo.revision))
            for ((ordinal, uid) in listOf(photoFace, photoFace2).withIndex()) {
                old.execSQL("""INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b)
                    VALUES(?,?,?,0.1,0.2,0.5,0.8)""", arrayOf<Any>(photo.key, ordinal, uid))
                old.execSQL("""INSERT INTO embeddings(face_uid,source_revision,model_version,vector)
                    VALUES(?,?,?,?)""", arrayOf<Any>(uid, photo.revision, model,
                    FaceEmbeddingVectorCodec.encode(vector(ordinal * 7))))
            }
            old.execSQL("INSERT INTO cluster_members(face_uid,anchor_uid) VALUES(?,?)",
                arrayOf(photoFace2, photoFace2))
            old.execSQL("INSERT INTO people_names(person_key,name) VALUES(?,'Ana')", arrayOf(ana.value))
            old.execSQL("INSERT INTO people_confirmed(face_uid,person_key) VALUES(?,?)",
                arrayOf(photoFace, ana.value))
            old.execSQL("INSERT INTO people_cannot_link(face_a,face_b) VALUES(?,?)",
                arrayOf(photoFace, photoFace2))
            old.execSQL("INSERT INTO people_rejected(face_uid,person_key) VALUES(?,?)",
                arrayOf(photoFace2, ana.value))
            old.execSQL("INSERT INTO face_reviews(media_key,ordinal,decision) VALUES(?,1,'LATER')",
                arrayOf(photo.key))
            old.version = 3
        }
    }

    private val shippedCounts = mapOf("people_names" to 1, "people_confirmed" to 1,
        "people_cannot_link" to 1, "people_rejected" to 1, "face_reviews" to 1,
        "embeddings" to 2, "faces" to 2, "catalog" to 1, "cluster_members" to 1)

    @Test fun shippedV3UpgradesInPlaceKeepsEveryReviewFactAndSurvivesRestart() = withFile { file ->
        writeShippedV3(file)
        FaceIndexStore(contextFor(file)).use { store ->
            val db = store.writableDatabase
            assertEquals(3, db.version) // no version bump: a revert opens it
            assertTrue("frame_ms" in columns(db, "faces"))
            assertTrue("skip_reason" in columns(db, "catalog"))
            assertEquals(shippedCounts, reviewCounts(db))
            db.rawQuery("SELECT COUNT(*) FROM faces WHERE frame_ms IS NOT NULL", null).use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertNull(store.peopleReview("epoch-v3")) // reopen is never a grant
            grant(store, "epoch-after", listOf(photo))
            // #580: the grant's publication regrouped the file under today's rules.
            assertFalse(store.regroupPending("epoch-after"))
            val review = requireNotNull(store.peopleReview("epoch-after"))
            assertEquals(mapOf(ana to "Ana"), review.names)
            assertEquals(ana, review.clusters.reviews.confirmed[NativeFaceObservationId(photoFace)])
            assertEquals("LATER", store.reviewObservationState("epoch-after", photoFace2))
            assertNull(store.faceLocation("epoch-after", photoFace)!!.frameMs)
        }
        // Restart: the column check is idempotent and nothing is rewritten.
        FaceIndexStore(contextFor(file)).use { store ->
            val db = store.writableDatabase
            assertEquals(3, db.version)
            assertEquals(shippedCounts, reviewCounts(db))
            grant(store, "epoch-restart", listOf(photo))
            assertEquals(mapOf(ana to "Ana"), store.peopleReview("epoch-restart")?.names)
        }
    }

    /** A reverted (v3) build: same version, so neither onUpgrade nor onDowngrade may run. */
    private class RevertedV3Helper(context: Context) :
        SQLiteOpenHelper(context, "atlas_face_discovery.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) = fail("existing file must not be recreated")
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            fail("revert must not upgrade")
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            fail("revert must not downgrade")
    }

    @Test fun revertedBuildOpensTheFileAndOnlyVideoRowsArePruned() = withFile { file ->
        writeShippedV3(file)
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-video", listOf(photo, video))
            val lease = requireNotNull(store.claim("epoch-video", 1_000))
            assertEquals(video.key, lease.media.key) // the photo is already processed
            assertTrue(store.completeEmbeddings(lease, listOf(box, box),
                listOf(vector(3), vector(4)), model, listOf(2_000L, 6_000L)))
        }
        RevertedV3Helper(contextFor(file)).use { reverted ->
            val db = reverted.writableDatabase
            assertEquals(3, db.version)
            // v3 statements, verbatim column lists, still work on the file.
            db.execSQL("""UPDATE catalog SET state=CASE WHEN attempts>=3 THEN
                'failed' ELSE 'queued' END, lease=NULL, lease_epoch=NULL,
                lease_deadline=NULL WHERE state='running'""")
            db.rawQuery("""SELECT f.face_uid,f.l,f.t,f.r,f.b FROM faces f
                WHERE f.media_key=? ORDER BY f.ordinal""", arrayOf(photo.key)).use {
                assertEquals(2, it.count)
            }
            // The reverted publisher stages photos only; its commit prunes the rest.
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO stage(media_key,revision) VALUES(?,?)",
                    arrayOf(photo.key, photo.revision))
                db.execSQL("""DELETE FROM catalog WHERE NOT EXISTS
                    (SELECT 1 FROM stage WHERE stage.media_key=catalog.media_key)""")
                db.delete("stage", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            assertEquals(shippedCounts, reviewCounts(db))
        }
        // Roll forward again: the photo's facts are still there.
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-forward", listOf(photo))
            assertEquals(mapOf(ana to "Ana"), store.peopleReview("epoch-forward")?.names)
        }
    }

    @Test fun refusedDowngradeKeepsEveryRow() = withFile { file ->
        writeShippedV3(file)
        FaceIndexStore(contextFor(file)).use { it.writableDatabase }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 4 } // a future build
        try {
            FaceIndexStore(contextFor(file)).use { it.writableDatabase }
            fail("an unknown newer schema must not be opened")
        } catch (_: SQLiteException) { }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            assertEquals(4, db.version)
            assertEquals(shippedCounts, reviewCounts(db))
        }
    }

    @Test fun videoFramesRoundTripAndTypedSkipIsTerminalUntilARevisionChange() = withFile { file ->
        val broken = IndexedMedia("atlas-face-media-v1:synthetic-video-2", "video-rev-1")
        FaceIndexStore(contextFor(file)).use { store ->
            val epoch = "epoch-round-trip"
            grant(store, epoch, listOf(video, broken))
            var now = 1_000L
            val first = requireNotNull(store.claim(epoch, now++))
            val second = requireNotNull(store.claim(epoch, now++))
            val (clip, bad) = if (first.media == video) first to second else second to first
            try {
                store.completeEmbeddings(clip, listOf(box), listOf(vector(1)), model, listOf(-5L))
                fail("negative frame time accepted")
            } catch (_: IllegalArgumentException) { }
            try {
                store.completeEmbeddings(clip, listOf(box, box), listOf(vector(1), vector(2)),
                    model, listOf(1L))
                fail("frame/box count mismatch accepted")
            } catch (_: IllegalArgumentException) { }
            assertTrue(store.completeEmbeddings(clip, listOf(box, box),
                listOf(vector(1), vector(2)), model, listOf(2_000L, 6_000L)))
            assertTrue(store.skip(bad, "UNREADABLE_VIDEO"))
            assertFalse("a skip is not repeatable", store.skip(bad, "UNREADABLE_VIDEO"))

            assertEquals(listOf(2_000L, 6_000L),
                store.observationRecords(epoch, video.key).map { it.frameMs })
            assertEquals(listOf(2_000L, 6_000L),
                store.pendingFaceReviewPage(epoch).map { it.observation.frameMs })
            val uid = store.observationRecords(epoch, video.key).first().nativeFaceUid
            assertEquals(2_000L, store.faceLocation(epoch, uid)!!.frameMs)
            val progress = requireNotNull(store.progress(epoch))
            assertEquals(1, progress.processed)
            assertEquals(1, progress.failed)
            assertEquals("UNREADABLE_VIDEO", store.skipReason(epoch, broken.key))
            assertNull(store.claim(epoch, now++)) // skipped: no retry

            // Video faces cluster and search like photo faces, with their moment.
            assertTrue(store.materializeClusters(epoch))
            assertNotNull(store.peopleReview(epoch))
            val hits = FaceQueryEngine.search(store.faceSearchCorpus(epoch),
                FaceQuery(model, listOf(vector(2)))).hits
            assertEquals(6_000L, hits.single().bestFrameMs)

            // Editing the broken video (new revision) queues it again, skip cleared.
            val edited = broken.copy(revision = "video-rev-2")
            grant(store, "epoch-edited", listOf(video, edited))
            assertNull(store.skipReason("epoch-edited", broken.key))
            val retry = requireNotNull(store.claim("epoch-edited", now++))
            assertEquals(edited, retry.media)
            // The unchanged video keeps its faces and frame times.
            assertEquals(listOf(2_000L, 6_000L),
                store.observationRecords("epoch-edited", video.key).map { it.frameMs })
        }
    }
}
