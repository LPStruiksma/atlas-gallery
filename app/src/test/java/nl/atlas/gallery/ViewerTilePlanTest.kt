package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** No image bytes or Android decoder. Real subsampling remains unverified. */
class ViewerTilePlanTest {
    @Test fun largeOriginalsHaveStrictlyBoundedVisibleWork() {
        for ((w, h) in listOf(10_000 to 5_000, 20_000 to 10_000, 25_000 to 8_000)) {
            for (zoom in listOf(1f, 2.5f, 5f)) {
                val plan = requireNotNull(planViewerTiles(w, h, 1_440, 3_120, zoom, 0f, 0f))
                assertTrue(plan.tiles.size <= 12)
                assertTrue(plan.maxDecodedBytes <= 16L * 1024 * 1024)
                assertTrue(plan.sampleSize > 0 &&
                    plan.sampleSize and (plan.sampleSize - 1) == 0)
                assertCovered(plan)
            }
        }
    }

    @Test fun pannedPanoramasAndPortraitRotationStayWithinOrientedSource() {
        for ((w, h) in listOf(250_000 to 600, 600 to 250_000,
            2_000 to 12_000, 12_000 to 2_000)) {
            val plan = requireNotNull(planViewerTiles(w, h,
                1_440, 3_120, 3f, 200f, -200f))
            assertCovered(plan)
            assertTrue(plan.tiles.all { it.right <= w && it.bottom <= h })
        }
    }

    @Test fun rejectsUnboundedCorruptAndUnsatisfiableMetadata() {
        assertNull(planViewerTiles(0, 1, 1_440, 3_120, 2f, 0f, 0f))
        assertNull(planViewerTiles(50_000, 50_000, 1_440, 3_120, 2f, 0f, 0f))
        assertNull(planViewerTiles(20_000, 10_000, 1_440, 3_120,
            Float.NaN, 0f, 0f))
        assertNull(planViewerTiles(20_000, 10_000, 1_440, 3_120,
            2f, Float.POSITIVE_INFINITY, 0f))
        assertNull(planViewerTiles(20_000, 10_000, 1_440, 3_120,
            2f, 0f, 0f, tilePixels = 16))
        assertNull(planViewerTiles(20_000, 10_000, 1_440, 3_120,
            2f, 0f, 0f, maxTiles = 0))
    }

    @Test fun seededSyntheticSweepAlwaysRespectsVisibleCoverageAndBudget() {
        val rng = Random(20261022)
        repeat(10_000) {
            val w = rng.nextInt(100, 32_001)
            val h = rng.nextInt(100, 32_001)
            val viewW = rng.nextInt(320, 3_121)
            val viewH = rng.nextInt(320, 3_121)
            val zoom = listOf(1f, 1.4f, 2.5f, 4.5f)[rng.nextInt(4)]
            val plan = planViewerTiles(w, h, viewW, viewH, zoom,
                rng.nextInt(-500, 501).toFloat(), rng.nextInt(-500, 501).toFloat())
                ?: return@repeat  // Panning completely outside source fails closed.
            assertTrue(plan.tiles.size in 1..12)
            assertTrue(plan.maxDecodedBytes <= 16L * 1024 * 1024)
            assertCovered(plan)
        }
    }

    private fun assertCovered(plan: ViewerTilePlan) {
        val visible = plan.visibleSource
        for (x in listOf(visible.left, visible.right - 1))
            for (y in listOf(visible.top, visible.bottom - 1))
                assertTrue("Visible source corner $x,$y not covered",
                    plan.tiles.any { x in it.left until it.right &&
                        y in it.top until it.bottom })
    }
}
