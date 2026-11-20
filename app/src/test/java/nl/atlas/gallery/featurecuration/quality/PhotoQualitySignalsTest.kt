package nl.atlas.gallery.featurecuration.quality

import org.junit.Assert.*
import org.junit.Test

class PhotoQualitySignalsTest {
    private fun argb(gray: Int, alpha: Int = 255) = (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray

    @Test fun flatAndAlternatingEdgesAreSeparated() {
        val flat = PhotoQualitySignals.analyseArgb8888(IntArray(32 * 32) { argb(128) }, 32, 32)
        val edges = PhotoQualitySignals.analyseArgb8888(IntArray(32 * 32) { i -> argb(if ((i % 32) % 2 == 0) 0 else 255) }, 32, 32)
        assertEquals(0.0, flat.blur!!.laplacianVariance, 0.0)
        assertTrue(edges.blur!!.laplacianVariance > flat.blur!!.laplacianVariance)
    }

    @Test fun blurredStripesHaveLessEnergy() {
        val sharp = IntArray(32 * 32) { i -> argb(if ((i % 32) / 4 % 2 == 0) 0 else 255) }
        val blurred = IntArray(32 * 32) { i -> argb(intArrayOf(32, 32, 96, 96, 160, 160, 224, 224)[(i % 32) % 8]) }
        assertTrue(PhotoQualitySignals.analyseArgb8888(sharp, 32, 32).blur!!.laplacianVariance >
            PhotoQualitySignals.analyseArgb8888(blurred, 32, 32).blur!!.laplacianVariance)
    }

    @Test fun exposureReportsBlackWhiteAndNormalPattern() {
        assertEquals(1.0, PhotoQualitySignals.analyseArgb8888(IntArray(100) { argb(0) }, 10, 10).exposure!!.clippedDarkFraction, 0.0)
        assertEquals(1.0, PhotoQualitySignals.analyseArgb8888(IntArray(100) { argb(255) }, 10, 10).exposure!!.clippedLightFraction, 0.0)
        val normal = PhotoQualitySignals.analyseArgb8888(IntArray(100) { argb(40 + it % 180) }, 10, 10).exposure!!
        assertEquals(0.0, normal.clippedDarkFraction, 0.0)
        assertEquals(0.0, normal.clippedLightFraction, 0.0)
        assertTrue(normal.spread > .1)
    }

    @Test fun invalidSmallAndMaskedInputsAreUnknown() {
        assertNull(PhotoQualitySignals.analyseArgb8888(intArrayOf(), 2, 2).blur)
        assertNull(PhotoQualitySignals.analyseArgb8888(IntArray(20), 10, 10, 5).exposure)
        assertNull(PhotoQualitySignals.analyseArgb8888(IntArray(100) { argb(50, 0) }, 10, 10).blur)
        assertNull(PhotoQualitySignals.analyseArgb8888(IntArray(16) { argb(50) }, 4, 4).exposure)
    }

    @Test fun faceEvidenceDoesNotInventMissingLandmarks() {
        val missing = PhotoQualitySignals.aggregateFaces(listOf(FaceLandmarkObservation(null, null, null)))
        assertNull(missing.eyeOpenMean)
        assertNull(missing.smileMean)
        assertEquals(1, missing.observedFaces)
        val known = PhotoQualitySignals.aggregateFaces(listOf(FaceLandmarkObservation(.8f, .6f, .5f)))
        assertEquals(.7f, known.eyeOpenMean!!, .0001f)
        assertEquals(.5f, known.smileMean!!, 0f)
    }
}
