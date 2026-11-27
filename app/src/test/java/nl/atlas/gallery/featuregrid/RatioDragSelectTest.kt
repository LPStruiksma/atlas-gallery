package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Long-press drag on original-proportion rows finds the photo under the finger. */
class RatioDragSelectTest {
    // Two rows, 2 px gaps: [0: 100 | 1: 200] 150 tall, then [2: 150 | 3: 50 | 4: 96] 120 tall.
    private val options = RatioLayoutOptions(viewportWidthPx = 302, viewportHeightPx = 600,
        horizontalInsetPx = 0, gapPx = 2, targetRowHeightPx = 100, maxRowHeightPx = 200)
    private val layout = RatioLayout(listOf(
        RatioRow(0, 2, 0, 150, listOf(100, 200), true),
        RatioRow(2, 3, 152, 120, listOf(150, 50, 96), true)
    ), options, mediaCount = 5, contentHeightPx = 272)
    private val visible = listOf(0 to 0, 1 to 152)

    @Test fun findsThePhotoUnderTheFinger() {
        assertEquals(0, ratioHitMedia(layout, visible, 0, 50f, 75f))
        assertEquals(1, ratioHitMedia(layout, visible, 0, 250f, 10f))
        assertEquals(2, ratioHitMedia(layout, visible, 0, 10f, 200f))
        assertEquals(3, ratioHitMedia(layout, visible, 0, 160f, 200f))
        assertEquals(4, ratioHitMedia(layout, visible, 0, 301f, 271f))
    }

    @Test fun seamsGoToTheNearerPhotoAndRowGapsKeepTheRowAbove() {
        assertEquals(0, ratioHitMedia(layout, visible, 0, 100.5f, 75f))
        assertEquals(1, ratioHitMedia(layout, visible, 0, 101.5f, 75f))
        assertEquals(1, ratioHitMedia(layout, visible, 0, 250f, 151f))
        assertEquals(4, ratioHitMedia(layout, visible, 0, 900f, 200f))
    }

    @Test fun scrolledRowsUseTheirLiveOffsets() {
        // Scrolled 100 px: row 0 starts at -100, row 1 at 52.
        val scrolled = listOf(0 to -100, 1 to 52)
        assertEquals(1, ratioHitMedia(layout, scrolled, 0, 250f, 20f))
        assertEquals(3, ratioHitMedia(layout, scrolled, 0, 160f, 60f))
    }

    @Test fun emptySpaceBelowTheLastRowHitsNothing() {
        assertNull(ratioHitMedia(layout, visible, 0, 50f, 400f))
        assertNull(ratioHitMedia(layout, emptyList(), 0, 50f, 75f))
    }
}
