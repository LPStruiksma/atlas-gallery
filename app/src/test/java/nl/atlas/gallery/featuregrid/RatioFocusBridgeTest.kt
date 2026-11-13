package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class RatioFocusBridgeTest {
    private val options = RatioLayoutOptions(
        viewportWidthPx = 420, viewportHeightPx = 800,
        horizontalInsetPx = 3, gapPx = 3,
        targetRowHeightPx = 140, maxRowHeightPx = 280
    )

    @Test fun fingerOnSecondCellSelectsSecondNotFirstOrRowHeader() {
        val source = listOf(RatioMedia("a", 1f), RatioMedia("b", 1f),
            RatioMedia("c", 1f), RatioMedia("d", 1f))
        val layout = planRatioRows(source, options)
        val row = layout.rows.first()
        assertTrue(row.mediaCount >= 2)
        val x = options.horizontalInsetPx + row.cellWidthsPx[0] +
            options.gapPx + row.cellWidthsPx[1] / 2f
        val anchor = focalRatioAnchor(layout, listOf(RatioVisibleRow(0, 10)),
            x, 10 + row.heightPx / 2f) { source.getOrNull(it)?.key }
        assertEquals("b", anchor?.mediaKey)
        assertEquals(1, anchor?.itemIndex)
        assertEquals(.5f, anchor!!.fractionY, 0.02f)
    }

    @Test fun outsideRowsOrInvalidCoordinatesDoNotFabricateSource() {
        val layout = planRatioRows(listOf(RatioMedia("a", 1f)), options)
        assertNull(focalRatioAnchor(layout, emptyList(), 100f, 40f) { "a" })
        assertNull(focalRatioAnchor(layout, listOf(RatioVisibleRow(999, 0)),
            20f, 30f) { "a" })
        assertNull(focalRatioAnchor(layout, listOf(RatioVisibleRow(0, 0)),
            Float.NaN, 20f) { "a" })
    }

    @Test fun revokedMediaKeysCannotBeCarriedIntoNewLayout() {
        val layout = planRatioRows(listOf(RatioMedia("a", 1f)), options)
        assertNull(focalRatioAnchor(layout, listOf(RatioVisibleRow(0, 0)),
            80f, 80f) { null })
    }

    @Test fun visibleRowsOnlyAndOneLookupPerVisibleCellAt150k() {
        val source = List(150_000) { i ->
            RatioMedia("synthetic:${i}", if (i % 3 == 0) .67f else 1.5f)
        }
        val layout = planRatioRows(source, options)
        val selectedIndex = 111_111
        val rowIndex = layout.rowIndexOf(selectedIndex)
        val row = layout.rows[rowIndex]
        var calls = 0
        val anchor = focalRatioAnchor(
            layout, listOf(RatioVisibleRow(rowIndex, 50)), 10f, 60f
        ) { i -> calls++; source.getOrNull(i)?.key }
        assertNotNull(anchor)
        assertTrue(calls <= row.mediaCount)
        assertTrue(calls <= options.maxItemsPerRow)
        assertTrue(anchor!!.itemIndex in row.firstMediaIndex until
            row.firstMediaIndex + row.mediaCount)
    }
}
