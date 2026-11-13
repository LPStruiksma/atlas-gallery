package nl.atlas.gallery.featureface.embed

import nl.atlas.gallery.featureface.*
import nl.atlas.gallery.featureface.onnx.SFaceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.CancellationException
import org.junit.Test

/** Generated geometry and sparse synthetic vectors only; no face images. */
class FaceEmbeddingPipelineTest {
    private val epoch = "grant-current"
    private val media = FaceMediaKey("synthetic:1", "revision-1")
    private val lease = FaceScanLease(media, "yunet-v1", epoch, "opaque-1")

    private class Frame : BoundedFaceFrame {
        override val widthPixels = 200
        override val heightPixels = 200
        var closed = false
        override fun close() { closed = true }
    }

    private class StorePort(private val leases: ArrayDeque<FaceScanLease>) : FaceScanEmbeddingIndexPort {
        val saved = ArrayList<Pair<List<UnknownFaceBox>, List<FloatArray>>>()
        var failures = 0
        var releases = 0
        override fun claim(permissionEpoch: String) = leases.removeFirstOrNull()
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean =
            error("box-only persistence must never run")
        override fun completeEmbeddings(lease: FaceScanLease, observations: List<UnknownFaceBox>,
                                        vectors: List<FloatArray>, embedderVersion: String): Boolean {
            assertEquals(SFaceModel.EMBEDDER_VERSION, embedderVersion)
            saved += observations to vectors
            return true
        }
        override fun release(lease: FaceScanLease): Boolean { releases++; return true }
        override fun fail(lease: FaceScanLease): Boolean { failures++; return true }
    }

    private fun conditions() = FaceScanConditions(
        optedIn = true, photosPermissionGranted = true, permissionSnapshotCurrent = true,
        batteryNotLow = true, storageNotLow = true, charging = true,
        pausedByUser = false, backgroundMaintenance = false
    )

    private fun synthetic(axis: Int) = FloatArray(128) { if (it == axis) 1f else 0f }
    private fun detection(top: Int, axis: Int) = FaceEmbeddedDetection(
        FacePixelBounds(20, top, 80, top + 30), synthetic(axis))

    @Test fun oneBatchWritesSortedObservationsPairedWithEmbeddings() {
        val port = StorePort(ArrayDeque(listOf(lease, lease.copy(media =
            FaceMediaKey("synthetic:2", "revision-2"), opaqueToken = "opaque-2"))))
        val frames = ArrayList<Frame>()
        val pipeline = FaceEmbeddingPipeline("yunet-v1", SFaceModel.EMBEDDER_VERSION) {
            listOf(detection(90, 1), detection(10, 0))
        }
        val engine = FaceScanEngine({ epoch }, { conditions() }, { true }, port,
            FaceFrameReader { _, _ -> Frame().also { frames += it } }, pipeline)
        val result = FaceScanBatchPolicy({ 0L }, { engine.scanOne(epoch) }).run(FaceBatchBudget(3, 100))
        assertEquals(FaceBatchStop.NO_PENDING, result.stop)
        assertEquals(2, result.photosIndexed)
        assertEquals(4, result.unknownFaceObservations)
        assertEquals(2, port.saved.size)
        for ((boxes, vectors) in port.saved) {
            assertTrue(boxes[0].top < boxes[1].top)
            assertEquals(1f, vectors[0][0], 0f)
            assertEquals(1f, vectors[1][1], 0f)
        }
        assertTrue(frames.all { it.closed })
    }

    @Test fun mismatchedDetectorFailsWithoutPersistingOrStrandingFrame() {
        val port = StorePort(ArrayDeque(listOf(lease)))
        val frame = Frame()
        val pipeline = FaceEmbeddingPipeline("other-yunet", SFaceModel.EMBEDDER_VERSION) {
            listOf(detection(10, 0))
        }
        val engine = FaceScanEngine({ epoch }, { conditions() }, { true }, port,
            FaceFrameReader { _, _ -> frame }, pipeline)
        assertEquals(FaceScanStep.RETRYABLE_FAILURE, engine.scanOne(epoch).step)
        assertEquals(1, port.failures)
        assertTrue(frame.closed)
        assertTrue(port.saved.isEmpty())
    }

    @Test fun invalidVectorNeverBecomesAZeroFaceSuccess() {
        val port = StorePort(ArrayDeque(listOf(lease)))
        val pipeline = FaceEmbeddingPipeline("yunet-v1", SFaceModel.EMBEDDER_VERSION) {
            listOf(FaceEmbeddedDetection(FacePixelBounds(1, 1, 20, 20), FloatArray(128)))
        }
        val engine = FaceScanEngine({ epoch }, { conditions() }, { true }, port,
            FaceFrameReader { _, _ -> Frame() }, pipeline)
        assertEquals(FaceScanStep.RETRYABLE_FAILURE, engine.scanOne(epoch).step)
        assertTrue(port.saved.isEmpty())
    }
    @Test fun cancellationReleasesLeaseAndFrameWithoutSavingAnyVector() {
        val port = StorePort(ArrayDeque(listOf(lease)))
        val frame = Frame()
        val pipeline = FaceEmbeddingPipeline("yunet-v1", SFaceModel.EMBEDDER_VERSION) {
            throw CancellationException("synthetic worker cancellation")
        }
        val engine = FaceScanEngine({ epoch }, { conditions() }, { true }, port,
            FaceFrameReader { _, _ -> frame }, pipeline)
        try {
            engine.scanOne(epoch)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertTrue(frame.closed)
        assertEquals(1, port.releases)
        assertEquals(0, port.failures)
        assertTrue(port.saved.isEmpty())
        // A cancelled reservation must not strand the synchronous inference slot.
        val next = pipeline.tryReserve()
        assertTrue(next != null)
        next?.close()
    }

}
