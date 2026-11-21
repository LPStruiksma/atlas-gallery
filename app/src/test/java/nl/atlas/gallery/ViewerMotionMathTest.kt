package nl.atlas.gallery

import org.junit.Assert.*
import nl.atlas.gallery.featuredesign.AtlasMotion
import org.junit.Test
import kotlin.math.abs

/** Pure synthetic geometry tests; no source media, decoder, Android device or network. */
class ViewerMotionMathTest {
    @Test fun continuousStripTracksFractionAndReversalAt150k() {
        assertEquals(ViewerStripPosition(0, 0),
            viewerStripPosition(0, -.4f, 150_000, 60))
        assertEquals(ViewerStripPosition(149_999, 0),
            viewerStripPosition(149_999, .4f, 150_000, 60))
        assertEquals(ViewerStripPosition(1_200, 15),
            viewerStripPosition(1_200, .25f, 150_000, 60))
        assertEquals(ViewerStripPosition(1_199, 45),
            viewerStripPosition(1_200, -.25f, 150_000, 60))
        assertEquals(ViewerStripPosition(100, 0),
            viewerStripPosition(100, Float.NaN, 150_000, 60))
        val frames = (0..40).map {
            viewerStripPosition(99, -.5f + it * .025f, 150_000, 60)
        }
        val pixels = frames.map { it.index.toLong() * 60 + it.scrollOffsetPx }
        assertTrue(pixels.zipWithNext().all { it.first <= it.second })
        assertEquals(5_910L, pixels.first())
        assertEquals(5_970L, pixels.last())
    }

    @Test fun doubleTapAnchorsToTouchWhenNotNearBoundsAndResets() {
        val zoom = viewerDoubleTapZoom(ViewerZoomPosition(), 750f, 500f,
            1_000, 1_000, 1_000, 1_000)
        assertEquals(2.5f, zoom.scale, .001f)
        assertEquals(-375f, zoom.translationX, .001f)
        val mappedFocal = 500f + (750f - 500f) * zoom.scale + zoom.translationX
        assertEquals(750f, mappedFocal, .001f)
        assertEquals(ViewerZoomPosition(), viewerDoubleTapZoom(zoom,
            750f, 500f, 1_000, 1_000, 1_000, 1_000))
    }

    @Test fun zoomPanClampsLandscapePortraitAndUnknownSources() {
        val portrait = viewerZoomGesture(ViewerZoomPosition(scale = 3f),
            1f, 100_000f, -100_000f, 500f, 500f,
            1_000, 1_000, 1_000, 2_000)
        assertEquals(250f, portrait.translationX, .001f)
        assertEquals(-1_000f, portrait.translationY, .001f)
        val narrow = viewerDoubleTapZoom(ViewerZoomPosition(),
            800f, 500f, 1_000, 1_000, 1_000, 2_000)
        assertEquals(-125f, narrow.translationX, .001f)
        val unknown = viewerZoomGesture(ViewerZoomPosition(),
            3f, 300f, 300f, 500f, 500f, 1_000, 1_000, 0, 0)
        assertEquals(3f, unknown.scale, .001f)
        assertEquals(0f, unknown.translationX, .001f)
        assertEquals(0f, unknown.translationY, .001f)
    }

    @Test fun randomAspectAndRotationReboundsStayWithinFiniteBounds() {
        val widths = intArrayOf(1, 29, 300, 1_000, 7_680, 20_000)
        val heights = intArrayOf(1, 90, 240, 1_800, 8_640, 15_000)
        for (w in widths) for (h in heights) for (scale in listOf(1f, 1.2f, 2.5f, 5f)) {
            val gesture = viewerZoomGesture(
                ViewerZoomPosition(), scale, 10_000f, -10_000f,
                1_000f, 100f, 1_440, 3_120, w, h)
            assertTrue(gesture.scale in 1f..5f)
            assertTrue(gesture.translationX.isFinite())
            assertTrue(gesture.translationY.isFinite())
            val rebound = viewerReboundZoom(gesture, 3_120, 1_440, h, w)
            assertTrue(rebound.scale in 1f..5f)
            assertTrue(rebound.translationX.isFinite())
            assertTrue(rebound.translationY.isFinite())
            val again = viewerReboundZoom(rebound, 3_120, 1_440, h, w)
            assertTrue(abs(again.translationX - rebound.translationX) < .001f)
            assertTrue(abs(again.translationY - rebound.translationY) < .001f)
        }
    }

    @Test fun ordinaryPagerSwipeIsNeverClaimedByPhotoAtRest() {
        assertFalse(viewerMayHandleTransform(0, 1f))
        assertFalse(viewerMayHandleTransform(1, 1f))
        assertFalse(viewerMayHandleTransform(1, 1.005f))
        assertFalse(viewerMayHandleTransform(1, Float.NaN))
        assertTrue(viewerMayHandleTransform(2, 1f))
        assertTrue(viewerMayHandleTransform(2, 1.005f))
        assertTrue(viewerMayHandleTransform(1, 2.5f))
        assertTrue(viewerMayHandleTransform(1, 5f))
        assertFalse(viewerMayHandleTransform(0, 5f))
    }

    @Test fun pinchOwnershipPersistsThroughFingerReleaseAndReturnToFit() {
        var claimed = false
        // A fresh single pointer at fit must remain owned by HorizontalPager.
        claimed = viewerGestureOwned(claimed, 1, 1f)
        assertFalse(claimed)
        // A real two-finger gesture claims ownership, including a translation
        // that produces zero net zoom and hits the photo's hard pan bounds.
        claimed = viewerGestureOwned(claimed, 2, 1f)
        assertTrue(claimed)
        // Lifting one pointer after pinching back to fit must not page.
        claimed = viewerGestureOwned(claimed, 1, 1f)
        assertTrue(claimed)
        claimed = viewerGestureOwned(claimed, 0, 1f)
        assertTrue(claimed)
        // Fresh gesture after all pointers release starts unclaimed.
        assertFalse(viewerGestureOwned(false, 1, 1f))
        assertTrue(viewerGestureOwned(false, 1, 2.5f))
        // A scale-1 gesture after a corrupted nonfinite zoom also stays pager-owned.
        assertFalse(viewerGestureOwned(false, 1, Float.NaN))
    }

    @Test fun aNewGestureAtTheImageEdgeCanAdvanceExactlyOnePhoto() {
        // 1000x1000 square at 2.5x in a 1000x1000 viewport:
        // max horizontal pan is 750px. Only an outward swipe at that
        // boundary may navigate; touching the boundary from inside does not.
        val rightEdge = ViewerZoomPosition(2.5f, -750f, 0f)
        val centered = ViewerZoomPosition(2.5f, 0f, 0f)
        val leftEdge = ViewerZoomPosition(2.5f, 750f, 0f)
        assertEquals(1, viewerEdgeSwipeDirection(
            rightEdge, rightEdge, -25f, 0f, 1000, 1000, 1000, 1000))
        assertEquals(-1, viewerEdgeSwipeDirection(
            leftEdge, leftEdge, 25f, 0f, 1000, 1000, 1000, 1000))
        assertEquals(0, viewerEdgeSwipeDirection(
            centered, rightEdge, -25f, 0f, 1000, 1000, 1000, 1000))
        assertEquals(0, viewerEdgeSwipeDirection(
            rightEdge, rightEdge, -25f, 50f, 1000, 1000, 1000, 1000))
        assertEquals(0, viewerEdgeSwipeDirection(
            rightEdge, rightEdge, 25f, 0f, 1000, 1000, 1000, 1000))
        assertEquals(0, viewerEdgeSwipeDirection(
            ViewerZoomPosition(), ViewerZoomPosition(), -25f, 0f,
            1000, 1000, 1000, 1000))
        assertEquals(2, viewerEdgePageTarget(1, 3, -130f, 1000))
        assertEquals(0, viewerEdgePageTarget(1, 3, 130f, 1000))
        assertNull(viewerEdgePageTarget(1, 3, -110f, 1000))
        assertNull(viewerEdgePageTarget(2, 3, -130f, 1000))
        assertNull(viewerEdgePageTarget(0, 3, 130f, 1000))
        assertNull(viewerEdgePageTarget(1, 3, Float.NaN, 1000))
    }

    @Test fun edgeSwipeMustNotLeakOutOfPortraitOrInvalidGeometry() {
        // Portrait 1000x2000 fits at 0.5x in a square viewport. At 2.5x
        // the horizontal pan extent is (1000 * .5 * 2.5 - 1000)/2 = 125.
        // Centered at x=0 is NOT the image edge: an outward drag must
        // continue panning the photo, never skip to the next gallery item.
        val centered = ViewerZoomPosition(2.5f)
        val edge = ViewerZoomPosition(2.5f, -125f)
        assertEquals(0, viewerEdgeSwipeDirection(
            centered, centered, -20f, 0f, 1000, 1000, 1000, 2000))
        assertEquals(1, viewerEdgeSwipeDirection(
            edge, edge, -20f, 0f, 1000, 1000, 1000, 2000))
        assertEquals(0, viewerEdgeSwipeDirection(
            centered, edge, -20f, 0f, 1000, 1000, 1000, 2000))
        assertEquals(0, viewerEdgeSwipeDirection(
            edge, edge, -20f, 0f, 1000, 1000, 0, 2000))
        assertEquals(0, viewerEdgeSwipeDirection(
            edge, edge, Float.NaN, 0f, 1000, 1000, 1000, 2000))
        assertNull(viewerEdgePageTarget(-1, 3, -200f, 1000))
    }

    @Test fun seededPortraitAndLandscapeInteriorPansNeverTurnPage() {
        val random = kotlin.random.Random(20261024)
        repeat(10_000) {
            val imageWidth = random.nextInt(100, 30_001)
            val imageHeight = random.nextInt(100, 30_001)
            val viewportWidth = random.nextInt(400, 3_121)
            val viewportHeight = random.nextInt(400, 3_121)
            val scale = random.nextDouble(1.02, 5.0).toFloat()
            val fit = kotlin.math.min(
                viewportWidth.toDouble() / imageWidth,
                viewportHeight.toDouble() / imageHeight)
            val horizontalBound = ((imageWidth * fit * scale - viewportWidth) / 2.0)
                .coerceAtLeast(0.0).toFloat()
            val center = ViewerZoomPosition(scale)
            val edge = ViewerZoomPosition(scale, -horizontalBound)
            // A swipe started from the center may reach an edge, but must
            // not navigate until a SEPARATE gesture begins at that edge.
            if (horizontalBound > 8f) {
                assertEquals(0, viewerEdgeSwipeDirection(
                    center, center, -20f, 0f,
                    viewportWidth, viewportHeight, imageWidth, imageHeight))
                assertEquals(0, viewerEdgeSwipeDirection(
                    center, edge, -20f, 0f,
                    viewportWidth, viewportHeight, imageWidth, imageHeight))
            }
            assertEquals(1, viewerEdgeSwipeDirection(
                edge, edge, -20f, 0f,
                viewportWidth, viewportHeight, imageWidth, imageHeight))
            assertEquals(0, viewerEdgeSwipeDirection(
                edge, edge, -20f, 25f,
                viewportWidth, viewportHeight, imageWidth, imageHeight))
        }
    }

    @Test fun playedToEndRestartsWhileInterruptedVideoResumes() {
        assertEquals(0L, viewerPlaybackCheckpoint(4_000L, 4_000L, true))
        assertEquals(0L, viewerPlaybackCheckpoint(3_900L, 4_000L, false))
        assertEquals(2_500L, viewerPlaybackCheckpoint(2_500L, 4_000L, false))
        assertEquals(2_500L, viewerPlaybackCheckpoint(2_500L, -9223372036854775807L, false))
        assertEquals(0L, viewerPlaybackCheckpoint(2_500L, -9223372036854775807L, true))
        assertEquals(0L, viewerPlaybackCheckpoint(-50L, 4_000L, false))
        assertEquals(0L, viewerPlaybackCheckpoint(0L, 4_000L, false))
        // No overflow on 64-bit adversarial timestamps/durations.
        assertEquals(0L, viewerPlaybackCheckpoint(Long.MAX_VALUE, Long.MAX_VALUE, false))
        assertEquals(10L, viewerPlaybackCheckpoint(10L, Long.MAX_VALUE, false))
    }

    @Test fun videoResumeLruIsSessionScopedAndBounded() {
        val positions = ViewerPlaybackPositions(2)
        positions.save("content://synthetic/a", 15)
        positions.save("content://synthetic/b", 25)
        assertEquals(15L, positions.get("content://synthetic/a"))
        positions.save("content://synthetic/c", 40)
        assertEquals(0L, positions.get("content://synthetic/b"))
        assertEquals(2, positions.size())
        positions.save("content://synthetic/a", -1)
        positions.save("", 100)
        assertEquals(15L, positions.get("content://synthetic/a"))
        assertEquals(2, positions.size())
    }

    @Test fun rejectsImpossibleStripInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            viewerStripPosition(-1, 0f, 5, 60)
        }
        assertThrows(IllegalArgumentException::class.java) {
            viewerStripPosition(0, 0f, 0, 60)
        }
        assertThrows(IllegalArgumentException::class.java) {
            viewerStripPosition(0, 0f, 5, 0)
        }
    }

    // FLOW-4 (#713): animated double-tap zoom. Galaxy S24+ viewport 1080x2340.
    private val vw = 1_080
    private val vh = 2_340

    /** Eased progress at [ms] into the 160 ms double-tap zoom. */
    private fun eased(ms: Int): Float =
        AtlasMotion.SineInOut70.transform(ms / AtlasMotion.DOUBLE_TAP_ZOOM_MS.toFloat())

    /** Where the photo point that started under ([fx],[fy]) now is on screen. */
    private fun mapped(from: ViewerZoomPosition, pose: ViewerZoomPosition, f: Float, centre: Float,
                       fromT: Float, poseT: Float): Float {
        val u = (f - centre - fromT) / from.scale
        return centre + u * pose.scale + poseT
    }

    private fun assertLegal(pose: ViewerZoomPosition, sw: Int, sh: Int) {
        // A legal pose is one the next finger step does not move (no snap).
        val next = viewerZoomGesture(pose, 1f, 0f, 0f, vw / 2f, vh / 2f, vw, vh, sw, sh)
        assertEquals(pose.scale, next.scale, .001f)
        assertEquals(pose.translationX, next.translationX, .01f)
        assertEquals(pose.translationY, next.translationY, .01f)
    }

    @Test fun doubleTapZoomAnimatesFor160msWithSineInOut() {
        assertEquals(160, AtlasMotion.DOUBLE_TAP_ZOOM_MS)
        assertEquals(0f, eased(0), .001f)
        assertEquals(1f, eased(160), .001f)
        val mid = eased(80)
        assertTrue("sine in-out midpoint, got $mid", mid in .4f..0.75f)
        val curve = (0..160 step 8).map { eased(it) }
        assertTrue(curve.zipWithNext().all { it.first <= it.second + 1e-4f })
    }

    @Test fun doubleTapZoomInAtCentreKeepsTappedPixelStill() {
        val from = ViewerZoomPosition()
        for ((fx, fy) in listOf(540f to 1_170f, 700f to 1_000f, 380f to 1_500f)) {
            val to = viewerDoubleTapZoom(from, fx, fy, vw, vh, vw, vh)
            assertTrue(to.scale > 2f)
            for (ms in listOf(0, 40, 80, 120, 160)) {
                val pose = viewerDoubleTapFrame(from, to, fx, fy, vw, vh, vw, vh, eased(ms))
                val x = mapped(from, pose, fx, vw / 2f, from.translationX, pose.translationX)
                val y = mapped(from, pose, fy, vh / 2f, from.translationY, pose.translationY)
                assertEquals("x drift at $ms ms", fx, x, 8f)
                assertEquals("y drift at $ms ms", fy, y, 8f)
                assertLegal(pose, vw, vh)
            }
            assertEquals(from, viewerDoubleTapFrame(from, to, fx, fy, vw, vh, vw, vh, 0f))
            assertEquals(to, viewerDoubleTapFrame(from, to, fx, fy, vw, vh, vw, vh, 1f))
        }
    }

    @Test fun doubleTapZoomInNearCornerBlendsClampWithoutJump() {
        // A full-screen photo never needs clamping at 2.5x, so the clamped
        // corner case is a 4:3 landscape photo letterboxed on the S24+: its
        // short side still does not fill the screen, so vertical pan stays 0.
        val (sw, sh) = 4_000 to 3_000
        val from = ViewerZoomPosition()
        val (fx, fy) = 40f to 1_000f
        val to = viewerDoubleTapZoom(from, fx, fy, vw, vh, sw, sh)
        assertEquals(2.5f, to.scale, .001f)
        assertEquals(0f, to.translationY, .01f)
        var previous = from
        for (ms in 0..160 step 4) {
            val pose = viewerDoubleTapFrame(from, to, fx, fy, vw, vh, sw, sh, eased(ms))
            assertLegal(pose, sw, sh)
            assertTrue(pose.scale >= previous.scale - .001f)
            // The free axis still pivots on the tap.
            val x = mapped(from, pose, fx, vw / 2f, from.translationX, pose.translationX)
            assertEquals("x drift at $ms ms", fx, x, 8f)
            // Never a jump: each 4 ms frame moves the photo a bounded amount.
            assertTrue("jump at $ms ms", abs(pose.translationX - previous.translationX) < 120f)
            assertTrue("jump at $ms ms", abs(pose.translationY - previous.translationY) < 1f)
            previous = pose
        }
        assertEquals(to, previous)
    }

    @Test fun doubleTapZoomOutReturnsSmoothlyToFit() {
        val zoomed = viewerDoubleTapZoom(ViewerZoomPosition(), 700f, 1_000f, vw, vh, vw, vh)
        val to = viewerDoubleTapZoom(zoomed, 700f, 1_000f, vw, vh, vw, vh)
        assertEquals(ViewerZoomPosition(), to)
        val mid = viewerDoubleTapFrame(zoomed, to, 700f, 1_000f, vw, vh, vw, vh, eased(80))
        assertTrue(mid.scale > 1f && mid.scale < zoomed.scale)
        assertLegal(mid, vw, vh)
        var previous = zoomed
        for (ms in 0..160 step 4) {
            val pose = viewerDoubleTapFrame(zoomed, to, 700f, 1_000f, vw, vh, vw, vh, eased(ms))
            assertTrue(pose.scale <= previous.scale + .001f)
            assertLegal(pose, vw, vh)
            previous = pose
        }
        assertEquals(to, previous)
    }

    @Test fun cancellingMidAnimationHandsOverWithoutSnapBack() {
        val from = ViewerZoomPosition()
        // Letterboxed landscape photo: the vertical axis cannot pan until it fills.
        val (sw, sh) = 4_000 to 3_000
        val to = viewerDoubleTapZoom(from, 200f, 900f, vw, vh, sw, sh)
        for (ms in listOf(40, 80, 120)) {
            val pose = viewerDoubleTapFrame(from, to, 200f, 900f, vw, vh, sw, sh, eased(ms))
            assertLegal(pose, sw, sh)
            // A drag that follows continues from this exact pose.
            val dragged = viewerZoomGesture(pose, 1f, -10f, 0f, 200f, 900f, vw, vh, sw, sh)
            assertEquals(pose.scale, dragged.scale, .001f)
            assertTrue(abs(dragged.translationX - pose.translationX) <= 10.01f)
            assertEquals(pose.translationY, dragged.translationY, .01f)
        }
    }

    @Test fun doubleTapFrameFailsClosedOnUnknownSizesAndBadInput() {
        val from = ViewerZoomPosition()
        val to = viewerDoubleTapZoom(from, 700f, 1_000f, vw, vh, 0, 0)
        val mid = viewerDoubleTapFrame(from, to, 700f, 1_000f, vw, vh, 0, 0, .5f)
        assertEquals(0f, mid.translationX, 0f)
        assertEquals(0f, mid.translationY, 0f)
        assertEquals(to, viewerDoubleTapFrame(from, to, 700f, 1_000f, vw, vh, vw, vh, Float.NaN))
        assertEquals(to, viewerDoubleTapFrame(from, to, 700f, 1_000f, 0, 0, vw, vh, .5f))
        val nanTap = viewerDoubleTapFrame(from, to, Float.NaN, Float.NaN, vw, vh, vw, vh, .5f)
        assertTrue(nanTap.translationX.isFinite() && nanTap.translationY.isFinite())
    }

    @Test fun filmstripTapEasingIsMonotoneBoundedAndConstantTimeForLargeAlbums() {
        assertEquals(260, AtlasMotion.FILMSTRIP_TAP_MS)
        val checkpoints = listOf(0, 16, 65, 130, 195, 260)
        val frames = checkpoints.map { elapsed ->
            viewerTapStripFrame(ViewerStripPosition(4, 0), 7,
                AtlasMotion.FilmstripTapEase.transform(elapsed / 260f), 150_000, 60)
        }
        assertEquals(ViewerStripPosition(4, 0), frames.first())
        assertEquals(ViewerStripPosition(7, 0), frames.last())
        val coordinates = frames.map { it.index.toLong() * 60 + it.scrollOffsetPx }
        assertTrue(coordinates.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(coordinates.drop(1).dropLast(1).any { it > 240 && it < 420 })

        val far = viewerTapStripFrame(ViewerStripPosition(0, 0), 149_999,
            AtlasMotion.FilmstripTapEase.transform(.5f), 150_000, 60)
        assertTrue(far.index in 1 until 149_999)
        assertEquals(ViewerStripPosition(149_999, 0),
            viewerTapStripFrame(ViewerStripPosition(0, 0), 149_999, 1f, 150_000, 60))
        assertEquals(ViewerStripPosition(0, 0),
            viewerTapStripFrame(ViewerStripPosition(149_999, 0), 0, 1f, 150_000, 60))
    }

    @Test fun newTapStartsAtRenderedSubTileCoordinateAndBoundsStayLegal() {
        val interrupted = ViewerStripPosition(16, 23)
        assertEquals(interrupted, viewerTapStripFrame(interrupted, 2, 0f, 20, 60))
        val next = (0..10).map {
            viewerTapStripFrame(interrupted, 2, it / 10f, 20, 60)
        }
        val positions = next.map { it.index * 60 + it.scrollOffsetPx }
        assertTrue(positions.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(ViewerStripPosition(2, 0), next.last())
        assertEquals(ViewerStripPosition(0, 0),
            viewerTapStripFrame(ViewerStripPosition(0, 0), 0, .5f, 1, 60))
        assertEquals(ViewerStripPosition(1, 0),
            viewerTapStripFrame(ViewerStripPosition(1, 0), 99, 1f, 2, 60))
    }
}
