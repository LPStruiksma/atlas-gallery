package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class RatioViewportAdmissionTest {
    @Test fun splitScreenAndHighDensityInputsRejectBeforePlannerConstruction() {
        // At density 3x, 3dp inset/gap = 9 physical pixels; old UI's width>8
        // passed these into a RatioLayoutOptions constructor that throws.
        for (width in 9..27) {
            assertFalse("width=$width", canPlanRatioViewport(width, 640, 9, 9))
        }
        assertTrue(canPlanRatioViewport(28, 640, 9, 9))
        assertTrue(canPlanRatioViewport(420, 640, 9, 9))
    }

    @Test fun missingHeightOrNegativePaddingIsAlwaysRejected() {
        assertFalse(canPlanRatioViewport(420, 0, 9, 9))
        assertFalse(canPlanRatioViewport(420, -1, 9, 9))
        assertFalse(canPlanRatioViewport(420, 640, -1, 9))
        assertFalse(canPlanRatioViewport(420, 640, 9, -1))
        assertFalse(canPlanRatioViewport(0, 640, 0, 0))
    }

    @Test fun overflowDoesNotMisclassifyImpossibleViewportAsValid() {
        assertFalse(canPlanRatioViewport(420, 640, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(canPlanRatioViewport(Int.MAX_VALUE, 640, Int.MAX_VALUE, 0))
        assertFalse(canPlanRatioViewport(Int.MAX_VALUE, 640, 0, Int.MAX_VALUE))
        assertTrue(canPlanRatioViewport(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0))
    }

    @Test fun thresholdAgreementAcrossAdversarialPhysicalDimensions() {
        for (inset in 0..80) for (gap in 0..80) {
            val threshold = 2L * inset + gap
            val justBelow = threshold.toInt()
            assertFalse(canPlanRatioViewport(justBelow, 500, inset, gap))
            assertTrue(canPlanRatioViewport(justBelow + 1, 500, inset, gap))
        }
    }
}
