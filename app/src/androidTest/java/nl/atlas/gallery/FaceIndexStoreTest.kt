package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceReviewCounts
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import nl.atlas.gallery.featureface.onnx.SFaceModel

/**
 * Only invented opaque media keys and normalized geometry; no photos or real
 * biometric data. Every test has a distinct app-cache DB, not the live index.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreTest {
    private val a = IndexedMedia("content://synthetic/primary/image/1#photo", "generation:1")
    private val b = IndexedMedia("content://synthetic/primary/image/2#photo", "generation:1")
    private val box = IndexedFaceBox(0.1f,0.2f,0.5f,0.8f)

    private fun fixture(maxCatalogMedia: Int = FaceIndexStore.MAX_CATALOG_MEDIA): Pair<FaceIndexStore, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "lane-x-face-" + UUID.randomUUID())
        check(dir.mkdirs())
        val dbFile = File(dir,"face_fixture.db")
        return FaceIndexStore(contextFor(dbFile), maxCatalogMedia) to dbFile
    }
    private fun contextFor(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file,factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,errorHandler)
        }
    }
    private inline fun withStore(test: (FaceIndexStore) -> Unit) {
        val (store, dbFile) = fixture()
        try { store.use(test) } finally { dbFile.parentFile?.deleteRecursively() }
    }
    private fun stage(store: FaceIndexStore, epoch: String = "epoch-A",
                      rows: List<IndexedMedia> = listOf(a,b), model: String = "detector-1") {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch,rows)
        store.commitSnapshot(epoch,model)
    }

    @Test fun hiddenUntilAtomicCatalogCommitAndEpochMatchedReads() = withStore { db ->
        db.beginSnapshot("epoch-A")
        db.stageBatch("epoch-A",listOf(a))
        assertNull(db.progress("epoch-A"))
        assertNull(db.claim("epoch-A",1))
        db.commitSnapshot("epoch-A","detector-1")
        assertEquals(1,db.progress("epoch-A")?.pending)
        assertNull(db.progress("epoch-other"))
        assertNull(db.claim("epoch-other",2))
    }

    @Test fun claimsAndCompletesOnlyExactLeaseOnce() = withStore { db ->
        stage(db,rows=listOf(a))
        val claim = requireNotNull(db.claim("epoch-A",1000))
        assertNull(db.claim("epoch-B",1000))
        assertFalse(db.complete(claim.copy(token="forged"),listOf(box)))
        assertTrue(db.complete(claim,listOf(box)))
        assertFalse(db.complete(claim,listOf(box)))
        assertEquals(listOf(box),db.observations("epoch-A",a.key))
        assertTrue(db.observations("epoch-B",a.key).isEmpty())
        assertEquals(1,db.progress("epoch-A")?.processed)
        assertEquals(1,db.progress("epoch-A")?.observations)
    }

    @Test fun revokeHidesResultsAndRejectsLateCompletion() = withStore { db ->
        stage(db,rows=listOf(a))
        val claim = requireNotNull(db.claim("epoch-A",1000))
        db.beginSnapshot("epoch-B")
        assertNull(db.progress("epoch-A"))
        assertFalse(db.complete(claim,listOf(box)))
        assertTrue(db.release(claim)) // old worker can yield its OWN claim
        db.stageBatch("epoch-B",listOf(a))
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals(1,db.progress("epoch-B")?.pending)
        assertEquals(0,db.progress("epoch-B")?.observations)
        assertFalse(db.complete(claim,listOf(box)))
        assertEquals(0,db.progress("epoch-B")?.processed)
    }

    @Test fun preservedDecisionsOnlyForSameMediaAndDetectorRevision() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        assertTrue(db.review("epoch-A",a.key,0,"LATER"))
        assertEquals("LATER",db.reviewState("epoch-A",a.key,0))
        stage(db,epoch="epoch-B",rows=listOf(a))
        assertEquals("LATER",db.reviewState("epoch-B",a.key,0))
        assertNull(db.reviewState("epoch-A",a.key,0))
        stage(db,epoch="epoch-C",rows=listOf(a.copy(revision="generation:2")))
        assertTrue(db.observations("epoch-C",a.key).isEmpty())
        assertNull(db.reviewState("epoch-C",a.key,0))
    }

    @Test fun inaccessibleMediaPrunedAndNoFilenameOnlyReassociation() = withStore { db ->
        stage(db)
        val one = requireNotNull(db.claim("epoch-A",1000))
        assertTrue(db.complete(one,listOf(box)))
        stage(db,epoch="epoch-B",rows=listOf(b))
        assertTrue(db.observations("epoch-B",a.key).isEmpty())
        assertEquals(1,db.progress("epoch-B")?.pending)
        assertEquals(0,db.progress("epoch-B")?.observations)
    }

    @Test fun failedCatalogBatchAndStaleCommitLeaveNoVisiblePartialSnapshot() = withStore { db ->
        stage(db,rows=listOf(a))
        db.beginSnapshot("epoch-B")
        assertNull(db.progress("epoch-A"))
        db.stageBatch("epoch-B",listOf(a))
        try {
            db.stageBatch("epoch-B",listOf(a.copy(revision="changed")))
            fail("Contradictory source revision was staged")
        } catch (_: IllegalArgumentException) { }
        try {
            db.commitSnapshot("epoch-A","detector-1")
            fail("Stale catalog commit was allowed")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.progress("epoch-B"))
        // The contradictory stage must be irrecoverable in the same epoch.
        try {
            db.commitSnapshot("epoch-B","detector-1")
            fail("Aborted snapshot was publishable")
        } catch (_: IllegalArgumentException) { }
        db.beginSnapshot("epoch-C")
        db.stageBatch("epoch-C",listOf(a))
        db.commitSnapshot("epoch-C","detector-1")
        assertEquals(1,db.progress("epoch-C")?.pending)
    }

    @Test fun releaseDoesNotSpendRetryButFailuresDo() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.release(requireNotNull(db.claim("epoch-A",1000))))
        for (n in 1..3) {
            val lease = requireNotNull(db.claim("epoch-A",1000L+n))
            assertTrue(db.fail(lease))
        }
        assertEquals(1,db.progress("epoch-A")?.failed)
        assertNull(db.claim("epoch-A",2000))
    }

    @Test fun crashedClaimReclaimedWithoutAcceptingOldToken() = withStore { db ->
        stage(db,rows=listOf(a))
        val abandoned = requireNotNull(db.claim("epoch-A",1000,10))
        val reclaimed = requireNotNull(db.claim("epoch-A",1100,10))
        assertNotEquals(abandoned.token,reclaimed.token)
        assertFalse(db.complete(abandoned,listOf(box)))
        assertTrue(db.complete(reclaimed,listOf(box)))
        assertEquals(1,db.progress("epoch-A")?.processed)
    }

    @Test fun independentRestartResetsRunningLeases() = withStore { db ->
        stage(db,rows=listOf(a))
        val pending = requireNotNull(db.claim("epoch-A",1000))
        db.recoverInterrupted()
        assertFalse(db.complete(pending,listOf(box)))
        assertNotNull(db.claim("epoch-A",1001))
    }

    @Test fun stagedCatalogCapCountsUniqueKeysNotDuplicateRowsAndAbortsOverflow() {
        val (store,file) = fixture(maxCatalogMedia=3)
        try {
            store.use { db ->
                db.beginSnapshot("epoch-A")
                db.stageBatch("epoch-A", listOf(a,b))
                db.stageBatch("epoch-A", listOf(a)) // same key/revision, no extra row
                db.stageBatch("epoch-A", listOf(IndexedMedia(
                    "content://synthetic/photo/3", "version-1")))
                try {
                    db.stageBatch("epoch-A", listOf(IndexedMedia(
                        "content://synthetic/photo/4", "version-1")))
                    fail("Over-cap catalog was accepted")
                } catch (_: IllegalArgumentException) { }
                assertNull(db.progress("epoch-A"))
                try {
                    db.commitSnapshot("epoch-A","detector-1")
                    fail("Over-cap staged rows were publishable")
                } catch (_: IllegalArgumentException) { }
                db.beginSnapshot("epoch-B")
                db.stageBatch("epoch-B",listOf(a,b))
                db.commitSnapshot("epoch-B","detector-1")
                assertEquals(2,db.progress("epoch-B")?.pending)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun explicitRevocationImmediatelyHidesAllReadsWithoutNewEpoch() = withStore { db ->
        stage(db, rows=listOf(a))
        val lease = requireNotNull(db.claim("epoch-A",1000))
        assertTrue(db.complete(lease,listOf(box)))
        val uid = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        assertTrue(db.reviewObservation("epoch-A",uid,"LATER"))
        db.invalidatePermissionScope()
        assertNull(db.progress("epoch-A"))
        assertTrue(db.observations("epoch-A",a.key).isEmpty())
        assertNull(db.reviewObservationState("epoch-A",uid))
        assertFalse(db.reviewObservation("epoch-A",uid,"NOT_A_FACE"))
        assertNull(db.claim("epoch-A",1100))
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a))
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals("LATER",db.reviewObservationState("epoch-B",uid))
    }

    @Test fun balancedIgnoredDeleteAndInsertCannotForgeCompleteCatalog() = withStore { db ->
        // Existing A leaves the current Android grant; B becomes authorized.
        stage(db,rows=listOf(a))
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(b))
        // BOTH operations are suppressed: catalog still contains A, not B.
        // Cardinality is 1 on both sides, so COUNT alone gives a false PASS.
        db.writableDatabase.execSQL(
            """CREATE TRIGGER retain_inaccessible BEFORE DELETE ON catalog
                WHEN OLD.media_key='${a.key}'
                BEGIN SELECT RAISE(IGNORE); END"""
        )
        db.writableDatabase.execSQL(
            """CREATE TRIGGER skip_authorized BEFORE INSERT ON catalog
                WHEN NEW.media_key='${b.key}'
                BEGIN SELECT RAISE(IGNORE); END"""
        )
        try {
            db.commitSnapshot("epoch-B","detector-1")
            fail("Balanced ignored writes falsely published inaccessible A instead of B")
        } catch (_: IllegalStateException) { }
        // Failed publish rolls back to a previously stored catalog, but its
        // permission was revoked by beginSnapshot and must remain invisible.
        assertNull(db.progress("epoch-B"))
        assertTrue(db.observationRecords("epoch-B",a.key).isEmpty())
        assertTrue(db.observationRecords("epoch-B",b.key).isEmpty())
        assertTrue(db.invalidateSnapshotIfCurrent("epoch-B"))
        db.writableDatabase.execSQL("DROP TRIGGER retain_inaccessible")
        db.writableDatabase.execSQL("DROP TRIGGER skip_authorized")
        stage(db,epoch="epoch-C",rows=listOf(b))
        assertEquals(1,db.progress("epoch-C")?.pending)
        assertNull(db.claim("epoch-B",1000))
        val valid = requireNotNull(db.claim("epoch-C",1000))
        assertEquals(b,valid.media)
        assertNull(db.claim("epoch-C",1001))
    }

    @Test fun ignoredCatalogInsertNeverPublishesPartialPermissionInventory() = withStore { db ->
        db.beginSnapshot("epoch-A")
        db.stageBatch("epoch-A",listOf(a,b))
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_second_catalog BEFORE INSERT ON catalog
               WHEN NEW.media_key='${b.key}'
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        try {
            db.commitSnapshot("epoch-A","detector-1")
            fail("Ignored second catalogue insert published incomplete inventory")
        } catch (_: IllegalStateException) { }
        assertNull(db.progress("epoch-A"))
        assertNull(db.claim("epoch-A",1000))
        assertTrue(db.invalidateSnapshotIfCurrent("epoch-A"))
        db.writableDatabase.execSQL("DROP TRIGGER ignore_second_catalog")
        stage(db,epoch="epoch-B",rows=listOf(a,b))
        assertEquals(2,db.progress("epoch-B")?.pending)
    }

    @Test fun ignoredRevisionUpdateDoesNotPublishObsoleteFaceBox() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        val oldUid = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a.copy(revision="generation:2")))
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_catalog_edit BEFORE UPDATE ON catalog
               WHEN NEW.revision='generation:2'
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        try {
            db.commitSnapshot("epoch-B","detector-1")
            fail("Ignored catalogue revision update published stale face boxes")
        } catch (_: IllegalStateException) { }
        assertNull(db.progress("epoch-B"))
        assertTrue(db.observationRecords("epoch-B",a.key).isEmpty())
        assertTrue(db.invalidateSnapshotIfCurrent("epoch-B"))
        db.writableDatabase.execSQL("DROP TRIGGER ignore_catalog_edit")
        stage(db,epoch="epoch-C",rows=listOf(a.copy(revision="generation:2")))
        assertTrue(db.observationRecords("epoch-C",a.key).isEmpty())
        assertTrue(db.complete(requireNotNull(db.claim("epoch-C",1001)),listOf(box)))
        assertNotEquals(oldUid,
            db.observationRecords("epoch-C",a.key).single().nativeFaceUid)
    }

    @Test fun oldCancelledScannerCannotHideNewerStagedOrPublishedScope() = withStore { db ->
        stage(db, rows=listOf(a))
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a,b))
        assertFalse(db.invalidateSnapshotIfCurrent("epoch-A"))
        // Staged B must still commit; the stale A finalizer cannot erase it.
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals(2,db.progress("epoch-B")?.pending)
        assertFalse(db.invalidateSnapshotIfCurrent("epoch-A"))
        assertEquals(2,db.progress("epoch-B")?.pending)
        assertTrue(db.invalidateSnapshotIfCurrent("epoch-B"))
        assertNull(db.progress("epoch-B"))
        assertNull(db.claim("epoch-B",1001))
        // Epoch B is one-shot even though its owner cancelled afterward.
        try {
            db.beginSnapshot("epoch-B")
            fail("Reused cancelled epoch was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellingCurrentStagingEpochRejectsPartialPublish() = withStore { db ->
        db.beginSnapshot("epoch-A")
        db.stageBatch("epoch-A",listOf(a))
        assertTrue(db.invalidateSnapshotIfCurrent("epoch-A"))
        assertFalse(db.invalidateSnapshotIfCurrent("epoch-A"))
        try {
            db.commitSnapshot("epoch-A","detector-1")
            fail("Cancelled staged snapshot was published")
        } catch (_: IllegalArgumentException) { }
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(b))
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals(1,db.progress("epoch-B")?.pending)
    }

    @Test fun actualGrantRevocationStillInvalidatesNewerEpochRegardlessOfOwner() = withStore { db ->
        stage(db, rows=listOf(a))
        stage(db, epoch="epoch-B",rows=listOf(a,b))
        assertFalse(db.invalidateSnapshotIfCurrent("epoch-A"))
        assertNotNull(db.progress("epoch-B"))
        // This separate unconditional API is reserved for genuine grant loss.
        db.invalidatePermissionScope()
        assertNull(db.progress("epoch-B"))
        assertFalse(db.invalidateSnapshotIfCurrent("epoch-B"))
    }

    @Test fun ignoredLeaseClaimAndCompletionCannotReportSuccess() = withStore { db ->
        stage(db,rows=listOf(a))
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_claim BEFORE UPDATE ON catalog
               WHEN NEW.state='running'
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        try {
            db.claim("epoch-A",1000)
            fail("Ignored claim update produced a false lease")
        } catch (_: IllegalStateException) { }
        assertEquals(1,db.progress("epoch-A")?.pending)
        db.writableDatabase.execSQL("DROP TRIGGER ignore_claim")
        val claim = requireNotNull(db.claim("epoch-A",1000))
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_complete BEFORE UPDATE ON catalog
               WHEN NEW.state='processed'
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        try {
            db.complete(claim,listOf(box))
            fail("Ignored completion state was reported persisted")
        } catch (_: IllegalStateException) { }
        assertTrue(db.observationRecords("epoch-A",a.key).isEmpty())
        assertEquals(1,db.progress("epoch-A")?.running)
        db.writableDatabase.execSQL("DROP TRIGGER ignore_complete")
        assertTrue(db.complete(claim,listOf(box)))
        assertEquals(1,db.progress("epoch-A")?.processed)
        assertEquals(1,db.observationRecords("epoch-A",a.key).size)
    }

    @Test fun revocationBlocksInFlightCompletionBeforeNextEpochExists() = withStore { db ->
        stage(db, rows=listOf(a))
        val stale = requireNotNull(db.claim("epoch-A",1000))
        db.invalidatePermissionScope()
        assertFalse(db.complete(stale,listOf(box)))
        assertTrue(db.release(stale))
        assertNull(db.progress("epoch-A"))
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a))
        db.commitSnapshot("epoch-B","detector-1")
        assertFalse(db.complete(stale,listOf(box)))
        assertEquals(1,db.progress("epoch-B")?.pending)
    }

    @Test fun previouslyUsedEpochIsNeverReusableEvenAfterReopenAndHidesOldState() = withStore { db ->
        stage(db, rows=listOf(a))
        val stale = requireNotNull(db.claim("epoch-A",1000))
        try {
            db.beginSnapshot("epoch-A")
            fail("Epoch ABA replay was accepted")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.progress("epoch-A"))
        assertFalse(db.complete(stale,listOf(box)))
        try {
            db.stageBatch("epoch-A",listOf(a))
            fail("Reused epoch was available to stage")
        } catch (_: IllegalArgumentException) { }
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a))
        db.commitSnapshot("epoch-B","detector-1")
        try {
            db.beginSnapshot("epoch-A")
            fail("Earlier committed epoch was reused")
        } catch (_: IllegalArgumentException) { }
        // Delayed A cannot hide the independently valid newer B grant.
        assertEquals(1,db.progress("epoch-B")?.pending)
    }

    @Test fun staleEpochReplayCannotEraseNewerStagingOrCommittedGrant() = withStore { db ->
        stage(db,rows=listOf(a))
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a,b))
        try {
            db.beginSnapshot("epoch-A")
            fail("Old A worker restarted staging after B began")
        } catch (_: IllegalArgumentException) { }
        // Prove the old worker has not deleted B's staged rows.
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals(2,db.progress("epoch-B")?.pending)
        val lease = requireNotNull(db.claim("epoch-B",1000))
        assertTrue(db.complete(lease,listOf(box)))
        val uid = db.observationRecords("epoch-B",a.key).single().nativeFaceUid
        try {
            db.beginSnapshot("epoch-A")
            fail("Replayed old A must be rejected after B is active")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1,db.progress("epoch-B")?.processed)
        assertEquals(uid,db.observationRecords("epoch-B",a.key).single().nativeFaceUid)
        // But a duplicate of B itself must fail closed rather than replay
        // previously published B's private observation authority.
        try {
            db.beginSnapshot("epoch-B")
            fail("Duplicate currently active B was permitted")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.progress("epoch-B"))
        assertFalse(db.reviewObservation("epoch-B",uid,"LATER"))
    }

    @Test fun nativeObservationIdsStayStableAcrossUnchangedSnapshotsAndRotateOnEdit() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        val id = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
        stage(db,epoch="epoch-B",rows=listOf(a))
        assertEquals(id,db.observationRecords("epoch-B",a.key).single().nativeFaceUid)
        stage(db,epoch="epoch-C",rows=listOf(a.copy(revision="edited")))
        assertTrue(db.observationRecords("epoch-C",a.key).isEmpty())
        assertTrue(db.complete(requireNotNull(db.claim("epoch-C",1100)),listOf(box)))
        val replacement=db.observationRecords("epoch-C",a.key).single().nativeFaceUid
        assertNotEquals(id,replacement)
    }

    @Test fun ignoredUndoDeleteCannotPretendReviewWasCleared() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        val uid = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        assertTrue(db.reviewObservation("epoch-A",uid,"LATER"))
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_undo BEFORE DELETE ON face_reviews
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        assertFalse(db.reviewObservation("epoch-A",uid,"UNREVIEWED"))
        assertEquals("LATER",db.reviewObservationState("epoch-A",uid))
        assertFalse(db.review("epoch-A",a.key,0,"UNREVIEWED"))
        assertEquals("LATER",db.reviewState("epoch-A",a.key,0))
        db.writableDatabase.execSQL("DROP TRIGGER ignore_undo")
        assertTrue(db.reviewObservation("epoch-A",uid,"UNREVIEWED"))
        assertEquals("UNREVIEWED",db.reviewObservationState("epoch-A",uid))
        // Idempotent undo of a truly clear row remains safe.
        assertTrue(db.reviewObservation("epoch-A",uid,"UNREVIEWED"))
        assertTrue(db.review("epoch-A",a.key,0,"NOT_A_FACE"))
        assertTrue(db.review("epoch-A",a.key,0,"UNREVIEWED"))
        assertEquals("UNREVIEWED",db.reviewState("epoch-A",a.key,0))
    }

    @Test fun ignoredReviewInsertCannotPretendDecisionWasSaved() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        val uid = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        db.writableDatabase.execSQL(
            """CREATE TRIGGER ignore_review BEFORE INSERT ON face_reviews
               BEGIN SELECT RAISE(IGNORE); END"""
        )
        assertFalse(db.reviewObservation("epoch-A",uid,"LATER"))
        assertEquals("UNREVIEWED",db.reviewObservationState("epoch-A",uid))
        // Backwards-compatible ordinal path must also refuse false success.
        assertFalse(db.review("epoch-A",a.key,0,"NOT_A_FACE"))
        assertEquals("UNREVIEWED",db.reviewState("epoch-A",a.key,0))
        db.writableDatabase.execSQL("DROP TRIGGER ignore_review")
        assertTrue(db.reviewObservation("epoch-A",uid,"LATER"))
        assertEquals("LATER",db.reviewObservationState("epoch-A",uid))
    }

    @Test fun reviewByNativeUuidCannotTouchLaterReusedOrdinal() = withStore { db ->
        stage(db, rows=listOf(a))
        val original = requireNotNull(db.claim("epoch-A",1000))
        assertTrue(db.complete(original,listOf(box)))
        val originalUid = db.observationRecords("epoch-A",a.key).single().nativeFaceUid
        assertTrue(db.reviewObservation("epoch-A",originalUid,"NOT_A_FACE"))
        assertEquals("NOT_A_FACE",db.reviewObservationState("epoch-A",originalUid))
        stage(db,epoch="epoch-B", rows=listOf(a.copy(revision="v2")))
        assertNull(db.reviewObservationState("epoch-B",originalUid))
        assertFalse(db.reviewObservation("epoch-B",originalUid,"LATER"))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-B",2000)),listOf(box)))
        val newUid=db.observationRecords("epoch-B",a.key).single().nativeFaceUid
        assertNotEquals(originalUid,newUid)
        assertEquals("UNREVIEWED",db.reviewObservationState("epoch-B",newUid))
        assertNull(db.reviewObservationState("epoch-A",originalUid))
    }

    @Test fun epochReplayRefusalPersistsAfterIndependentStoreReopen() {
        val (store,file) = fixture()
        try {
            store.use { stage(it,rows=listOf(a)) }
            FaceIndexStore(contextFor(file)).use { reopened ->
                // A reopened private DB must never restore old visibility
                // before I has independently rechecked the current grant.
                assertNull(reopened.progress("epoch-A"))
                assertNull(reopened.claim("epoch-A",1000))
                try {
                    reopened.beginSnapshot("epoch-A")
                    fail("Previously committed epoch reused after process reopen")
                } catch (_: IllegalArgumentException) { }
                assertNull(reopened.progress("epoch-A"))
                reopened.beginSnapshot("epoch-B")
                reopened.stageBatch("epoch-B",listOf(a))
                reopened.commitSnapshot("epoch-B","detector-1")
                assertEquals(1,reopened.progress("epoch-B")?.pending)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun reopenRevokesOldClaimButKeepsUnchangedReviewsAfterNewGrant() {
        val (store,file) = fixture()
        try {
            var stableUid = ""
            var staleWorker: nl.atlas.gallery.featurepersistence.IndexedFaceLease? = null
            store.use { db ->
                stage(db, rows=listOf(a,b))
                assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),
                    listOf(box)))
                stableUid=db.observationRecords("epoch-A",a.key).single().nativeFaceUid
                assertTrue(db.reviewObservation("epoch-A",stableUid,"LATER"))
                staleWorker=requireNotNull(db.claim("epoch-A",1001))
                assertTrue(requireNotNull(staleWorker).token.isNotBlank())
            }
            FaceIndexStore(contextFor(file)).use { resumed ->
                assertNull(resumed.progress("epoch-A"))
                // Crash recovery must revoke the old opaque claim as well as
                // the old permission epoch, before any new grant is admitted.
                assertFalse(resumed.complete(requireNotNull(staleWorker),listOf(box)))
                assertFalse(resumed.release(requireNotNull(staleWorker)))
                assertTrue(resumed.observationRecords("epoch-A",a.key).isEmpty())
                assertNull(resumed.reviewObservationState("epoch-A",stableUid))
                resumed.beginSnapshot("epoch-B")
                resumed.stageBatch("epoch-B",listOf(a,b))
                resumed.commitSnapshot("epoch-B","detector-1")
                assertEquals(stableUid,
                    resumed.observationRecords("epoch-B",a.key).single().nativeFaceUid)
                assertEquals("LATER",
                    resumed.reviewObservationState("epoch-B",stableUid))
                assertEquals(1,resumed.progress("epoch-B")?.pending)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun reopenAbandonsUnfinishedSnapshotWithoutPublishingPartialRows() {
        val (store,file) = fixture()
        try {
            store.use { db ->
                stage(db, rows=listOf(a))
                db.beginSnapshot("epoch-B")
                db.stageBatch("epoch-B", listOf(a,b))
                assertNull(db.progress("epoch-A"))
            }
            FaceIndexStore(contextFor(file)).use { reopened ->
                assertNull(reopened.progress("epoch-A"))
                assertNull(reopened.progress("epoch-B"))
                try {
                    reopened.commitSnapshot("epoch-B","detector-1")
                    fail("Interrupted staged inventory was published after reopen")
                } catch (_: IllegalArgumentException) { }
                reopened.beginSnapshot("epoch-C")
                reopened.stageBatch("epoch-C",listOf(a))
                reopened.commitSnapshot("epoch-C","detector-1")
                assertEquals(1,reopened.progress("epoch-C")?.pending)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun contradictoryKeysWithinSingleBatchInvalidateWholeSnapshot() = withStore { db ->
        db.beginSnapshot("epoch-A")
        try {
            db.stageBatch("epoch-A",listOf(a,a.copy(revision="other")))
            fail("Two contradictory source revisions were accepted")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.progress("epoch-A"))
        try {
            db.commitSnapshot("epoch-A","detector-1")
            fail("Aborted batch was published")
        } catch (_: IllegalArgumentException) { }
        db.beginSnapshot("epoch-B")
        db.stageBatch("epoch-B",listOf(a))
        db.commitSnapshot("epoch-B","detector-1")
        assertEquals(1,db.progress("epoch-B")?.pending)
    }

    @Test fun refusesRawPathsAndOversizedBatches() = withStore { db ->
        db.beginSnapshot("epoch-A")
        try {
            db.stageBatch("epoch-A",listOf(IndexedMedia("/storage/emulated/0/DCIM/x.jpg","v1")))
            fail("Raw file path was accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            db.stageBatch("epoch-A",List(1025) { IndexedMedia("content://fictional/$it","v1") })
            fail("Oversized catalog batch was accepted")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.progress("epoch-A"))
    }

    @Test fun reviewCountersExcludeDecidedFacesAndNeverLeakAnOldGrant() = withStore { db ->
        stage(db,rows=listOf(a,b))
        assertEquals(IndexedFaceReviewCounts(0,0,0,0),db.reviewCounts("epoch-A"))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),
            listOf(box,IndexedFaceBox(0.55f,0.2f,0.95f,0.8f))))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1001)),listOf(box)))
        val faces = db.pendingFaceReviewPage("epoch-A",limit=3)
        assertEquals(3,faces.size)
        assertEquals(IndexedFaceReviewCounts(3,3,0,0),db.reviewCounts("epoch-A"))
        assertTrue(db.reviewObservation("epoch-A",faces[0].observation.nativeFaceUid,"LATER"))
        assertTrue(db.reviewObservation("epoch-A",faces[1].observation.nativeFaceUid,"NOT_A_FACE"))
        assertEquals(IndexedFaceReviewCounts(3,1,1,1),db.reviewCounts("epoch-A"))
        assertTrue(db.reviewObservation("epoch-A",faces[1].observation.nativeFaceUid,"UNREVIEWED"))
        assertEquals(IndexedFaceReviewCounts(3,2,1,0),db.reviewCounts("epoch-A"))
        db.invalidatePermissionScope()
        assertNull(db.reviewCounts("epoch-A"))
        stage(db,epoch="epoch-B",rows=listOf(a,b))
        assertEquals(IndexedFaceReviewCounts(3,2,1,0),db.reviewCounts("epoch-B"))
        assertNull(db.reviewCounts("epoch-A"))
    }

    @Test fun nativeUnknownFaceInboxPagesOnlyCurrentUnreviewedDetections() = withStore { db ->
        stage(db, rows=listOf(a,b))
        val secondBox = IndexedFaceBox(0.55f,0.2f,0.95f,0.8f)
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),
            listOf(box,secondBox)))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1001)),listOf(box)))
        val first = db.pendingFaceReviewPage("epoch-A",limit=2)
        assertEquals(2,first.size)
        assertEquals(listOf(a.key,a.key),first.map { it.cursor.mediaKey })
        assertEquals(listOf(0,1),first.map { it.cursor.ordinal })
        assertEquals("epoch-A",first[0].cursor.permissionEpoch)
        // P's resolver needs the revision as well as the canonical hash.
        assertEquals(listOf(a.revision,a.revision),
            first.map { it.media.revision })
        assertEquals(listOf(a.key,a.key),first.map { it.media.key })
        assertEquals(listOf("detector-1","detector-1"),
            first.map { it.detectorVersion })
        val remaining = db.pendingFaceReviewPage("epoch-A",first.last().cursor,limit=2)
        assertEquals(listOf(b.key),remaining.map { it.cursor.mediaKey })
        assertEquals(3,(first+remaining).map { it.observation.nativeFaceUid }.toSet().size)
        assertTrue(db.reviewObservation("epoch-A",
            first[0].observation.nativeFaceUid,"NOT_A_FACE"))
        val afterReview = db.pendingFaceReviewPage("epoch-A",limit=3)
        assertEquals(listOf(first[1].observation.nativeFaceUid,
            remaining[0].observation.nativeFaceUid),
            afterReview.map { it.observation.nativeFaceUid })
        db.invalidatePermissionScope()
        assertTrue(db.pendingFaceReviewPage("epoch-A").isEmpty())
        stage(db,epoch="epoch-B",rows=listOf(a,b))
        assertEquals(2,db.pendingFaceReviewPage("epoch-B").size)
        assertTrue(db.pendingFaceReviewPage("epoch-A").isEmpty())
        try {
            db.pendingFaceReviewPage("epoch-B",first.last().cursor)
            fail("Stale grant cursor was accepted after regrant")
        } catch (_: IllegalArgumentException) { }
        try {
            db.pendingFaceReviewPage("epoch-B",limit=129)
            fail("Unbounded page size was accepted")
        } catch (_: IllegalArgumentException) { }
    }


    @Test fun unknownInboxUsesExactProcessedDetectorVersionNotOldPublishedModel() =
        withStore { db ->
            stage(db,rows=listOf(a),model="detector-v1")
            assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
            val previous = db.pendingFaceReviewPage("epoch-A").single()
            assertEquals("detector-v1",previous.detectorVersion)

            // Changing detector version invalidates earlier rectangles/reviews.
            stage(db,epoch="epoch-B",rows=listOf(a),model="detector-v2")
            assertTrue(db.pendingFaceReviewPage("epoch-A").isEmpty())
            assertTrue(db.pendingFaceReviewPage("epoch-B").isEmpty())
            val next = requireNotNull(db.claim("epoch-B",1001))
            assertEquals("detector-v2",next.detectorVersion)
            assertTrue(db.complete(next,listOf(box)))
            val current = db.pendingFaceReviewPage("epoch-B").single()
            assertEquals(a,current.media)
            assertEquals("detector-v2",current.detectorVersion)
            assertNotEquals(previous.observation.nativeFaceUid,
                current.observation.nativeFaceUid)
            assertTrue(db.reviewObservation("epoch-B",
                current.observation.nativeFaceUid,"LATER"))
            assertTrue(db.pendingFaceReviewPage("epoch-B").isEmpty())
        }

    @Test fun unknownInboxMediaKeysArePinnedToFreshSourceRevisions() = withStore { db ->
        stage(db,rows=listOf(a))
        assertTrue(db.complete(requireNotNull(db.claim("epoch-A",1000)),listOf(box)))
        val previous = db.pendingFaceReviewPage("epoch-A").single()
        assertEquals(a,previous.media)
        assertEquals("detector-1",previous.detectorVersion)
        // A new permission epoch with unchanged authoritative MediaStore
        // revision reuses the same observation identity.
        stage(db,epoch="epoch-B",rows=listOf(a))
        val unchanged = db.pendingFaceReviewPage("epoch-B").single()
        assertEquals(a,unchanged.media)
        assertEquals(previous.observation.nativeFaceUid,unchanged.observation.nativeFaceUid)
        assertTrue(db.pendingFaceReviewPage("epoch-A").isEmpty())

        val edited = a.copy(revision="generation:2")
        stage(db,epoch="epoch-C",rows=listOf(edited))
        // Never hand an old revision/face rectangle to the UI while a changed
        // photo is awaiting a fresh detector pass.
        assertTrue(db.pendingFaceReviewPage("epoch-C").isEmpty())
        assertTrue(db.complete(requireNotNull(db.claim("epoch-C",1001)),listOf(box)))
        val refreshed = db.pendingFaceReviewPage("epoch-C").single()
        assertEquals(edited,refreshed.media)
        assertNotEquals(previous.observation.nativeFaceUid,refreshed.observation.nativeFaceUid)
        assertTrue(db.pendingFaceReviewPage("epoch-B").isEmpty())
    }


    private fun syntheticEmbedding(axis: Int = 0): FloatArray =
        FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }

    private fun tableRows(db: FaceIndexStore, name: String): Int {
        require(name in listOf("faces", "embeddings", "cluster_members"))
        return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $name", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
    }

    @Test fun embeddedReadsRejectRevokedAndSupersededEpochsAcrossThreads() = withStore { db ->
        val model = SFaceModel.EMBEDDER_VERSION
        stage(db, rows=listOf(a))
        val lease = requireNotNull(db.claim("epoch-A", 1000))
        assertTrue(db.completeEmbeddings(lease, listOf(box), listOf(syntheticEmbedding()), model))
        val saved = db.embeddingRecords("epoch-A", a.key).single()
        assertNull(db.clusterState("epoch-A"))
        assertTrue(db.materializeClusters("epoch-A"))
        assertEquals(1, db.clusterState("epoch-A")?.faces?.size)
        assertTrue(db.embeddingRecords("wrong-epoch", a.key).isEmpty())
        assertNull(db.clusterState("wrong-epoch"))

        // The worker begins only after revocation has committed, so it cannot
        // reuse a vector or cluster obtained under the old authorization.
        val started = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        val reader = Thread {
            try {
                started.countDown()
                check(revoked.await(10, TimeUnit.SECONDS))
                repeat(32) {
                    assertTrue(db.embeddingRecords("epoch-A", a.key).isEmpty())
                    assertNull(db.clusterState("epoch-A"))
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        reader.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        db.invalidatePermissionScope()
        revoked.countDown()
        reader.join(10_000)
        assertFalse("Revoked reader did not finish", reader.isAlive)
        error.get()?.let { throw it }
        assertTrue(db.embeddingRecords("epoch-A", a.key).isEmpty())
        assertNull(db.clusterState("epoch-A"))

        // An in-progress replacement epoch also hides the old vector rows.
        db.beginSnapshot("epoch-B")
        assertTrue(db.embeddingRecords("epoch-A", a.key).isEmpty())
        assertNull(db.clusterState("epoch-B"))
        db.stageBatch("epoch-B", listOf(a))
        db.commitSnapshot("epoch-B", "detector-1", model)
        assertEquals(saved.nativeFaceUid, db.embeddingRecords("epoch-B", a.key).single().nativeFaceUid)
        // #543: an identical re-grant prunes nothing, so the published
        // generation stays readable under the new epoch (and only under it).
        assertEquals(1, db.clusterState("epoch-B")?.faces?.size)
        assertTrue(db.materializeClusters("epoch-B"))
        assertEquals(1, db.clusterState("epoch-B")?.faces?.size)
        assertNull(db.clusterState("epoch-A"))
    }

    @Test fun embeddedCompletionRollbackSourceModelAndLeaseAreAtomic() = withStore { db ->
        val model = SFaceModel.EMBEDDER_VERSION
        stage(db, rows=listOf(a))
        val lease = requireNotNull(db.claim("epoch-A", 1000))
        val vectors = listOf(syntheticEmbedding())
        assertFalse(db.completeEmbeddings(lease.copy(token="wrong-token"), listOf(box), vectors, model))
        assertFalse(db.completeEmbeddings(lease, listOf(box), vectors, "$model-wrong"))

        db.writableDatabase.execSQL("""CREATE TRIGGER reject_synthetic_embedding
            BEFORE INSERT ON embeddings BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END""")
        try {
            db.completeEmbeddings(lease, listOf(box), vectors, model)
            fail("An aborted vector insert was committed")
        } catch (_: android.database.sqlite.SQLiteException) { }
        db.writableDatabase.execSQL("DROP TRIGGER reject_synthetic_embedding")
        assertEquals(0, tableRows(db, "faces"))
        assertEquals(0, tableRows(db, "embeddings"))
        assertEquals(1, db.progress("epoch-A")?.running)
        assertTrue(db.completeEmbeddings(lease, listOf(box), vectors, model))
        val old = db.embeddingRecords("epoch-A", a.key).single()
        assertNull(db.clusterState("epoch-A"))
        assertTrue(db.materializeClusters("epoch-A"))
        assertEquals(1, tableRows(db, "cluster_members"))
        assertFalse(db.completeEmbeddings(lease, listOf(box), vectors, model))

        // A changed source revision cascades old vectors and membership.
        val revised = a.copy(revision="generation:2")
        stage(db, epoch="epoch-B", rows=listOf(revised))
        assertTrue(db.embeddingRecords("epoch-B", a.key).isEmpty())
        assertEquals(0, tableRows(db, "embeddings"))
        assertEquals(0, tableRows(db, "cluster_members"))
        assertFalse(db.completeEmbeddings(lease, listOf(box), vectors, model))
        val newLease = requireNotNull(db.claim("epoch-B", 2000))
        assertTrue(db.completeEmbeddings(newLease, listOf(box), vectors, model))
        assertNotEquals(old.nativeFaceUid, db.embeddingRecords("epoch-B", a.key).single().nativeFaceUid)

        // An embedder/alignment version bump invalidates even identical bytes.
        db.beginSnapshot("epoch-C")
        db.stageBatch("epoch-C", listOf(revised))
        db.commitSnapshot("epoch-C", "detector-1", "$model-vNext")
        assertTrue(db.embeddingRecords("epoch-C", a.key).isEmpty())
        assertEquals(0, tableRows(db, "embeddings"))
        assertEquals(1, db.progress("epoch-C")?.pending)
        val bumped = requireNotNull(db.claim("epoch-C", 3000))
        assertFalse(db.completeEmbeddings(bumped, listOf(box), vectors, model))
        assertTrue(db.completeEmbeddings(bumped, listOf(box), vectors, "$model-vNext"))
        assertEquals("$model-vNext", db.embeddingRecords("epoch-C", a.key).single().modelVersion)
    }

    @Test fun realSqliteV1UpgradeRetainsCatalogAndRequiresFreshEmbeddings() {
        val (unused, file) = fixture()
        val model = SFaceModel.EMBEDDER_VERSION
        val previousId = "000000000000000000000000000000ab"
        try {
            unused.close()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("""CREATE TABLE control(
                    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                    active_epoch TEXT, staging_epoch TEXT, detector_version TEXT,
                    staged_count INTEGER NOT NULL DEFAULT 0)""")
                old.execSQL("""INSERT INTO control(singleton,active_epoch,detector_version)
                    VALUES(1,'epoch-v1','detector-1')""")
                old.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
                old.execSQL("INSERT INTO epoch_history(epoch) VALUES('epoch-v1')")
                old.execSQL("""CREATE TABLE catalog(
                    media_key TEXT PRIMARY KEY NOT NULL,
                    revision TEXT NOT NULL, detector_version TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('queued','running','processed','failed')),
                    attempts INTEGER NOT NULL DEFAULT 0,
                    lease TEXT, lease_epoch TEXT, lease_deadline INTEGER)""")
                old.execSQL("""CREATE TABLE faces(
                    media_key TEXT NOT NULL REFERENCES catalog(media_key) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, face_uid TEXT NOT NULL UNIQUE CHECK(length(face_uid)=32),
                    l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
                    PRIMARY KEY(media_key,ordinal))""")
                old.execSQL("""CREATE TABLE face_reviews(
                    media_key TEXT NOT NULL, ordinal INTEGER NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('LATER','NOT_A_FACE')),
                    PRIMARY KEY(media_key,ordinal),
                    FOREIGN KEY(media_key,ordinal) REFERENCES faces(media_key,ordinal)
                        ON DELETE CASCADE)""")
                old.execSQL("CREATE TABLE stage(media_key TEXT PRIMARY KEY NOT NULL, revision TEXT NOT NULL)")
                old.execSQL("""INSERT INTO catalog(media_key,revision,detector_version,state)
                    VALUES(?,?,'detector-1','processed')""", arrayOf(a.key, a.revision))
                old.execSQL("""INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b)
                    VALUES(?,0,?,0.1,0.2,0.5,0.8)""", arrayOf(a.key, previousId))
                old.execSQL("""INSERT INTO face_reviews(media_key,ordinal,decision)
                    VALUES(?,0,'LATER')""", arrayOf(a.key))
                old.version = 1
            }
            FaceIndexStore(contextFor(file)).use { upgraded ->
                assertEquals(FaceIndexStore.SCHEMA_VERSION, upgraded.readableDatabase.version)
                assertNull(upgraded.progress("epoch-v1"))
                assertTrue(upgraded.embeddingRecords("epoch-v1", a.key).isEmpty())
                assertNull(upgraded.clusterState("epoch-v1"))
                upgraded.beginSnapshot("epoch-v2")
                upgraded.stageBatch("epoch-v2", listOf(a))
                upgraded.commitSnapshot("epoch-v2", "detector-1", model)
                // Legacy v1 rectangles have no SFace vector: queue re-scan,
                // rather than exposing an unembedded face as a new cluster.
                assertEquals(1, upgraded.progress("epoch-v2")?.pending)
                assertEquals(0, tableRows(upgraded, "faces"))
                assertEquals(0, tableRows(upgraded, "embeddings"))
                val lease = requireNotNull(upgraded.claim("epoch-v2", 1000))
                assertTrue(upgraded.completeEmbeddings(lease, listOf(box),
                    listOf(syntheticEmbedding()), model))
                assertEquals(1, upgraded.embeddingRecords("epoch-v2", a.key).size)
                assertNull(upgraded.clusterState("epoch-v2"))
                assertTrue(upgraded.materializeClusters("epoch-v2"))
                assertEquals(1, upgraded.clusterState("epoch-v2")?.faces?.size)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun restartRevokesOldEmbeddedLeaseAndRestoresOnlyAfterFreshGrant() {
        val (store, file) = fixture()
        val model = SFaceModel.EMBEDDER_VERSION
        var savedUid = ""
        var interrupted: nl.atlas.gallery.featurepersistence.IndexedFaceLease? = null
        try {
            store.use { initial ->
                stage(initial, rows=listOf(a,b))
                assertTrue(initial.completeEmbeddings(
                    requireNotNull(initial.claim("epoch-A", 1000)), listOf(box),
                    listOf(syntheticEmbedding()), model))
                savedUid = initial.embeddingRecords("epoch-A", a.key).single().nativeFaceUid
                interrupted = requireNotNull(initial.claim("epoch-A", 1001))
            }
            FaceIndexStore(contextFor(file)).use { resumed ->
                assertTrue(resumed.embeddingRecords("epoch-A", a.key).isEmpty())
                assertNull(resumed.clusterState("epoch-A"))
                assertFalse(resumed.completeEmbeddings(requireNotNull(interrupted),
                    listOf(box), listOf(syntheticEmbedding()), model))
                resumed.beginSnapshot("epoch-B")
                resumed.stageBatch("epoch-B", listOf(a,b))
                resumed.commitSnapshot("epoch-B", "detector-1", model)
                assertEquals(savedUid, resumed.embeddingRecords("epoch-B", a.key).single().nativeFaceUid)
                assertNull(resumed.clusterState("epoch-B"))
                assertTrue(resumed.materializeClusters("epoch-B"))
                assertEquals(1, resumed.clusterState("epoch-B")?.faces?.size)
                assertEquals(1, resumed.progress("epoch-B")?.pending)
                assertTrue(resumed.completeEmbeddings(
                    requireNotNull(resumed.claim("epoch-B", 2000)), listOf(box),
                    listOf(syntheticEmbedding()), model))
                assertNull(resumed.clusterState("epoch-B"))
                assertTrue(resumed.materializeClusters("epoch-B"))
                assertEquals(2, resumed.clusterState("epoch-B")?.faces?.size)
                assertFalse(resumed.completeEmbeddings(requireNotNull(interrupted),
                    listOf(box), listOf(syntheticEmbedding()), model))
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun clusterPublicationIsFailClosedAndCoalescesPhotos() = withStore { db ->
        val model = SFaceModel.EMBEDDER_VERSION
        stage(db,rows=listOf(a,b))
        val first = requireNotNull(db.claim("epoch-A",1000))
        assertTrue(db.completeEmbeddings(first,listOf(box),listOf(syntheticEmbedding()),model))
        assertNull(db.clusterState("epoch-A"))
        val second = requireNotNull(db.claim("epoch-A",1001))
        assertTrue(db.completeEmbeddings(second,listOf(box),listOf(syntheticEmbedding()),model))
        assertEquals(0, tableRows(db,"cluster_members"))
        assertTrue(db.materializeClusters("epoch-A"))
        val members = requireNotNull(db.clusterState("epoch-A"))
        assertEquals(2,members.faceCount)
        assertEquals(2, tableRows(db,"cluster_members"))
        assertTrue(db.materializeClusters("epoch-A")) // clean idempotence
        db.invalidatePermissionScope()
        assertNull(db.clusterState("epoch-A"))
        assertFalse(db.materializeClusters("epoch-A"))
    }

    @Test fun publicationRollsBackSuppressedMemberAndCanRetry() = withStore { db ->
        val model = SFaceModel.EMBEDDER_VERSION
        stage(db,rows=listOf(a))
        assertTrue(db.completeEmbeddings(requireNotNull(db.claim("epoch-A",1000)),
            listOf(box),listOf(syntheticEmbedding()),model))
        db.writableDatabase.execSQL("""CREATE TRIGGER suppress_cluster_member
            BEFORE INSERT ON cluster_members BEGIN SELECT RAISE(IGNORE); END""")
        try {
            db.materializeClusters("epoch-A")
            fail("Suppressed member publication succeeded")
        } catch (_: IllegalStateException) { }
        assertNull(db.clusterState("epoch-A"))
        assertEquals(0,tableRows(db,"cluster_members"))
        db.writableDatabase.execSQL("DROP TRIGGER suppress_cluster_member")
        assertTrue(db.materializeClusters("epoch-A"))
        assertEquals(1,tableRows(db,"cluster_members"))
    }

}
