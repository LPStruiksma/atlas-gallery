package nl.atlas.gallery.featureface

import org.junit.Assert.*
import org.junit.Test

/** Synthetic boxes/qualities only; the backfill is not allowed to re-embed old rows. */
class FaceQualityBackfillTest {
    private val uid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val source = FaceQualityBackfillSource(
        FaceMediaKey("synthetic:1", "revision"), "yunet-v1", "sface-v1",
        listOf(FaceQualityBackfillFace(uid, UnknownFaceBox(.1f, .1f, .5f, .5f), null))
    )
    private val score = FaceQuality(.8f, belowSizeFloor = false)

    private class Frame : BoundedFaceFrame {
        override val widthPixels = 200
        override val heightPixels = 200
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun nullRowsAreScoredOnceAndExistingScoresAreUntouched() {
        var pending = true
        var stored: FaceQuality? = null
        var writes = 0
        var decoded = 0
        val runner = FaceQualityBackfillRunner(
            epoch = "epoch",
            next = { if (pending) source else null },
            store = { _, quality ->
                if (stored == null) {
                    stored = quality[uid]
                    if (stored != null) { writes++; pending = false; 1 } else 0
                } else 0
            },
            reader = FaceFrameReader { _, _ -> Frame().also { decoded++ } },
            scoreOnly = {
                listOf(FaceQualityScoredDetection(FacePixelBounds(20, 20, 100, 100), score))
            },
            canContinue = { true }
        )
        assertEquals(1, runner.run(null).written)
        assertEquals(score, stored)
        assertEquals(1, writes)
        assertEquals(0, runner.run(null).written)
        assertEquals(1, decoded)
    }

    @Test fun chargingLossDuringInferenceDiscardsScoresBeforeCommit() {
        var charging = true
        var writes = 0
        val frame = Frame()
        val runner = FaceQualityBackfillRunner(
            "epoch", { source }, { _, _ -> writes++; 1 },
            FaceFrameReader { _, _ -> frame },
            { charging = false
              listOf(FaceQualityScoredDetection(FacePixelBounds(20, 20, 100, 100), score)) },
            { charging }
        )
        val result = runner.run(null)
        assertTrue(result.held)
        assertEquals(0, writes)
        assertTrue(frame.closed)
    }

    @Test fun mismatchedGeometryDoesNotOverwriteAnExistingObservation() {
        var writes = 0
        val runner = FaceQualityBackfillRunner(
            "epoch", { source }, { _, values -> writes += values.size; values.size },
            FaceFrameReader { _, _ -> Frame() },
            { listOf(FaceQualityScoredDetection(FacePixelBounds(140, 140, 190, 190), score)) },
            { true }
        )
        assertEquals(0, runner.run(null).written)
        assertEquals(0, writes)
    }

    @Test fun unpluggedNeverOpensMedia() {
        var opens = 0
        val runner = FaceQualityBackfillRunner(
            "epoch", { source }, { _, _ -> 1 },
            FaceFrameReader { _, _ -> opens++; Frame() },
            { emptyList() }, { false }
        )
        assertTrue(runner.run(null).held)
        assertEquals(0, opens)
    }
}
