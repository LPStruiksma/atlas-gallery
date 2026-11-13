package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

/** #494: the pinch ladder as a pure state machine, no gesture plumbing. */
class GalleryPinchTrackerTest {
    private val slop = 21f // API35 emulator touch slop in px

    /** Feeds a two-finger pinch moving each finger linearly from [from] to [to] px from the centroid. */
    private fun GalleryPinchTracker.pinch(from: Float, to: Float, samples: Int = 40,
                                          cx: Float = 500f, cy: Float = 800f) {
        for (i in 0..samples) onSample(2, from + (to - from) * i / samples, cx, cy)
        onSample(1, Float.NaN, cx, cy) // first finger lifts
        onSample(0, Float.NaN, cx, cy)
    }

    @Test fun spreadFromMediumGridWalksPastLargeIntoOriginalRows() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        tracker.pinch(from = 40f, to = 430f)
        assertTrue(tracker.started)
        assertEquals(GalleryZoomStop.ORIGINAL, tracker.destination())
    }

    @Test fun timelineAndPlainGridShareOriginalStopWhenHostProvidesRoute() {
        for (timelineEnabled in listOf(true, false)) {
            val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop,
                allowOriginal = true)
            tracker.pinch(from = 40f, to = 430f)
            assertEquals("mode timeline=$timelineEnabled", GalleryZoomStop.ORIGINAL,
                tracker.destination())
        }
    }

    @Test fun plainGridWithoutHostRouteRemainsOnSquareStops() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.LARGE, slop,
            allowOriginal = false)
        tracker.pinch(from = 40f, to = 430f)
        assertNull(tracker.destination())
    }

    @Test fun originalIsUnreachableWithoutHostDestination() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop, allowOriginal = false)
        tracker.pinch(from = 40f, to = 430f)
        assertEquals(GalleryZoomStop.LARGE, tracker.destination())
    }

    @Test fun pinchInFromOriginalRowsReturnsToLargeAndABigPinchGoesFurther() {
        val small = GalleryPinchTracker(GalleryZoomStop.ORIGINAL, slop)
        small.pinch(from = 300f, to = 200f)
        assertEquals(GalleryZoomStop.LARGE, small.destination())
        val big = GalleryPinchTracker(GalleryZoomStop.ORIGINAL, slop)
        big.pinch(from = 430f, to = 40f)
        assertEquals(GalleryZoomStop.SMALL, big.destination())
    }

    /** The #475 probe never started: its fingers moved less than slop. Slop is measured on the span itself. */
    @Test fun startsOnceSpanTravelledMoreThanSlopNotBefore() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.LARGE, slop)
        assertFalse(tracker.onSample(2, 100f, 0f, 0f))
        assertFalse(tracker.onSample(2, 115f, 0f, 0f))
        assertFalse(tracker.onSample(2, 120f, 0f, 0f)) // 20 px < slop
        assertTrue(tracker.onSample(2, 122f, 0f, 0f))
        assertTrue(tracker.started)
    }

    @Test fun belowSlopJitterAndOneFingerScrollsAreNeverConsumed() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        assertFalse(tracker.onSample(1, Float.NaN, 10f, 10f))
        listOf(100f, 110f, 95f, 108f, 100f).forEach { assertFalse(tracker.onSample(2, it, 0f, 0f)) }
        assertFalse(tracker.onSample(1, Float.NaN, 0f, 0f))
        assertFalse(tracker.started)
        assertNull(tracker.destination())
    }

    @Test fun startedPinchConsumesLiftsSoNoTileOpens() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        tracker.onSample(2, 50f, 0f, 0f)
        assertTrue(tracker.onSample(2, 200f, 0f, 0f))
        assertTrue(tracker.onSample(1, Float.NaN, 0f, 0f))
        assertTrue(tracker.onSample(0, Float.NaN, 0f, 0f))
    }

    @Test fun fingerCountChangesRebaselineInsteadOfJumping() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        tracker.onSample(2, 100f, 0f, 0f)
        // A third finger lands far away: span jumps but nothing moved.
        assertFalse(tracker.onSample(3, 400f, 0f, 0f))
        assertFalse(tracker.onSample(3, 405f, 0f, 0f))
        // Back to two fingers at their old span.
        assertFalse(tracker.onSample(2, 100f, 0f, 0f))
        assertFalse(tracker.started)
        assertNull(tracker.destination())
    }

    @Test fun invalidSpansAreIgnored() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.LARGE, slop)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -5f).forEach {
            assertFalse(tracker.onSample(2, it, 0f, 0f))
        }
        assertNull(tracker.destination())
    }

    @Test fun focusFollowsTheFingersCentroidForTheAnchor() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        tracker.pinch(from = 40f, to = 430f, cx = 312f, cy = 1234f)
        assertEquals(312f, tracker.focusX, 0f)
        assertEquals(1234f, tracker.focusY, 0f)
    }

    @Test fun reversingWithinOneGestureSettlesBackOnTheStartStop() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        for (s in 0..5) tracker.onSample(2, 100f + s * 10f, 0f, 0f)
        for (s in 0..5) tracker.onSample(2, 150f - s * 10f, 0f, 0f)
        assertTrue(tracker.started)
        assertNull(tracker.destination())
    }

    @Test fun pinchSpanIsMeanDistanceFromCentroid() {
        val (span, cx, cy) = pinchSpan(floatArrayOf(100f, 300f), floatArrayOf(50f, 50f))!!
        assertEquals(100f, span, 1e-4f)
        assertEquals(200f, cx, 1e-4f)
        assertEquals(50f, cy, 1e-4f)
        assertNull(pinchSpan(FloatArray(0), FloatArray(0)))
    }

    /** #507: the live zoom drives the continuous transition. */
    @Test fun zoomFollowsTheFingersFromOneAndIsClamped() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        assertEquals(1f, tracker.zoom, 0f)
        tracker.onSample(2, 100f, 0f, 0f)
        tracker.onSample(2, 110f, 0f, 0f) // under slop: not started, no zoom
        assertEquals(1f, tracker.zoom, 0f)
        tracker.onSample(2, 150f, 0f, 0f)
        assertEquals(1.5f, tracker.zoom, 0.001f)
        tracker.onSample(2, 120f, 0f, 0f)
        assertEquals(1.2f, tracker.zoom, 0.001f)
        tracker.onSample(2, 2_000f, 0f, 0f)
        assertEquals(GalleryPinchTracker.MAX_ZOOM, tracker.zoom, 0f)
        val pinchIn = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        pinchIn.pinch(from = 430f, to = 10f)
        assertEquals(GalleryPinchTracker.MIN_ZOOM, pinchIn.zoom, 0f)
    }

    @Test fun aThirdFingerDoesNotJumpTheZoom() {
        val tracker = GalleryPinchTracker(GalleryZoomStop.MEDIUM, slop)
        tracker.onSample(2, 100f, 0f, 0f)
        tracker.onSample(2, 150f, 0f, 0f)
        val before = tracker.zoom
        tracker.onSample(3, 400f, 0f, 0f) // new finger set re-baselines the span
        assertEquals(before, tracker.zoom, 0f)
        tracker.onSample(3, 440f, 0f, 0f)
        assertEquals(before * 1.1f, tracker.zoom, 0.001f)
    }
}
