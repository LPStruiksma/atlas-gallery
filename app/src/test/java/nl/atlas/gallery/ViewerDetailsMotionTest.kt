package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe-up Details motion rules (GalleryViewer's one details surface). */
class ViewerDetailsMotionTest {
    // The owner's S24+ viewport: 1080 x 2340 px.
    private val width = 1080f
    private val height = 2340f
    private val panel = viewerDetailsPanelPx(height)
    private val fling = 1_100f * 2.8125f

    @Test fun surfaceEdgeStaysUnderTheFinger() {
        assertEquals(0f, viewerDetailsDragProgress(0f, 0f, panel), 0f)
        assertEquals(.5f, viewerDetailsDragProgress(0f, -panel / 2f, panel), 1e-4f)
        // Grabbed mid-flight at 0.7 and pulled down by a quarter panel.
        assertEquals(.45f, viewerDetailsDragProgress(.7f, panel / 4f, panel), 1e-4f)
        assertEquals(1f, viewerDetailsDragProgress(.9f, -panel, panel), 0f)
        assertEquals(0f, viewerDetailsDragProgress(.1f, panel, panel), 0f)
    }

    @Test fun degenerateInputsNeverJump() {
        assertEquals(.3f, viewerDetailsDragProgress(.3f, Float.NaN, panel), 0f)
        assertEquals(.3f, viewerDetailsDragProgress(.3f, -100f, 0f), 0f)
        assertEquals(0f, viewerDetailsPanelPx(0f), 0f)
        assertEquals(0f, viewerDetailsPanelPx(Float.NaN), 0f)
    }

    @Test fun releaseIsVelocityAware() {
        // Slow release: position decides.
        assertFalse(viewerDetailsSettleOpen(.3f, 0f, panel, fling))
        assertTrue(viewerDetailsSettleOpen(.6f, 0f, panel, fling))
        // A short quick swipe opens; the same distance released slowly does not.
        assertTrue(viewerDetailsSettleOpen(.2f, 2_500f, panel, fling))
        assertFalse(viewerDetailsSettleOpen(.2f, 300f, panel, fling))
        // Flicks win outright in either direction.
        assertTrue(viewerDetailsSettleOpen(.05f, fling, panel, fling))
        assertFalse(viewerDetailsSettleOpen(.95f, -fling, panel, fling))
        // A downward toss from open closes before reaching half way.
        assertFalse(viewerDetailsSettleOpen(.75f, -2_500f, panel, fling))
    }

    @Test fun shortTestDragSpringsBack() {
        // ViewerFingerFollowTest: 6 % of the height at ~1750 px/s must fall back.
        val p = viewerDetailsDragProgress(0f, -height * .06f, panel)
        assertFalse(viewerDetailsSettleOpen(p, 1_750f, panel, fling))
    }

    @Test fun fullHeightPhotoBottomRidesTheSurfaceTop() {
        for (p in listOf(0f, .25f, .5f, .75f, 1f)) {
            // Unknown aspect is treated as a full-height photo.
            val t = viewerDetailsPhotoTransform(p, width, height, panel, 0f)
            val photoBottom = height / 2f + t.translationY + t.scale * height / 2f
            val surfaceTop = height - panel * p
            assertEquals("p=$p", surfaceTop, photoBottom, .5f)
        }
    }

    @Test fun landscapePhotoGlidesWithoutShrinking() {
        // 4:3 landscape: fitted height 810 px fits above a 983 px surface.
        val t = viewerDetailsPhotoTransform(1f, width, height, panel, .75f)
        assertEquals(1f, t.scale, 0f)
        assertEquals(-panel / 2f, t.translationY, 1e-3f)
        // Its centre lands in the middle of the space above the surface.
        assertEquals((height - panel) / 2f, height / 2f + t.translationY, .5f)
    }

    @Test fun portraitPhotoShrinksOnlyToFit() {
        val t = viewerDetailsPhotoTransform(1f, width, height, panel, 1.5f)
        val fitted = width * 1.5f
        assertEquals(height - panel, fitted * t.scale, .5f)
        assertEquals(1f, viewerDetailsPhotoTransform(0f, width, height, panel, 1.5f).scale, 0f)
    }

    @Test fun rowsFadeInWhileRising() {
        assertEquals(0f, viewerDetailsContentAlpha(0f), 0f)
        assertEquals(0f, viewerDetailsContentAlpha(.2f), 0f)
        assertTrue(viewerDetailsContentAlpha(.5f) in .4f..0.6f)
        assertEquals(1f, viewerDetailsContentAlpha(.75f), 0f)
        assertEquals(1f, viewerDetailsContentAlpha(1f), 0f)
    }
}
