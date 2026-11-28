package nl.atlas.gallery.featureaiedit.ui

import nl.atlas.gallery.featureaiedit.preview.CompareImageSize
import nl.atlas.gallery.featureaiedit.preview.CompareNormalizedPoint
import nl.atlas.gallery.featureaiedit.preview.CompareViewportPlanner
import nl.atlas.gallery.featureaiedit.preview.CompareViewportPoint
import nl.atlas.gallery.featureaiedit.preview.CompareViewportSize
import nl.atlas.gallery.featureaiedit.ui.AiEditCompareMath.IntRect
import nl.atlas.gallery.featureaiedit.ui.AiEditCompareMath.Orientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditCompareMathTest {
    @Test fun splitClampsAndStepsThroughTalkBackStops() {
        assertEquals(0f, AiEditCompareMath.clampSplit(-1f))
        assertEquals(1f, AiEditCompareMath.clampSplit(2f))
        assertEquals(.5f, AiEditCompareMath.clampSplit(Float.NaN))
        assertEquals(.75f, AiEditCompareMath.stepSplit(.5f, up = true))
        assertEquals(.25f, AiEditCompareMath.stepSplit(.5f, up = false))
        assertEquals(.5f, AiEditCompareMath.stepSplit(.3f, up = true))
        assertEquals(1f, AiEditCompareMath.stepSplit(1f, up = true))
        assertEquals(0f, AiEditCompareMath.stepSplit(0f, up = false))
        assertEquals("25 percent original, 75 percent edited", AiEditCompareMath.splitDescription(.25f))
    }

    @Test fun centreDetentTicksOncePerCrossingNotOnOscillation() {
        val d = AiEditCompareMath.CentreDetent(.5f)
        // Starting at the centre is not armed: tiny wiggles never tick.
        assertFalse(d.update(.51f)); assertFalse(d.update(.49f)); assertFalse(d.update(.505f))
        // Move clearly away, then cross: one tick.
        assertFalse(d.update(.3f))
        assertTrue(d.update(.51f))
        // Oscillating around the centre right after does not repeat.
        assertFalse(d.update(.49f)); assertFalse(d.update(.52f)); assertFalse(d.update(.48f))
        // Away again and back across: a second tick.
        assertFalse(d.update(.2f))
        assertTrue(d.update(.5f))
    }

    @Test fun zoomIsClampedAndDoubleTapToggles() {
        assertEquals(1.0, AiEditCompareMath.clampZoom(.2), 0.0)
        assertEquals(8.0, AiEditCompareMath.clampZoom(99.0), 0.0)
        assertEquals(1.0, AiEditCompareMath.clampZoom(1.005), 0.0)
        assertEquals(2.5, AiEditCompareMath.doubleTapZoom(1.0), 0.0)
        assertEquals(1.0, AiEditCompareMath.doubleTapZoom(3.0), 0.0)
        assertEquals(1.5, AiEditCompareMath.zoomIn(1.0), 1e-9)
        assertEquals(1.0, AiEditCompareMath.zoomOut(1.2), 0.0)
    }

    @Test fun zoomAroundKeepsTheAnchoredPixelUnderTheFinger() {
        val imgW = 4000; val imgH = 3000; val vw = 1080.0; val vh = 1500.0
        val start = AiEditCompareMath.View(.5, .5, 1.0)
        val anchorX = 700.0; val anchorY = 800.0
        val zoomed = AiEditCompareMath.zoomAround(start, 3.0, anchorX, anchorY, imgW, imgH, vw, vh)
        val before = CompareViewportPlanner.plan(CompareImageSize(imgW, imgH), CompareImageSize(imgW, imgH),
            CompareViewportSize(1080, 1500), CompareNormalizedPoint(.5, .5), 1.0).candidate
        val after = CompareViewportPlanner.plan(CompareImageSize(imgW, imgH), CompareImageSize(imgW, imgH),
            CompareViewportSize(1080, 1500), CompareNormalizedPoint(zoomed.focusX, zoomed.focusY), 3.0).candidate
        val p = assertNotNullAndGet(before.viewportToSourceOrNull(CompareViewportPoint(anchorX, anchorY)))
        val back = after.sourceToViewport(p)
        assertEquals(anchorX, back.x, 1.0)
        assertEquals(anchorY, back.y, 1.0)
    }

    @Test fun panFollowsTheFingerAndStopsAtEdges() {
        val imgW = 1000; val imgH = 1000; val vw = 1000.0; val vh = 1000.0
        val v = AiEditCompareMath.View(.5, .5, 2.0)
        val moved = AiEditCompareMath.pan(v, -100.0, 0.0, imgW, imgH, vw, vh)
        assertTrue("dragging left reveals the right side", moved.focusX > .5)
        assertEquals(.55, moved.focusX, 1e-9)
        val far = AiEditCompareMath.pan(v, -1e6, 1e6, imgW, imgH, vw, vh)
        // Clamped to the range that still moves the picture: half the pane in image units.
        assertEquals(.75, far.focusX, 1e-9)
        assertEquals(.25, far.focusY, 1e-9)
        // At fit, a side that fits stays centred.
        val fit = AiEditCompareMath.pan(AiEditCompareMath.View(.5, .5, 1.0), 300.0, 300.0, imgW, imgH, vw, vh)
        assertEquals(.5, fit.focusX, 0.0)
    }

    @Test fun mismatchedAspectFramesShareTheNormalizedFocus() {
        val original = CompareImageSize(4000, 3000)
        val candidate = CompareImageSize(1024, 1024)
        val plan = CompareViewportPlanner.plan(original, candidate, CompareViewportSize(1080, 1500),
            CompareNormalizedPoint(.3, .6), 4.0)
        assertTrue(plan.totalDecodeBytes <= CompareViewportPlanner.DEFAULT_COMBINED_PREVIEW_BYTES)
        val point = CompareNormalizedPoint(.3, .6)
        val a = plan.original.sourceToViewport(point)
        val b = plan.candidate.sourceToViewport(point)
        assertEquals(a.x, b.x, 1.0)
    }

    @Test fun uprightCropMapsToStoredGridForEveryOrientation() {
        // Stored 400x300; a crop in upright space must cover the same pixels after orienting.
        val rawW = 400; val rawH = 300
        val r = IntRect(10, 20, 110, 70)
        assertEquals(r, AiEditCompareMath.uprightToRaw(r, Orientation.NORMAL, rawW, rawH))
        assertEquals(IntRect(290, 20, 390, 70), AiEditCompareMath.uprightToRaw(r, Orientation.FLIP_HORIZONTAL, rawW, rawH))
        assertEquals(IntRect(290, 230, 390, 280), AiEditCompareMath.uprightToRaw(r, Orientation.ROTATE_180, rawW, rawH))
        assertEquals(IntRect(10, 230, 110, 280), AiEditCompareMath.uprightToRaw(r, Orientation.FLIP_VERTICAL, rawW, rawH))
        // Upright is 300x400 for the swapping ones.
        assertEquals(IntRect(20, 10, 70, 110), AiEditCompareMath.uprightToRaw(r, Orientation.TRANSPOSE, rawW, rawH))
        assertEquals(IntRect(20, 190, 70, 290), AiEditCompareMath.uprightToRaw(r, Orientation.ROTATE_90, rawW, rawH))
        assertEquals(IntRect(330, 190, 380, 290), AiEditCompareMath.uprightToRaw(r, Orientation.TRANSVERSE, rawW, rawH))
        assertEquals(IntRect(330, 10, 380, 110), AiEditCompareMath.uprightToRaw(r, Orientation.ROTATE_270, rawW, rawH))
    }

    @Test fun rotate90MappingMatchesAPixelByPixelRotation() {
        // Brute-force check: rotate a stored grid 90° clockwise and compare covered pixel sets.
        val rawW = 7; val rawH = 5
        // Upright is rawH x rawW = 5 x 7. Upright (ux, uy) comes from raw (uy, rawH - 1 - ux).
        val r = IntRect(1, 2, 4, 6)
        val expected = HashSet<Pair<Int, Int>>()
        for (ux in r.left until r.right) for (uy in r.top until r.bottom) expected += uy to (rawH - 1 - ux)
        val m = AiEditCompareMath.uprightToRaw(r, Orientation.ROTATE_90, rawW, rawH)
        val got = HashSet<Pair<Int, Int>>()
        for (x in m.left until m.right) for (y in m.top until m.bottom) got += x to y
        assertEquals(expected, got)
    }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
