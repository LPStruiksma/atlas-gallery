package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class GalleryZoomLadderTest {
    @Test fun everySquareLevelMapsWithoutChangingExistingDensityIdentity() {
        assertEquals(listOf(3, 4, 7), GridDensity.entries.map { it.columns })
        for (density in GridDensity.entries)
            assertEquals(density, GalleryZoomStop.forDensity(density).squareDensityOrNull())
        assertNull(GalleryZoomStop.ORIGINAL.squareDensityOrNull())
    }

    @Test fun largePinchOutReachesUncroppedRowsAndPinchInReturnsToLarge() {
        val open = GalleryZoomGesture(GalleryZoomStop.LARGE)
        open.addZoomFactor(1.20f)
        assertEquals(GalleryZoomStop.ORIGINAL, open.settle())
        val close = GalleryZoomGesture(GalleryZoomStop.ORIGINAL)
        close.addZoomFactor(0.82f)
        assertEquals(GalleryZoomStop.LARGE, close.settle())
    }

    @Test fun missingHostDestinationPreservesExistingThreeStops() {
        val guarded = GalleryZoomGesture(GalleryZoomStop.LARGE, allowOriginal = false)
        repeat(9) { guarded.addZoomFactor(1.3f) }
        assertEquals(GalleryZoomStop.LARGE, guarded.settle())
        val smaller = GalleryZoomGesture(GalleryZoomStop.LARGE, allowOriginal = false)
        smaller.addZoomFactor(0.82f)
        assertEquals(GalleryZoomStop.MEDIUM, smaller.settle())
    }

    @Test fun pinchCanCrossMoreThanOneLevelAndClampAtEndpoints() {
        val inward = GalleryZoomGesture(GalleryZoomStop.ORIGINAL)
        repeat(12) { inward.addZoomFactor(0.78f) }
        assertEquals(GalleryZoomStop.SMALL, inward.settle())
        val outward = GalleryZoomGesture(GalleryZoomStop.SMALL)
        repeat(12) { outward.addZoomFactor(1.28f) }
        assertEquals(GalleryZoomStop.ORIGINAL, outward.settle())
    }

    @Test fun noiseInvalidFactorsAndCoalescedOutliersDoNotSwitchLevel() {
        val gesture = GalleryZoomGesture(GalleryZoomStop.LARGE)
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 1.01f, 1.02f)
            .forEach(gesture::addZoomFactor)
        assertEquals(GalleryZoomStop.LARGE, gesture.settle())
        val outlier = GalleryZoomGesture(GalleryZoomStop.LARGE)
        outlier.addZoomFactor(50f)
        assertEquals(GalleryZoomStop.LARGE, outlier.settle())
    }

    @Test fun strongSingleSampleStillChangesOneStopAndReversalIsStable() {
        val gesture = GalleryZoomGesture(GalleryZoomStop.MEDIUM)
        gesture.addZoomFactor(1.2f)
        assertEquals(GalleryZoomStop.LARGE, gesture.settle())
        gesture.addZoomFactor(1f / 1.2f)
        assertEquals(GalleryZoomStop.MEDIUM, gesture.settle())
    }

    @Test fun staleGrantReorderAndInvalidFocusAreRejectedInConstantTime() {
        val anchor = GridFocalAnchor("synthetic:42", 42, .3f, .7f)
        val request = GalleryZoomRequest(anchor, 280f, 91L)
        var lookups = 0
        fun key(index: Int): String? { lookups++; return if (index == 42) "synthetic:42" else null }
        assertTrue(request.isCurrentFor(91L, 150_000, ::key))
        assertEquals(1, lookups)
        assertFalse(request.isCurrentFor(92L, 150_000, ::key))
        assertEquals(1, lookups) // revoked publication never reads a media key
        assertFalse(request.isCurrentFor(91L, 42, ::key))
        assertEquals(1, lookups)
        assertFalse(request.isCurrentFor(91L, 150_000) { "synthetic:reordered" })
        assertFalse(GalleryZoomRequest(anchor, Float.NaN, 91L)
            .isCurrentFor(91L, 150_000, ::key))
        assertTrue(GalleryZoomRequest(null, 0f, 91L)
            .isCurrentFor(91L, 150_000, ::key))
    }

    @Test fun zoomHandoffCarriesGrantEpochAndFocalFractionsWithoutGuessing() {
        val anchor = GridFocalAnchor("synthetic:42", 42, .3f, .7f)
        val request = GalleryZoomRequest(anchor, 280f, 91L)
        assertEquals(42, request.focal?.itemIndex)
        assertEquals(.7f, request.focal!!.fractionY, 0.0f)
        assertEquals(91L, request.catalogueEpoch)
        assertNull(request.destination) // compatibility for previously built callers
        assertEquals(GalleryZoomStop.ORIGINAL, GalleryZoomRequest(
            anchor, 280f, 91L, GalleryZoomStop.ORIGINAL).destination)
        assertEquals(GridDensity.LARGE, GalleryZoomRequest(
            anchor, 280f, 91L, GalleryZoomStop.LARGE)
            .destination?.squareDensityOrNull())
        assertNull(GalleryZoomRequest(null, 280f, 92L).focal)
    }
}
