package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #556: the drag finds the tile under the finger; gaps and headers keep the last tile. */
class GridDragSelectTest {
    // Header row (slot 0) spanning the width, then two rows of three 100 px tiles with 6 px gaps.
    private val cells = listOf(
        DragHitCell(0, 0, 0, 318, 40),
        DragHitCell(1, 6, 46, 100, 100), DragHitCell(2, 112, 46, 100, 100), DragHitCell(3, 218, 46, 100, 100),
        DragHitCell(4, 6, 152, 100, 100), DragHitCell(5, 112, 152, 100, 100), DragHitCell(6, 218, 152, 100, 100)
    )

    @Test fun findsTheTileUnderTheFinger() {
        assertEquals(1, dragHitSlot(cells, 50f, 90f))
        assertEquals(5, dragHitSlot(cells, 150f, 200f))
        assertEquals(6, dragHitSlot(cells, 317f, 251f))
    }

    @Test fun horizontalGapOrPaddingPicksTheNearestTileInThatRow() {
        assertEquals(1, dragHitSlot(cells, 2f, 90f))
        assertEquals(2, dragHitSlot(cells, 110f, 90f))
        assertEquals(3, dragHitSlot(cells, 400f, 90f))
    }

    @Test fun verticalGapAndEmptySpaceHitNothing() {
        assertNull(dragHitSlot(cells, 50f, 149f))
        assertNull(dragHitSlot(cells, 50f, 900f))
        assertNull(dragHitSlot(emptyList(), 50f, 90f))
    }

    @Test fun aHeaderRowIsReportedAsItsOwnSlot() {
        assertEquals(0, dragHitSlot(cells, 150f, 20f))
    }
}
