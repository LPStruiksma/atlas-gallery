package nl.atlas.gallery.featuresearch.face

import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.BoundedFaceFrame
import nl.atlas.gallery.featureface.FaceEmbeddedDetection
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FacePixelBounds
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.SFaceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only fake frames and synthetic vectors; no Android bitmap, model or photo. */
class QueryFaceCaptureTest {
    private val source = QueryFaceSource(FaceMediaKey("fictional-media:1", "rev-1"), "epoch-1")
    private class Frame : BoundedFaceFrame {
        override val widthPixels = 240
        override val heightPixels = 120
        var closed = false
        override fun close() { closed = true }
    }
    private fun vector(axis: Int) = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }
    private fun face(left: Int, top: Int, axis: Int) =
        FaceEmbeddedDetection(FacePixelBounds(left, top, left + 30, top + 30), vector(axis))

    @Test fun twoFacesAreSortedPairedAndChosenWithCopiedVersionedVector() = runBlocking {
        var openCount = 0
        var frame = Frame()
        val inference = FaceEmbeddingPipeline("yunet-test", "sface-test") {
            listOf(face(100, 60, 7), face(20, 10, 3))
        }
        val capture = QueryFaceCapture(
            FaceFrameReader { lease, budget ->
                assertEquals(source.media, lease.media)
                assertEquals(source.permissionEpoch, lease.permissionEpoch)
                assertEquals(960, budget)
                openCount++
                frame
            }, inference, { true }, { _, _ -> null })
        capture.capture(source)
        val state = capture.state.value as QueryFaceCaptureState.Ready
        assertEquals(2, state.faces.size)
        assertTrue(state.faces[0].query.sourceBox.top < state.faces[1].query.sourceBox.top)
        assertEquals(1f, capture.choose(0)!!.embedding[3], 0f)
        assertEquals(1f, capture.choose(1)!!.embedding[7], 0f)
        assertEquals("sface-test", capture.choose(0)!!.modelVersion)
        val external = capture.choose(0)!!.embedding
        external[3] = 0f
        assertEquals(1f, capture.choose(0)!!.embedding[3], 0f)
        assertTrue(frame.closed)
        assertEquals(1, openCount)
        assertNull(capture.choose(2))
        capture.clear()
        assertNull(capture.choose(0))
    }

    @Test fun zeroFacesIsTypedEmptyNotFailure() = runBlocking {
        val frame = Frame()
        val inference = FaceEmbeddingPipeline("yunet-test", "sface-test") { emptyList() }
        val capture = QueryFaceCapture(
            FaceFrameReader { _, _ -> frame }, inference, { true }, { _, _ -> null })
        capture.capture(source)
        assertTrue(capture.state.value is QueryFaceCaptureState.Empty)
        assertTrue(frame.closed)
        assertNull(capture.choose(0))
    }

    @Test fun busyReservationBlocksDecodeAndIsNotAnEmptyFace() = runBlocking {
        val inference = FaceEmbeddingPipeline("yunet-test", "sface-test") { listOf(face(10, 10, 0)) }
        var opens = 0
        val capture = QueryFaceCapture(
            FaceFrameReader { _, _ -> opens++; Frame() }, inference, { true },
            { _, _ -> null }, busyWaitMs = 200L)
        val held = inference.tryReserve()!!
        try {
            capture.capture(source)
            assertTrue(capture.state.value is QueryFaceCaptureState.Busy)
            assertEquals(0, opens)
        } finally {
            held.close()
        }
        capture.capture(source)
        assertTrue(capture.state.value is QueryFaceCaptureState.Ready)
    }

    /**
     * A scan batch (or a just-replaced capture) briefly holding the shared
     * reservation is waited out, not shown as Busy (FaceSearchJourneyTest
     * flake: three immediate "Try again" taps all met a held slot).
     */
    @Test fun briefScanHoldIsWaitedOutWithoutASecondInference() = runBlocking {
        val inference = FaceEmbeddingPipeline("yunet-test", "sface-test") { listOf(face(10, 10, 0)) }
        var opens = 0
        val capture = QueryFaceCapture(
            FaceFrameReader { _, _ -> opens++; Frame() }, inference, { true },
            { _, _ -> null }, busyWaitMs = 5_000L)
        val holder = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val locked = java.util.concurrent.CountDownLatch(1)
            val release = holder.submit {
                val held = inference.tryReserve()!!
                locked.countDown()
                Thread.sleep(300) // one photo of a background scan
                held.close()
            }
            locked.await()
            capture.capture(source)
            release.get()
            assertTrue(capture.state.value is QueryFaceCaptureState.Ready)
            assertEquals(1, opens) // decoded once, after the slot was free
        } finally {
            holder.shutdownNow()
        }
    }

    @Test fun revokeDuringInferenceDropsAllCandidatesAndBlocksChoose() = runBlocking {
        var granted = true
        val frame = Frame()
        val inference = FaceEmbeddingPipeline("yunet-test", "sface-test") {
            granted = false
            listOf(face(10, 10, 0))
        }
        val capture = QueryFaceCapture(
            FaceFrameReader { _, _ -> frame }, inference, { granted }, { _, _ -> null })
        capture.capture(source)
        assertTrue(capture.state.value is QueryFaceCaptureState.Unavailable)
        assertTrue(frame.closed)
        assertNull(capture.choose(0))
    }

    @Test fun revokeAfterReadyInvalidatesSelectionAndBadVectorIsFailure() = runBlocking {
        var granted = true
        val valid = FaceEmbeddingPipeline("yunet-test", "sface-test") { listOf(face(20, 10, 0)) }
        val capture = QueryFaceCapture(FaceFrameReader { _, _ -> Frame() }, valid,
            { granted }, { _, _ -> null })
        capture.capture(source)
        assertTrue(capture.state.value is QueryFaceCaptureState.Ready)
        granted = false
        assertNull(capture.choose(0))
        assertTrue(capture.state.value is QueryFaceCaptureState.Unavailable)

        val corrupt = FaceEmbeddingPipeline("yunet-test", "sface-test") {
            listOf(FaceEmbeddedDetection(FacePixelBounds(0, 0, 40, 40), FloatArray(128)))
        }
        val brokenFrame = Frame()
        val broken = QueryFaceCapture(
            FaceFrameReader { _, _ -> brokenFrame }, corrupt, { true },
            { _, _ -> null })
        broken.capture(source)
        assertTrue(broken.state.value is QueryFaceCaptureState.Failure)
        assertTrue(brokenFrame.closed)
        assertNull(broken.choose(0))
        corrupt.tryReserve().use { assertTrue(it != null) }
    }
}
