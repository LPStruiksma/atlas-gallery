package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

/** Synthetic focal selection bounds: input catalogue size must not affect lookup work. */
class RatioFocusBridgeScaleTest {
    private val options = RatioLayoutOptions(
        viewportWidthPx = 420, viewportHeightPx = 800,
        horizontalInsetPx = 3, gapPx = 3,
        targetRowHeightPx = 140, maxRowHeightPx = 280
    )

    private fun layout(count: Int): RatioLayout =
        planRatioRows(List(count) { i -> RatioMedia("synthetic:$i", 1f) }, options)

    @Test fun emptyAndSingleSourceNeverInspectOffscreenMedia() {
        val empty = layout(0)
        var calls = 0
        assertNull(focalRatioAnchor(empty, listOf(RatioVisibleRow(0, 0)), 10f, 10f) {
            calls++
            "unexpected"
        })
        assertEquals(0, calls)

        val one = layout(1)
        assertEquals("synthetic:0", focalRatioAnchor(
            one, listOf(RatioVisibleRow(0, 20)), 100f, 40f
        ) { i -> calls++; "synthetic:$i" }?.mediaKey)
        assertEquals(1, calls)
    }

    @Test fun invalidRowsAndNonfiniteFocusNeverReadCatalogue() {
        val plan = layout(12)
        val invalid = listOf(RatioVisibleRow(-1, 0), RatioVisibleRow(Int.MAX_VALUE, 0))
        var calls = 0
        fun select(rows: List<RatioVisibleRow>, x: Float, y: Float): GridFocalAnchor? =
            focalRatioAnchor(plan, rows, x, y) { calls++; "synthetic:$it" }
        assertNull(select(invalid, 10f, 10f))
        assertNull(select(listOf(RatioVisibleRow(0, 0)), Float.NaN, 10f))
        assertNull(select(listOf(RatioVisibleRow(0, 0)), Float.POSITIVE_INFINITY, 10f))
        assertNull(select(listOf(RatioVisibleRow(0, 0)), 10f, Float.NEGATIVE_INFINITY))
        assertEquals(0, calls)
    }

    @Test fun nullBlankAndDegenerateFocusReturnOnlyEligibleMedia() {
        val plan = layout(3)
        val row = plan.rows[0]
        val visible = listOf(RatioVisibleRow(0, 15))
        var calls = 0
        val none = focalRatioAnchor(plan, visible, 0f, 0f) {
            calls++
            if (it == 0) null else " "
        }
        assertNull(none)
        assertEquals(row.mediaCount, calls)
        calls = 0
        val candidate = focalRatioAnchor(plan, visible, -Float.MAX_VALUE, -Float.MAX_VALUE) {
            calls++
            if (it == 0) null else "synthetic:$it"
        }
        assertNotNull(candidate)
        assertEquals(1, candidate!!.itemIndex)
        assertEquals(row.mediaCount, calls)
        assertEquals(0f, candidate.fractionX, 0f)
        assertEquals(0f, candidate.fractionY, 0f)
    }

    @Test fun quarterMillionCatalogueInspectsOnlyVisibleCellsWithManyRows() {
        val plan = layout(250_000)
        val midpoint = plan.rowIndexOf(125_000)
        val rowIndices = (midpoint - 12..midpoint + 12).filter { it in plan.rows.indices }
        val rows = rowIndices.mapIndexed { n, index -> RatioVisibleRow(index, n * 150) }
        val expectedIndices = rowIndices.flatMap { index ->
            val row = plan.rows[index]
            (row.firstMediaIndex until row.firstMediaIndex + row.mediaCount).toList()
        }
        val observed = mutableListOf<Int>()
        val focusRow = plan.rows[midpoint]
        val focusTop = rows[rowIndices.indexOf(midpoint)].topPx
        val x = options.horizontalInsetPx + focusRow.cellWidthsPx[0] / 2f
        val focusY = focusTop + focusRow.heightPx / 2f
        val anchor = focalRatioAnchor(plan, rows, x, focusY) { index ->
            observed.add(index)
            "synthetic:$index"
        }
        assertEquals(expectedIndices, observed)
        assertEquals(expectedIndices.size, observed.size)
        assertEquals(focusRow.firstMediaIndex, anchor?.itemIndex)
        assertEquals("synthetic:${focusRow.firstMediaIndex}", anchor?.mediaKey)
        assertEquals(.5f, anchor!!.fractionY, .02f)
        assertTrue(observed.size <= rows.size * options.maxItemsPerRow)
        assertTrue(observed.size < 250_000)
    }

    @Test fun overlappingEdgeCellsChooseFirstVisibleCandidateDeterministically() {
        val plan = layout(4)
        val row = plan.rows[0]
        assertTrue(row.mediaCount >= 2)
        val edgeX = options.horizontalInsetPx + row.cellWidthsPx[0] +
            options.gapPx / 2f
        val visible = listOf(RatioVisibleRow(0, 0))
        repeat(5) {
            val selected = focalRatioAnchor(plan, visible, edgeX, row.heightPx / 2f) {
                "synthetic:$it"
            }
            assertEquals("synthetic:0", selected?.mediaKey)
            assertEquals(0, selected?.itemIndex)
        }
        val secondCenter = options.horizontalInsetPx + row.cellWidthsPx[0] +
            options.gapPx + row.cellWidthsPx[1] / 2f
        val selected = focalRatioAnchor(plan, visible, secondCenter, row.heightPx / 2f) {
            "synthetic:$it"
        }
        assertEquals(1, selected?.itemIndex)
    }
}
