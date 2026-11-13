package nl.atlas.gallery.featuregrid

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RatioNarrowLayoutTest {
    @Test fun constrainedWidthWithWideGapsDoesNotCrashOrCreateNegativeCells() {
        val options = RatioLayoutOptions(94, 300, horizontalInsetPx = 0,
            gapPx = 23, targetRowHeightPx = 15, maxRowHeightPx = 25, maxItemsPerRow = 15)
        val source = listOf(8f, .25f, 8f, .25f, .08f, 2f, .25f, 2f, 1f,
            1f, .25f, .08f, .08f, .25f, .5f, .25f, 8f, 8f, .08f,
            .5f, .08f, 1f, 8f, 2f, 1f, 16f, 8f, 2f, 16f)
            .mapIndexed { i, ratio -> RatioMedia("uri:$i", ratio) }
        val layout = planRatioRows(source, options)
        assertEquals(source.size, layout.rows.sumOf { it.mediaCount })
        assertTrue(layout.rows.size > 1)
        for (row in layout.rows) {
            assertTrue(row.cellWidthsPx.all { it > 0 })
            assertTrue(row.cellWidthsPx.sum() + (row.mediaCount - 1) * options.gapPx <=
                options.contentWidthPx)
        }
    }

    @Test fun seededAdversarialOptionsPreserveExactCoverageAndWidth() {
        val random = Random(82971)
        val ratios = listOf(.08f, .25f, .5f, 1f, 2f, 8f, 16f)
        repeat(2_000) { run ->
            val gap = random.nextInt(0, 65)
            val width = random.nextInt(gap + 1, 400)
            val target = random.nextInt(1, 430)
            val options = RatioLayoutOptions(width, 400, 0, gap, target,
                target + random.nextInt(0, 200), random.nextInt(1, 17))
            val items = List(random.nextInt(1, 40)) { i ->
                RatioMedia("$run:$i", ratios[random.nextInt(ratios.size)])
            }
            val layout = planRatioRows(items, options)
            assertEquals(items.size, layout.rows.sumOf { it.mediaCount })
            for (row in layout.rows) {
                assertTrue(row.cellWidthsPx.all { it > 0 })
                assertTrue(row.cellWidthsPx.sum() + options.gapPx *
                    (row.mediaCount - 1) <= options.contentWidthPx)
            }
        }
    }
}
