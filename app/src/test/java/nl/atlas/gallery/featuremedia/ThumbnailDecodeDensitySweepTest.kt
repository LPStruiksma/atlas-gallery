package nl.atlas.gallery.featuremedia

import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.GridViewportGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** DAILY-11: viewport-to-decode integration; synthetic JVM-only geometry. */
class ThumbnailDecodeDensitySweepTest {
    private val columns = listOf(3, 4, 7, 12, 28)

    private fun request(viewport: GridViewportGeometry, count: Int): Int {
        val side = viewport.cellSidePx(count)
        return thumbnailDecodePixels(side, side)
    }

    @Test fun phoneWidthsAndDensityTransitionsPreserveBoundsAndMonotonicity() {
        val widths = listOf(320, 360, 393, 412, 420, 720, 1080, 1440, 2160)
        var cases = 0
        for (width in widths) for (gap in listOf(0, 1, 3, 9, 24)) {
            val viewport = GridViewportGeometry(width, 800, gap, gap, 250_000)
            var priorSide = Int.MAX_VALUE
            var priorDecode = Int.MAX_VALUE
            for (density in GridDensity.entries) {
                val count = density.columns
                assertTrue("unexpected density columns=$count", count in columns)
                val side = viewport.cellSidePx(count)
                val decode = request(viewport, count)
                val context = "width=$width inset=$gap gap=$gap columns=$count side=$side decode=$decode"
                assertTrue("$context: side must be positive", side > 0)
                assertTrue("$context: decode outside 96..900", decode in 96..900)
                assertTrue("$context: increasing columns enlarged cell", side <= priorSide)
                assertTrue("$context: increasing columns enlarged decode", decode <= priorDecode)
                assertTrue("$context: uncapped bucket is not multiple of 32",
                    decode == 900 || decode % 32 == 0)
                priorSide = side
                priorDecode = decode
                cases++
            }
        }
        assertEquals("bounded sweep case count", 225, cases)
    }

    @Test fun geometryRemaindersAndExtremeValidInputsMatchIndependentBucketOracle() {
        val widths = listOf(1, 2, 31, 95, 319, 386, 393, 427, 599, 600, 601,
            1080, 2160, Int.MAX_VALUE)
        val insets = listOf(0, 1, 7, 256, Int.MAX_VALUE)
        val gaps = listOf(0, 1, 3, 31, Int.MAX_VALUE)
        for (width in widths) for (inset in insets) for (gap in gaps) {
            val viewport = GridViewportGeometry(width, 1, inset, gap, 0)
            for (count in columns) {
                val available = width.toLong() - 2L * inset - (count - 1L) * gap
                val expectedSide = if (available <= 0L) 1 else
                    ((available + count - 1L) / count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val side = viewport.cellSidePx(count)
                val actual = request(viewport, count)
                val desired = ceil(expectedSide.toDouble() * 1.5)
                val expectedDecode = if (desired >= 900.0) 900 else
                    (ceil(desired / 32.0).toInt() * 32).coerceIn(96, 900)
                val context = "width=$width inset=$inset gap=$gap columns=$count side=$side"
                assertEquals("$context: rounded cell side", expectedSide, side)
                assertEquals("$context: decode bucket", expectedDecode, actual)
                assertTrue("$context: decode=$actual out of bounds", actual in 96..900)
            }
        }
    }

    @Test fun everyOrdinaryCellBucketTransitionRetainsOnePointFiveOversampling() {
        // Sweep actual viewport-derived cell sides, not direct calls with arbitrary cell sizes.
        for (count in columns) for (side in 1..620) {
            val viewport = GridViewportGeometry(side * count, 800, 0, 0, 100)
            assertEquals("columns=$count targetSide=$side", side, viewport.cellSidePx(count))
            val decode = request(viewport, count)
            val desired = ceil(side * 1.5)
            assertTrue("columns=$count side=$side decode=$decode desired=$desired: undersampled",
                decode >= desired || decode == 900)
            if (decode < 900) {
                assertEquals("columns=$count side=$side: bucket alignment", 0, decode % 32)
                if (decode > 96) {
                    assertTrue("columns=$count side=$side: skipped bucket",
                        decode - desired < 32)
                }
            }
        }
        for (side in listOf(64, 65, 85, 86, 597, 598, 599, 600)) {
            val viewport = GridViewportGeometry(side * 4, 800, 0, 0, 0)
            val expected = when (side) {
                64 -> 96
                65, 85 -> 128
                86 -> 160
                597 -> 896
                else -> 900
            }
            assertEquals("4-column transition side=$side", expected, request(viewport, 4))
        }
    }

    @Test fun invalidCellDimensionsKeepLegacyFallback() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0)) for (other in listOf(1, 400, Int.MAX_VALUE)) {
            assertEquals("invalid width=$invalid height=$other", 400,
                thumbnailDecodePixels(invalid, other))
            assertEquals("width=$other invalid height=$invalid", 400,
                thumbnailDecodePixels(other, invalid))
        }
    }
}
