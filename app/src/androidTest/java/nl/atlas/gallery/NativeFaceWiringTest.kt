package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureface.BoundedFaceFrame
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.FaceCatalogPublisher
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FacePixelBounds
import nl.atlas.gallery.featureface.FaceSourceFrames
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanEngine
import nl.atlas.gallery.featureface.FaceScanIoFailure
import nl.atlas.gallery.featureface.FaceScanLease
import nl.atlas.gallery.featureface.FaceScanStep
import nl.atlas.gallery.featureface.FaceUnknownInboxBridge
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.NativeFaceIndexPort
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * P scanner -> X private face index -> U inbox wiring on a real app-cache
 * SQLite database. Only invented MediaStore rows and a scripted locator: no
 * photos are decoded, no detector model runs and nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class NativeFaceWiringTest {
    private val epoch = "grant-epoch-1"
    private val conditions = FaceScanConditions(
        optedIn = true, photosPermissionGranted = true, permissionSnapshotCurrent = true,
        batteryNotLow = true, storageNotLow = true, charging = false,
        pausedByUser = false, backgroundMaintenance = false
    )

    private fun photo(id: Long, modified: Long = 1_700_000_000L, video: Boolean = false) = FaceVisibleMedia(
        uri = "content://media/external_primary/${if (video) "video" else "images"}/media/$id",
        volumeName = "external_primary", isVideo = video, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = modified, editorRevision = 0,
        widthPixels = 4000, heightPixels = 3000, orientationDegrees = 0
    )

    private class Frame(override val widthPixels: Int, override val heightPixels: Int) : BoundedFaceFrame {
        var closed = 0
        override fun close() { closed++ }
    }

    private class Fixture(val store: FaceIndexStore, val dir: File)

    private fun fixture(): Fixture {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "native-face-wiring-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "face_fixture.db")
        val context: Context = object : ContextWrapper(base) {
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
        return Fixture(FaceIndexStore(context), dir)
    }

    private inline fun withStore(test: (FaceIndexStore) -> Unit) {
        val f = fixture()
        try { f.store.use(test) } finally { f.dir.deleteRecursively() }
    }

    @Test fun publishScanAndReviewUnknownFacesEndToEnd() = withStore { store ->
        val inventory = listOf(photo(1), photo(2), photo(3, video = true))
        var activeEpoch: String? = epoch
        val directory = FaceAuthorizedMediaDirectory.build(
            epoch, inventory, { it }, { activeEpoch }, { true })
        val publication = FaceCatalogPublisher(store, { it == activeEpoch }, { true })
            .publish(epoch, "detector-1", inventory.asSequence())
        assertEquals(2, publication.sourcePhotoRows)
        // VS-V1b: a video with complete provenance is catalogued, not ignored.
        assertEquals(1, publication.sourceVideoRows)
        assertEquals(0, publication.ignoredVideoRows)

        val frames = ArrayList<Frame>()
        val engine = FaceScanEngine(
            scopeEpoch = { activeEpoch },
            currentConditions = { conditions },
            sourceRevisionCurrent = directory::sourceRevisionCurrent,
            index = NativeFaceIndexPort(store),
            // This scripted reader decodes photos only; a video is a typed skip.
            reader = object : FaceFrameReader {
                override fun open(lease: FaceScanLease, maxLongEdgePixels: Int) =
                    Frame(maxLongEdgePixels, maxLongEdgePixels * 3 / 4).also { frames += it }
                override fun openFrames(lease: FaceScanLease, maxLongEdgePixels: Int): FaceSourceFrames {
                    if ("/video/" in directory.authorizedUri(lease.media)) {
                        throw FaceSourceSkipped("VIDEO_UNSUPPORTED")
                    }
                    return FaceSourceFrames.still(open(lease, maxLongEdgePixels))
                }
            },
            locator = { frame ->
                listOf(FacePixelBounds(0, 0, frame.widthPixels / 4, frame.heightPixels / 4),
                    FacePixelBounds(frame.widthPixels / 2, frame.heightPixels / 2,
                        frame.widthPixels * 3 / 4, frame.heightPixels * 3 / 4))
            }
        )
        val steps = List(4) { engine.scanOne(epoch) }
        assertEquals(FaceScanStep.NO_PENDING, steps.last().step)
        assertEquals(listOf(FaceScanStep.INDEXED, FaceScanStep.INDEXED, FaceScanStep.SKIPPED),
            steps.take(3).map { it.step }.sortedBy { it.ordinal })
        assertEquals(listOf(2, 2), steps.filter { it.step == FaceScanStep.INDEXED }.map { it.observations })
        assertTrue("every decoded frame closed exactly once", frames.size == 2 && frames.all { it.closed == 1 })
        val progress = store.progress(epoch)
        assertNotNull(progress)
        progress!!
        assertEquals(2, progress.processed)
        assertEquals(1, progress.failed) // the typed video skip, no retry
        assertEquals(4, progress.observations)

        val bridge = FaceUnknownInboxBridge(store::pendingFaceReviewPage, directory::authorizedUri) { it == activeEpoch }
        val first = bridge.page(epoch, limit = 3)
        assertEquals(3, first.items.size)
        assertNotNull(first.nextCursor)
        val rest = bridge.page(epoch, first.nextCursor, limit = 3)
        assertEquals(1, rest.items.size)
        assertNull(rest.nextCursor)
        val all = first.items + rest.items
        assertEquals(4, all.map { it.nativeFaceUid }.toSet().size)
        val photoUris = inventory.filter { !it.isVideo }.map { it.uri }.toSet()
        assertTrue(all.all { it.sourceUri in photoUris })
        assertTrue(all.all { it.detectorVersion == "detector-1" })

        // Review addresses the native UID; dismissed faces leave the inbox.
        assertTrue(store.reviewObservation(epoch, all.first().nativeFaceUid, "NOT_A_FACE"))
        assertEquals(3, bridge.page(epoch, limit = 32).items.size)

        // Grant revocation: the inbox and the scanner both stop.
        activeEpoch = null
        try { bridge.page(epoch); fail("revoked grant must not page") } catch (_: SecurityException) { }
        assertEquals(FaceScanStep.HELD, engine.scanOne(epoch).step)
    }

    /**
     * #538 (absorbs #539's synthetic scenarios), the host's real order:
     * publisher first, directory second. One MediaStore item listed twice
     * (same key and revision) is staged once by SQLite; the directory used to
     * refuse it ("Duplicate canonical media identity" -> "The private face
     * index could not be prepared"). Now both sides agree and the scan runs.
     */
    @Test fun repeatedIdenticalRowPublishesBuildsAndScansOnce() = withStore { store ->
        val one = photo(41)
        val other = photo(46)
        val rows = listOf(one, other, one.copy())
        val publication = FaceCatalogPublisher(store, { it == epoch }, { true })
            .publish(epoch, "detector-1", rows.asSequence())
        assertEquals(3, publication.sourcePhotoRows) // listed rows, not unique keys
        assertEquals(2, store.progress(epoch)!!.pending)
        val directory = FaceAuthorizedMediaDirectory.build(epoch, rows, { it }, { epoch }, { true })
        assertEquals(2, directory.mediaCount)
        assertEquals(1, directory.repeatedRows)
        assertEquals(one.uri, directory.authorizedUri(one.toDurableFaceKey()))
        val engine = FaceScanEngine(
            { epoch }, { conditions }, directory::sourceRevisionCurrent,
            NativeFaceIndexPort(store), { _, edge -> Frame(edge, edge) },
            { listOf(FacePixelBounds(10, 10, 90, 90)) }
        )
        assertEquals(FaceScanStep.INDEXED, engine.scanOne(epoch).step)
        assertEquals(FaceScanStep.INDEXED, engine.scanOne(epoch).step)
        assertEquals(FaceScanStep.NO_PENDING, engine.scanOne(epoch).step)
        assertEquals(2, store.progress(epoch)!!.processed)
    }

    /** Same key, conflicting revision: still refused at staging, nothing published. */
    @Test fun conflictingRevisionDuplicateFailsInStagingBeforeDirectory() = withStore { store ->
        val first = photo(42)
        val changed = first.copy(sizeBytes = first.sizeBytes + 1)
        assertEquals(first.toDurableFaceKey().canonicalMediaKey,
            changed.toDurableFaceKey().canonicalMediaKey)
        try {
            FaceCatalogPublisher(store, { it == epoch }, { true })
                .publish(epoch, "detector-1", sequenceOf(first, changed))
            fail("conflicting revision should fail staging")
        } catch (error: IllegalArgumentException) {
            assertEquals("Invalid or conflicting staged media; fresh permission epoch required",
                error.message)
        }
        assertNull("a failed stage must not publish any grant-visible index", store.progress(epoch))
        try {
            FaceAuthorizedMediaDirectory.build(epoch, listOf(first, changed), { it }, { epoch }, { true })
            fail("conflicting revision should not build a directory")
        } catch (error: IllegalArgumentException) {
            assertEquals("Contradictory duplicate canonical media identity", error.message)
        }
    }

    /**
     * #538 overlapping publications: a newer publication B starts its own
     * snapshot right after publisher A's last scope check. The store refuses
     * A's stale batch with an IllegalArgumentException, which the host used
     * to report as "could not be prepared" (and switch discovery off, killing
     * B too). A now reports a lost race (IllegalStateException) and B, the
     * newer publication, still publishes intact.
     */
    @Test fun publicationOvertakenMidStagingIsALostRaceNotAnInvariantFailure() = withStore { store ->
        val epochA = "grant-epoch-a"
        val epochB = "grant-epoch-b"
        var live = epochA
        var checks = 0
        val rowsA = (1L..5L).map { photo(100 + it) }
        val rowsB = (1L..3L).map { photo(200 + it) }
        val a = FaceCatalogPublisher(store, scopeCurrent = { e ->
            val current = e == live
            // Second check = the one just before A's first stageBatch: it
            // passes, then B overtakes before A reaches the store.
            if (current && ++checks == 2) {
                live = epochB
                store.beginSnapshot(epochB)
            }
            current
        }, providerSnapshotCurrent = { true }, batchSize = 2)
        try {
            a.publish(epochA, "detector-1", rowsA.asSequence())
            fail("an overtaken publication must not publish")
        } catch (lost: IllegalStateException) {
            assertTrue(lost.cause is IllegalArgumentException)
        } catch (wrong: IllegalArgumentException) {
            fail("lost race reported as an invariant failure: ${wrong.message}")
        }
        assertNull(store.progress(epochA))
        // B already began its snapshot above; a fresh epoch for B's own run.
        val epochC = "grant-epoch-c"
        live = epochC
        val published = FaceCatalogPublisher(store, { it == live }, { true })
            .publish(epochC, "detector-1", rowsB.asSequence())
        assertEquals(3, published.sourcePhotoRows)
        assertEquals(3, store.progress(epochC)!!.pending)
    }

    /** A store refusal while the epoch is still current stays a real failure. */
    @Test fun storeRefusalOfACurrentEpochIsStillAnInvariantFailure() = withStore { store ->
        val used = "grant-epoch-used"
        FaceCatalogPublisher(store, { true }, { true }).publish(used, "detector-1", sequenceOf(photo(301)))
        try {
            FaceCatalogPublisher(store, { true }, { true })
                .publish(used, "detector-1", sequenceOf(photo(302)))
            fail("a reused epoch must be refused")
        } catch (refused: IllegalArgumentException) {
            assertEquals("Permission epoch already used; new epoch required", refused.message)
        }
    }

    @Test fun distinctAndWeakRevisionPhotoRowsCompleteTheActualHostSequence() = withStore { store ->
        val distinct = photo(43)
        val weak = photo(44).copy(providerVersion = null, generationModified = 0,
            sizeBytes = 0, modifiedAtSeconds = -1, editorRevision = -1,
            widthPixels = 0, heightPixels = 0, orientationDegrees = Int.MIN_VALUE)
        val invalidCanonical = photo(45).copy(id = 0)
        val rows = listOf(distinct, weak, invalidCanonical)
        val published = FaceCatalogPublisher(store, { it == epoch }, { true })
            .publish(epoch, "detector-1", rows.asSequence())
        val directory = FaceAuthorizedMediaDirectory.build(
            epoch, rows, { it }, { epoch }, { true })
        assertEquals(2, published.sourcePhotoRows)
        assertEquals(1, published.weakPhotoRows)
        assertEquals(1, published.ignoredPhotoRows)
        assertEquals(2, directory.mediaCount)
        assertEquals(2, store.progress(epoch)!!.pending)
        assertEquals(distinct.uri, directory.authorizedUri(distinct.toDurableFaceKey()))
        assertEquals(weak.uri, directory.authorizedUri(weak.toDurableFaceKey()))
    }

    @Test fun partialPhotoProvenanceStillPublishesAndRunsFaceScan() = withStore { store ->
        val intact = photo(21)
        val weak = photo(22).copy(
            providerVersion = null, generationModified = 0,
            editorRevision = -1, widthPixels = 0, heightPixels = 0)
        val lostIdentity = photo(23).copy(id = 0)
        val rows = listOf(intact, weak, lostIdentity)
        val directory = FaceAuthorizedMediaDirectory.build(
            epoch, rows, { it }, { epoch }, { true })
        val result = FaceCatalogPublisher(store, { it == epoch }, { true })
            .publish(epoch, "detector-1", rows.asSequence())
        assertEquals(2, directory.mediaCount)
        assertEquals(2, result.sourcePhotoRows)
        assertEquals(1, result.weakPhotoRows)
        assertEquals(1, result.ignoredPhotoRows)
        assertEquals(1, result.missingPhotoFields["generationModified"])
        assertEquals(weak.uri, directory.authorizedUri(weak.toDurableFaceKey()))
        val engine = FaceScanEngine(
            { epoch }, { conditions }, directory::sourceRevisionCurrent,
            NativeFaceIndexPort(store), { _, edge -> Frame(edge, edge) },
            { listOf(FacePixelBounds(10, 10, 90, 90)) }
        )
        assertEquals(FaceScanStep.INDEXED, engine.scanOne(epoch).step)
        assertEquals(FaceScanStep.INDEXED, engine.scanOne(epoch).step)
        assertEquals(FaceScanStep.NO_PENDING, engine.scanOne(epoch).step)
        assertEquals(2, store.progress(epoch)!!.processed)
        assertEquals(2, store.progress(epoch)!!.observations)
    }

    @Test fun weakPhotoRevisionChangeQueuesItAgain() = withStore { store ->
        val weak = photo(32).copy(generationModified = 0, widthPixels = 0)
        val prior = FaceCatalogPublisher(store, { true }, { true })
        prior.publish(epoch, "detector-1", sequenceOf(weak))
        val directory = FaceAuthorizedMediaDirectory.build(epoch, listOf(weak),
            { it }, { epoch }, { true })
        val engine = FaceScanEngine(
            { epoch }, { conditions }, directory::sourceRevisionCurrent,
            NativeFaceIndexPort(store), { _, edge -> Frame(edge, edge) },
            { listOf(FacePixelBounds(10, 10, 90, 90)) }
        )
        assertEquals(FaceScanStep.INDEXED, engine.scanOne(epoch).step)
        assertEquals(1, store.progress(epoch)!!.processed)
        val changed = weak.copy(sizeBytes = weak.sizeBytes + 1)
        val newer = "grant-epoch-new-weak"
        FaceCatalogPublisher(store, { true }, { true })
            .publish(newer, "detector-1", sequenceOf(changed))
        val progress = store.progress(newer)!!
        assertEquals(1, progress.pending)
        assertEquals(0, progress.processed)
    }

    @Test fun editedSourceIsReleasedNotIndexed() = withStore { store ->
        val original = listOf(photo(7))
        var visible = original
        val directoryFor = { rows: List<FaceVisibleMedia> ->
            FaceAuthorizedMediaDirectory.build(epoch, rows, { it }, { epoch }, { true })
        }
        FaceCatalogPublisher(store, { it == epoch }, { true }).publish(epoch, "detector-1", original.asSequence())
        // The photo is edited after publication: its live revision no longer matches.
        visible = listOf(photo(7, modified = 1_800_000_000L))
        val directory = directoryFor(visible)
        var decoded = 0
        val engine = FaceScanEngine(
            { epoch }, { conditions }, directory::sourceRevisionCurrent, NativeFaceIndexPort(store),
            { _, edge -> decoded++; Frame(edge, edge) }, { emptyList() }
        )
        assertEquals(FaceScanStep.RELEASED_STALE, engine.scanOne(epoch).step)
        assertEquals("stale source must never be decoded", 0, decoded)
        assertEquals(0, store.progress(epoch)!!.processed)
    }

    @Test fun decodeFailureSpendsBoundedRetryAndPublishFailureHidesCatalogue() = withStore { store ->
        val inventory = listOf(photo(9))
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it }, { epoch }, { true })
        FaceCatalogPublisher(store, { it == epoch }, { true }).publish(epoch, "detector-1", inventory.asSequence())
        val engine = FaceScanEngine(
            { epoch }, { conditions }, directory::sourceRevisionCurrent, NativeFaceIndexPort(store),
            { _, _ -> throw FaceScanIoFailure("synthetic corrupt image") }, { emptyList() }
        )
        assertEquals(FaceScanStep.RETRYABLE_FAILURE, engine.scanOne(epoch).step)
        assertEquals(0, store.progress(epoch)!!.processed)

        // A publisher whose scope drifts mid-run must not leave a half catalogue visible.
        var calls = 0
        val drifting = FaceCatalogPublisher(store, { calls++ < 2 }, { true })
        try {
            drifting.publish("grant-epoch-2", "detector-1", listOf(photo(10), photo(11)).asSequence())
            fail("scope drift must abort publication")
        } catch (_: IllegalStateException) { }
        assertNull(store.progress("grant-epoch-2"))
        // Starting a new grant snapshot already hid the old grant's results.
        assertNull(store.progress(epoch))
        assertFalse(inventory.first().toDurableFaceKey().canonicalMediaKey.contains("/storage/"))
    }
}
