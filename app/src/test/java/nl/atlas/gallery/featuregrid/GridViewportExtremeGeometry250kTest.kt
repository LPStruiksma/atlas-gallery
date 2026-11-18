package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Memoryless 250k-item zoom geometry checks: no media list or per-item allocations.
 * Independent Long/Double oracle deliberately does not call the production stride math.
 */
class GridViewportExtremeGeometry250kTest {
    private val count = 250_000
    private val columns = listOf(3, 4, 7, 12, 28)

    @Test fun extremeViewportMatrixKeepsTargetsOnValidRowsAndPixels() {
        var cases = 0
        for (width in listOf(1, 420, 1440, 4096)) {
            for (inset in listOf(0, 80, 2000)) {
                for (gap in listOf(0, 9, 4096)) {
                    val viewport = GridViewportGeometry(width, 880, inset, gap, count)
                    for (cols in columns) {
                        val usable = width.toLong() - 2L * inset - (cols - 1L) * gap
                        val expectedCell = if (usable <= 0L) 1L
                            else (usable + cols - 1L) / cols
                        assertEquals("cell width=$width inset=$inset gap=$gap cols=$cols",
                            expectedCell, viewport.cellSidePx(cols).toLong())
                        val stride = expectedCell + gap
                        val rows = (count.toLong() + cols - 1L) / cols
                        val content = rows * expectedCell + (rows - 1L) * gap
                        val maximum = (content - viewport.heightPx).coerceAtLeast(0L)
                        for (index in listOf(0, count / 2, count - 1)) {
                            for (fraction in listOf(0f, 0.25f, 0.5f, 1f)) {
                                val anchor = GridFocalAnchor("synthetic:$index", index, 0.5f, fraction)
                                for (focus in listOf(-500f, 0f, 440f, 2000f)) {
                                    val context = "width=$width inset=$inset gap=$gap cols=$cols index=$index fraction=$fraction focus=$focus"
                                    val result = resolveGridAnchor(anchor, index, cols, viewport, focus)
                                    assertTrue("$context: index ${result.firstItemIndex}",
                                        result.firstItemIndex in 0 until count)
                                    assertEquals("$context: row alignment", 0, result.firstItemIndex % cols)
                                    assertTrue("$context: offset ${result.firstItemScrollOffsetPx} stride=$stride",
                                        result.firstItemScrollOffsetPx >= 0 &&
                                            result.firstItemScrollOffsetPx.toLong() < stride)
                                    val actual = result.firstItemIndex.toLong() / cols * stride +
                                        result.firstItemScrollOffsetPx
                                    val desired = (index / cols).toDouble() * stride +
                                        fraction.toDouble() * expectedCell - focus.toDouble()
                                    val expected = desired.coerceIn(0.0, maximum.toDouble())
                                        .roundToLong().coerceIn(0L, maximum)
                                    assertEquals("$context: absolute scroll", expected, actual)
                                    assertTrue("$context: scroll exceeds maximum $maximum", actual <= maximum)
                                    if (abs(desired - desired.coerceIn(0.0, maximum.toDouble())) > 0.501) {
                                        assertTrue("$context: missing bounds flag", result.clampedByBounds)
                                    }
                                    cases++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("deterministic bounded case count", 8640, cases)
    }

    @Test fun movingFocusDownNeverIncreasesScrollAndDoesNotJumpRows() {
        val viewport = GridViewportGeometry(1080, 2340, 28, 17, count)
        for (cols in columns) for (index in listOf(0, 123_457, count - 1)) {
            val anchor = GridFocalAnchor("synthetic:$index", index, 0.3f, 0.7f)
            val stride = viewport.cellSidePx(cols).toLong() + viewport.gapPx
            var previous = Long.MAX_VALUE
            for (focus in listOf(-1000f, 0f, 1f, 300f, 800f, 2339f, 5000f)) {
                val target = resolveGridAnchor(anchor, index, cols, viewport, focus)
                val absolute = target.firstItemIndex.toLong() / cols * stride +
                    target.firstItemScrollOffsetPx
                assertTrue("cols=$cols index=$index focus=$focus previous=$previous actual=$absolute",
                    absolute <= previous)
                previous = absolute
            }
        }
    }

    @Test fun maxIntHeightAndCatalogCountRemainMemorylessAndCoherent() {
        val viewport = GridViewportGeometry(2048, Int.MAX_VALUE, 32, 11, Int.MAX_VALUE)
        for (cols in columns) {
            val cell = viewport.cellSidePx(cols).toLong()
            val stride = cell + viewport.gapPx
            val rows = (Int.MAX_VALUE.toLong() + cols - 1) / cols
            val maxScroll = (rows * cell + (rows - 1) * viewport.gapPx -
                viewport.heightPx).coerceAtLeast(0)
            for (index in listOf(0, Int.MAX_VALUE / 2, Int.MAX_VALUE - 1)) {
                val target = resolveGridAnchor(
                    GridFocalAnchor("synthetic:$index", index, 0.5f, 0.5f),
                    index, cols, viewport, 1000f
                )
                val absolute = target.firstItemIndex.toLong() / cols * stride +
                    target.firstItemScrollOffsetPx
                assertTrue("cols=$cols index=$index row start=${target.firstItemIndex}",
                    target.firstItemIndex in 0 until Int.MAX_VALUE &&
                        target.firstItemIndex % cols == 0)
                assertTrue("cols=$cols index=$index offset=${target.firstItemScrollOffsetPx}",
                    target.firstItemScrollOffsetPx >= 0 &&
                        target.firstItemScrollOffsetPx.toLong() < stride)
                assertTrue("cols=$cols index=$index scroll=$absolute max=$maxScroll",
                    absolute in 0..maxScroll)
            }
        }
    }
}
