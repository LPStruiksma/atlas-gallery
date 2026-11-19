package nl.atlas.gallery.featuregrid

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** PERF-20: lock the direct visible-item scan to the old allocated projection's decisions. */
class GridDragHitPerfTest {
    private data class Cell(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    private fun projected(cells: List<Cell>, viewportStartOffset: Int, x: Float, y: Float): Int? =
        dragHitSlot(cells.map { DragHitCell(it.index, it.x, it.y - viewportStartOffset, it.w, it.h) }, x, y)

    private fun direct(cells: List<Cell>, viewportStartOffset: Int, x: Float, y: Float): Int? =
        dragHitVisibleItems(cells, viewportStartOffset, x, y,
            index = { it.index }, offset = { IntOffset(it.x, it.y) },
            size = { IntSize(it.w, it.h) })

    @Test fun gapsHeaderRightPaddingAndViewportShiftMatchProjection() {
        val cells = listOf(Cell(0, 0, 0, 318, 40),
            Cell(1, 6, 46, 100, 100), Cell(2, 112, 46, 100, 100),
            Cell(3, 218, 46, 100, 100), Cell(4, 6, 152, 100, 100))
        val points = listOf(-50f to -1f, 150f to 20f, 2f to 90f,
            110f to 90f, 400f to 90f, 50f to 149f, 300f to 250f)
        for (shift in listOf(0, -20, 30)) for ((x, y) in points)
            assertEquals("shift=$shift x=$x y=$y", projected(cells, shift, x, y),
                direct(cells, shift, x, y))
    }

    @Test fun changingLayoutAndNonContiguousSlotsNeverReuseStaleGeometry() {
        val first = listOf(Cell(8, 10, 10, 100, 100))
        val second = listOf(Cell(30, 210, 10, 100, 100))
        assertEquals(8, direct(first, 0, 40f, 30f))
        assertEquals(30, direct(second, 0, 40f, 30f))
        assertEquals(null, direct(second, 0, 40f, 200f))
        assertEquals(30, direct(second, -40, 40f, 70f))
    }

    @Test fun syntheticTenThousandEventsMatchAllocationBasedOracle() {
        val random = Random(2020)
        var tested = 0
        repeat(10_000) {
            val shift = random.nextInt(-64, 64)
            val cells = List(random.nextInt(0, 20)) { n ->
                Cell(n * 3 + 10, (n % 4) * 108 + 6, (n / 4) * 108,
                    100, 100)
            }
            val x = random.nextInt(-40, 520).toFloat()
            val y = random.nextInt(-100, 700).toFloat()
            assertEquals("event=$it", projected(cells, shift, x, y),
                direct(cells, shift, x, y))
            tested++
        }
        assertEquals(10_000, tested)
        // Oracle constructs one mapped list and one DragHitCell per visible item
        // per event. The direct scan constructs neither.
    }
}
