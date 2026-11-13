package nl.atlas.gallery.featureface

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.search.FaceQueryStatus
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * VS-V1b (#468) JVM evidence: video source keys and catalogue rules, the
 * multi-frame scan path with frame_ms, typed skips, the frame-counted batch
 * budget, and video moments in face search. Invented MediaStore rows,
 * scripted frames and axis-aligned vectors only; no media is decoded.
 */
class VideoFaceCatalogTest {
    private val epoch = "grant-epoch-video"

    private fun row(id: Long, video: Boolean, durationMs: Long = 4_000L,
                    size: Long = 1_000L + id, editor: Long = 0L,
                    provider: String? = "synthetic-provider-1") = FaceVisibleMedia(
        uri = "content://media/external_primary/${if (video) "video" else "images"}/media/$id",
        volumeName = "external_primary", isVideo = video, id = id,
        providerVersion = provider, generationModified = 10 + id,
        sizeBytes = size, modifiedAtSeconds = 1_700_000_000L, editorRevision = editor,
        widthPixels = 1920, heightPixels = 1080, orientationDegrees = 0, durationMs = durationMs
    )

    /** Independent re-implementation of the v1 length-prefixed SHA-256 digest. */
    private fun v1Digest(vararg fields: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            val n = bytes.size
            sha.update(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
                (n ushr 8).toByte(), n.toByte()))
            sha.update(bytes)
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    // ---- Source keys -------------------------------------------------------

    @Test fun photoKeysStayByteIdenticalSoNoPhotoIsRescanned() {
        val photo = row(7, video = false)
        val key = photo.toDurableFaceKey()
        assertEquals("atlas-face-media-v1:" + v1Digest("atlas-face-source-key-v1",
            photo.uri, "external_primary", "image", "7"), key.canonicalMediaKey)
        assertEquals("atlas-face-rev-v1:" + v1Digest("atlas-face-revision-v1",
            "synthetic-provider-1", "17", "1007", "1700000000", "1920", "1080", "0", "0"),
            key.sourceRevision)
    }

    @Test fun videoKeyIsDistinctAndItsRevisionFollowsEditsAndDuration() {
        val video = row(7, video = true)
        val key = video.toDurableFaceKey()
        assertNotEquals(row(7, video = false).toDurableFaceKey().canonicalMediaKey,
            key.canonicalMediaKey)
        assertTrue(key.canonicalMediaKey.startsWith("atlas-face-media-v1:"))
        // Same identity, new revision: in-app edit, trim (duration), re-encode (size).
        for (edited in listOf(video.copy(editorRevision = 1), video.copy(durationMs = 3_000),
                video.copy(sizeBytes = 999))) {
            val changed = edited.toDurableFaceKey()
            assertEquals(key.canonicalMediaKey, changed.canonicalMediaKey)
            assertNotEquals(key.sourceRevision, changed.sourceRevision)
        }
        // An unknown (0) duration is still a valid identity; the scanner skips it typed.
        row(8, video = true, durationMs = 0).toDurableFaceKey()
    }

    @Test fun kindAndUriMustAgree() {
        expectInvalid { row(7, video = true).copy(isVideo = false).toDurableFaceKey() }
        expectInvalid { row(7, video = false).copy(isVideo = true).toDurableFaceKey() }
        expectInvalid {
            FaceMediaSourceKey.fromVisiblePhoto(row(7, true).uri, "external_primary", true, 7,
                "p", 1, 1, 1, 0, 1, 1, 0)
        }
        expectInvalid { row(7, video = true, durationMs = -1).toDurableFaceKey() }
    }

    @Test fun incompleteVideoIsLeftOutButIncompletePhotoGetsWeakRevision() {
        assertNull(row(5, video = true, size = 0).durableFaceKeyOrNull())
        assertNull(row(5, video = true, provider = null).durableFaceKeyOrNull())
        val baseline = row(5, video = false).toDurableFaceKey()
        val weak = row(5, video = false, size = 0, provider = null).toDurableFaceKey()
        assertEquals(baseline.canonicalMediaKey, weak.canonicalMediaKey)
        assertNotEquals(baseline.sourceRevision, weak.sourceRevision)
        expectInvalid { row(5, video = false).copy(id = 0).toDurableFaceKey() }
    }

    @Test fun weakPhotoKeyUsesExplicitUnknownsAndRedetectsWhenAvailableFieldsChange() {
        val source = row(13, video = false).copy(
            providerVersion = null, generationModified = 0, editorRevision = -1,
            widthPixels = 0, heightPixels = 0, orientationDegrees = Int.MIN_VALUE)
        val initial = source.toDurableFaceKey()
        assertEquals(6, FaceMediaSourceKey.missingPhotoFields(
            source.providerVersion, source.generationModified, source.sizeBytes,
            source.modifiedAtSeconds, source.editorRevision, source.widthPixels,
            source.heightPixels, source.orientationDegrees).size)
        for (edited in listOf(source.copy(sizeBytes = 99),
                source.copy(modifiedAtSeconds = 123),
                source.copy(generationModified = 20),
                source.copy(widthPixels = 1920, heightPixels = 1080),
                source.copy(editorRevision = 0))) {
            assertEquals(initial.canonicalMediaKey, edited.toDurableFaceKey().canonicalMediaKey)
            assertNotEquals(initial.sourceRevision, edited.toDurableFaceKey().sourceRevision)
        }
        assertEquals(initial.sourceRevision, source.toDurableFaceKey().sourceRevision)
    }

    @Test fun admissionCountsWeakPhotoFieldsWithoutLeakingIdentities() {
        val admission = FaceCatalogAdmission()
        val valid = row(1, video = false)
        val weak = row(2, video = false).copy(
            providerVersion = null, generationModified = 0, widthPixels = 0,
            heightPixels = 0)
        assertEquals(valid.toDurableFaceKey(), admission.admit(valid))
        assertEquals(weak.toDurableFaceKey(), admission.admit(weak))
        assertNull(admission.admit(row(3, video = false).copy(id = 0)))
        assertEquals(2, admission.photoRows)
        assertEquals(3, admission.observedPhotoRows)
        assertEquals(1, admission.weakPhotoRows)
        assertEquals(1, admission.ignoredPhotoRows)
        assertEquals(mapOf("providerVersion" to 1, "generationModified" to 1,
            "widthPixels" to 1, "heightPixels" to 1), admission.missingPhotoFields)
    }

    @Test fun mediaRecordProjectionCarriesVideoDuration() {
        val record = MediaRecord(id = 3, uri = row(3, true).uri, name = "clip.mp4",
            relativePath = "DCIM/", sizeBytes = 10, takenAtMillis = 1, isVideo = true,
            durationMillis = 2_500, volumeName = "external_primary")
        assertEquals(2_500L, record.toFaceVisibleMedia(0).durationMs)
        assertEquals(0L, record.copy(isVideo = false).toFaceVisibleMedia(0).durationMs)
    }

    // ---- Directory ---------------------------------------------------------

    @Test fun directoryResolvesVideosAndLeavesOutTheSameRowsAsTheCatalogue() {
        val photo = row(1, video = false)
        val video = row(2, video = true)
        val broken = row(3, video = true, size = 0)
        val directory = FaceAuthorizedMediaDirectory.build(epoch, listOf(photo, video, broken),
            { it }, { epoch }, { true })
        assertEquals(2, directory.mediaCount)
        assertEquals(video, directory.authorizedSource(video.toDurableFaceKey()))
        assertEquals(video.uri, directory.authorizedUri(video.toDurableFaceKey()))
        assertEquals(photo.uri, directory.authorizedUri(photo.toDurableFaceKey()))
        // A catalogue lease for an edited revision never resolves the current row.
        val stale = FaceMediaKey(video.toDurableFaceKey().canonicalMediaKey,
            video.copy(editorRevision = 4).toDurableFaceKey().sourceRevision)
        assertTrue(!directory.sourceRevisionCurrent(stale))
        try {
            directory.authorizedSource(stale); fail("stale video revision resolved")
        } catch (_: SecurityException) { }
    }

    // ---- Scan engine -------------------------------------------------------

    private val conditions = FaceScanConditions(
        optedIn = true, photosPermissionGranted = true, permissionSnapshotCurrent = true,
        batteryNotLow = true, storageNotLow = true, charging = true,
        pausedByUser = false, backgroundMaintenance = true
    )

    private class Frame(val tag: Int) : BoundedFaceFrame {
        override val widthPixels = 400
        override val heightPixels = 200
        var closed = 0
        override fun close() { closed++ }
    }

    private class Port(leases: List<FaceScanLease>) : FaceScanIndexPort {
        private val queue = ArrayDeque(leases)
        val completed = ArrayList<Pair<List<UnknownFaceBox>, List<Long?>>>()
        val skipped = ArrayList<String>()
        var released = 0
        var failed = 0
        override fun claim(permissionEpoch: String) = queue.removeFirstOrNull()
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>) =
            error("video results must use completeFrames")
        override fun completeFrames(lease: FaceScanLease, observations: List<UnknownFaceBox>,
                                    frameMs: List<Long?>): Boolean {
            completed += observations to frameMs; return true
        }
        override fun release(lease: FaceScanLease): Boolean { released++; return true }
        override fun fail(lease: FaceScanLease): Boolean { failed++; return true }
        override fun skip(lease: FaceScanLease, reason: String): Boolean {
            skipped += reason; return true
        }
    }

    private fun lease(n: Int) = FaceScanLease(FaceMediaKey("video:$n", "rev-$n"), "yunet", epoch, "t$n")

    private fun keyframes(vararg times: Long): Pair<FaceSourceFrames, List<Frame>> {
        val frames = times.mapIndexed { i, _ -> Frame(i) }
        return FaceSourceFrames(times.zip(frames).map { (t, f) -> TimedFaceFrame(t, f) }) to frames
    }

    private fun engine(port: FaceScanIndexPort, reader: FaceFrameReader,
                       current: () -> Boolean = { true },
                       locate: (BoundedFaceFrame) -> List<FacePixelBounds>) =
        FaceScanEngine({ epoch }, { conditions }, { current() }, port, reader,
            OnDeviceFaceLocator { locate(it) })

    private fun videoReader(source: () -> FaceSourceFrames) = object : FaceFrameReader {
        override fun open(lease: FaceScanLease, maxLongEdgePixels: Int) =
            error("a video must be opened through openFrames")
        override fun openFrames(lease: FaceScanLease, maxLongEdgePixels: Int) = source()
    }

    @Test fun everyKeyframeFaceCarriesItsFrameTimeInTimeOrder() {
        val port = Port(listOf(lease(1)))
        val (source, frames) = keyframes(0L, 2_000L, 4_000L)
        val result = engine(port, videoReader { source }) { frame ->
            when ((frame as Frame).tag) {
                0 -> emptyList()
                1 -> listOf(FacePixelBounds(200, 50, 260, 120), FacePixelBounds(20, 40, 80, 110))
                else -> listOf(FacePixelBounds(100, 20, 160, 90))
            }
        }.scanOne(epoch)
        assertEquals(FaceScanStep.INDEXED, result.step)
        assertEquals(3, result.observations)
        assertEquals(3, result.frames)
        val (boxes, times) = port.completed.single()
        assertEquals(listOf(2_000L, 2_000L, 4_000L), times)
        assertTrue(boxes[0].left < boxes[1].left) // same frame: geometry order
        assertTrue(frames.all { it.closed == 1 })
    }

    @Test fun aVideoKeepsItsLargestThirtyTwoFacesDeterministically() {
        val port = Port(listOf(lease(2)))
        val times = LongArray(12) { it * 2_000L }
        val (source, _) = keyframes(*times)
        // Each keyframe: one large face (60x60) and three small ones (10x10).
        engine(port, videoReader { source }) { _ ->
            listOf(FacePixelBounds(300, 100, 360, 160)) +
                (0 until 3).map { FacePixelBounds(it * 20, 0, it * 20 + 10, 10) }
        }.scanOne(epoch)
        val (boxes, frameMs) = port.completed.single()
        assertEquals(FaceDiscoveryContract.MAX_FACES_PER_IMAGE, boxes.size)
        // All 12 large faces survive; 20 of the 36 small ones, earliest first.
        assertEquals(12, boxes.count { it.right - it.left > 0.1f })
        assertEquals(frameMs.map { it!! }.sorted(), frameMs.map { it!! })
    }

    @Test fun grantOrPowerChangeBetweenKeyframesReleasesWithoutCompletion() {
        val port = Port(listOf(lease(3)))
        val (source, frames) = keyframes(0L, 2_000L, 4_000L)
        var checks = 0
        val result = engine(port, videoReader { source }, current = { ++checks < 3 }) {
            listOf(FacePixelBounds(10, 10, 60, 60))
        }.scanOne(epoch)
        assertEquals(FaceScanStep.RELEASED_STALE, result.step)
        assertEquals(1, port.released)
        assertTrue(port.completed.isEmpty())
        assertTrue(frames.all { it.closed == 1 })
    }

    @Test fun corruptOrDrmVideoIsATypedTerminalSkipNotARetry() {
        val port = Port(listOf(lease(4)))
        val result = engine(port, videoReader { throw FaceSourceSkipped("UNREADABLE_VIDEO") }) {
            error("nothing to locate")
        }.scanOne(epoch)
        assertEquals(FaceScanStep.SKIPPED, result.step)
        assertEquals(listOf("UNREADABLE_VIDEO"), port.skipped)
        assertEquals(0, port.failed)
        assertTrue(port.completed.isEmpty())
        try { FaceSourceSkipped("not a reason"); fail("free text reason accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationMidVideoReleasesAndClosesEveryKeyframe() {
        val port = Port(listOf(lease(5)))
        val (source, frames) = keyframes(0L, 2_000L)
        try {
            engine(port, videoReader { source }) { frame ->
                if ((frame as Frame).tag == 1) throw CancellationException("stop") else emptyList()
            }.scanOne(epoch)
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, port.released)
        assertTrue(frames.all { it.closed == 1 })
    }

    @Test fun keyframesAreBoundedAndTimeOrdered() {
        expectInvalid { FaceSourceFrames(emptyList()) }
        expectInvalid { keyframes(2_000L, 0L) }
        expectInvalid { FaceSourceFrames((0..60).map { TimedFaceFrame(it * 1000L, Frame(it)) }) }
        expectInvalid { FaceSourceFrames(listOf(TimedFaceFrame(null, Frame(0)), TimedFaceFrame(5, Frame(1)))) }
    }

    @Test fun batchBudgetCountsEveryKeyframe() {
        val results = ArrayDeque(listOf(
            FaceScanStepResult(FaceScanStep.INDEXED, 1, 1),
            FaceScanStepResult(FaceScanStep.INDEXED, 2, 6),
            FaceScanStepResult(FaceScanStep.INDEXED, 1, 1)))
        val batch = FaceScanBatchPolicy({ 0L }, { results.removeFirst() }).run(FaceBatchBudget(8, 1_000))
        // 1 + 6 + 1 = 8 frame units: the third source ends the slice.
        assertEquals(FaceBatchStop.PHOTO_LIMIT, batch.stop)
        assertEquals(3, batch.photosIndexed)
        val video = FaceScanBatchPolicy({ 0L }, {
            FaceScanStepResult(FaceScanStep.INDEXED, 0, FaceSourceFrames.MAX_FRAMES)
        }).run(FaceBatchBudget(8, 1_000))
        assertEquals(1, video.stepsAttempted) // one long video fills the whole slice
        val skips = ArrayDeque(listOf(FaceScanStepResult(FaceScanStep.SKIPPED),
            FaceScanStepResult(FaceScanStep.NO_PENDING)))
        val afterSkip = FaceScanBatchPolicy({ 0L }, { skips.removeFirst() }).run(FaceBatchBudget(8, 1_000))
        assertEquals(FaceBatchStop.NO_PENDING, afterSkip.stop) // a skip does not stop the slice
        assertEquals(0, afterSkip.photosIndexed)
    }

    // ---- Search by face returns video moments ------------------------------

    @Test fun faceSearchHitsCarryVideoMoments() {
        val model = "sface-test"
        fun axis(i: Int) = FloatArray(128) { if (it == i) 1f else 0f }
        val box = IndexedFaceBox(0.1f, 0.1f, 0.4f, 0.4f)
        val clip = IndexedMedia("video-key", "rev")
        val still = IndexedMedia("photo-key", "rev")
        fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
        val corpus = FaceSearchCorpus.Builder(model)
            .add(id(1), clip, box, null, axis(0), frameMs = 6_000L)
            .add(id(2), clip, box, null, axis(0), frameMs = 2_000L)
            .add(id(3), still, box, null, axis(0))
            .add(id(4), clip, box, null, axis(9), frameMs = 8_000L)
            .build(ClusterReviewFacts.NONE, clustersPublished = true)
        val result = FaceQueryEngine.search(corpus, FaceQuery(model, listOf(axis(0))))
        assertEquals(FaceQueryStatus.OK, result.status)
        val video = result.hits.single { it.media == clip }
        assertEquals(listOf(2_000L, 6_000L), video.momentsMs)
        assertTrue(video.bestFrameMs in setOf(2_000L, 6_000L))
        assertTrue(video.faces.all { it.frameMs != null })
        assertNull(result.hits.single { it.media == still }.bestFrameMs)
        expectInvalid { FaceSearchCorpus.Builder(model).add(id(5), clip, box, null, axis(0), frameMs = -1) }
    }

    private inline fun expectInvalid(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) { }
    }
}
