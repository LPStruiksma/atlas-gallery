package nl.atlas.gallery.featureface

import nl.atlas.gallery.featureface.onnx.YuNetFace
import org.junit.Assert.*
import org.junit.Test

/** Synthetic crop only: no real people or user media. */
class FaceQualityTest {
    private val n = 112
    private fun crop(contrast: Int = 70, exposure: Int = 128, blur: Boolean = false): FloatArray {
        val gray = FloatArray(n * n) { i ->
            val x = i % n
            val y = i / n
            val pattern = if (blur) {
                if ((x / 28 + y / 28) % 2 == 0) 1 else -1
            } else {
                if ((x + y) % 2 == 0) 1 else -1
            }
            (exposure + pattern * contrast).coerceIn(0, 255).toFloat()
        }
        return FloatArray(n * n * 3) { gray[it % (n * n)] }
    }
    private fun face(noseX: Float = 56f, shortEdge: Float = 160f): YuNetFace = YuNetFace(
        8f, 8f, shortEdge, shortEdge, 0.99f,
        floatArrayOf(35f, 45f, 77f, 45f, noseX, 65f, 42f, 83f, 70f, 83f)
    )
    private fun score(face: YuNetFace = face(), rgb: FloatArray = crop()) =
        FaceQualityScorer.score(face, 320, 320, rgb)

    @Test fun sharpCropBeatsBlurredCrop() {
        assertTrue(score().score > score(rgb = crop(blur = true)).score)
    }
    @Test fun correctExposureBeatsClippedAndDark() {
        val normal = score().score
        assertTrue(normal > score(rgb = crop(exposure = 255)).score)
        assertTrue(normal > score(rgb = crop(exposure = 0)).score)
    }
    @Test fun frontalLandmarksBeatAsymmetricProfile() {
        assertTrue(score().score > score(face = face(noseX = 92f)).score)
    }
    @Test fun edgeTruncationReducesScore() {
        val centered = face()
        val cut = YuNetFace(0f, 0f, 160f, 160f, 0.99f, centered.landmarks)
        assertTrue(score(face = centered).score > score(face = cut).score)
    }

    @Test fun smallFaceIsFlaggedAndPenalized() {
        val small = score(face = face(shortEdge = 64f))
        assertTrue(small.belowSizeFloor)
        assertFalse(score().belowSizeFloor)
        assertTrue(score().score > small.score)
    }
    @Test fun scoresAreFiniteAndVersioned() {
        val scored = score()
        assertTrue(scored.score in 0f..1f)
        assertEquals(FaceQualityScorer.VERSION, scored.qualityVersion)
    }
}
