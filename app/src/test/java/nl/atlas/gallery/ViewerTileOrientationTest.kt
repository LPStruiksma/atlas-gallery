package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Pure Kotlin; zero image bytes, Android APIs or media access. */
class ViewerTileOrientationTest {
    @Test fun everyExifOrientationHasExactUprightDimensionsAndCorners() {
        for (tag in 1..8) {
            val o = requireNotNull(viewerOrientationFromExif(600, 400, tag))
            assertEquals(if (tag in 5..8) 400 else 600, o.uprightWidth)
            assertEquals(if (tag in 5..8) 600 else 400, o.uprightHeight)
            val rawFull = ViewerSourceRect(0, 0, 600, 400)
            val uprightFull = ViewerSourceRect(0, 0, o.uprightWidth, o.uprightHeight)
            assertEquals(uprightFull, viewerUprightRegionForRawTile(rawFull, o))
            assertEquals(rawFull, viewerRawRegionForUprightTile(uprightFull, o))
            assertEquals(rawFull, viewerRawRegionForUprightTile(
                requireNotNull(viewerUprightRegionForRawTile(rawFull, o)), o))
        }
    }

    @Test fun knownExifPointMapsCatchMirrorAndTransposeConfusion() {
        // Raw top-left 1x1 pixel in a non-square 600x400 input.
        val corner = ViewerSourceRect(0, 0, 1, 1)
        val expected = listOf(
            ViewerSourceRect(0, 0, 1, 1),       // 1 identity
            ViewerSourceRect(599, 0, 600, 1),   // 2 mirror horizontal
            ViewerSourceRect(599, 399, 600, 400), // 3 rotate 180
            ViewerSourceRect(0, 399, 1, 400),   // 4 vertical flip
            ViewerSourceRect(0, 0, 1, 1),       // 5 transpose
            ViewerSourceRect(399, 0, 400, 1),   // 6 rotate 90
            ViewerSourceRect(399, 599, 400, 600), // 7 transverse
            ViewerSourceRect(0, 599, 1, 600)    // 8 rotate 270
        )
        for (tag in 1..8) {
            val o = requireNotNull(viewerOrientationFromExif(600, 400, tag))
            assertEquals("EXIF $tag", expected[tag - 1],
                viewerUprightRegionForRawTile(corner, o))
        }
    }

    @Test fun tenThousandSeededRectsRoundtripWithoutOutOfBounds() {
        val rng = Random(20261023)
        repeat(10_000) {
            val width = rng.nextInt(1, 100_001)
            val height = rng.nextInt(1, 100_001)
            val tag = rng.nextInt(1, 9)
            val o = requireNotNull(viewerOrientationFromExif(width, height, tag))
            val x0 = rng.nextInt(width)
            val x1 = rng.nextInt(x0 + 1, width + 1)
            val y0 = rng.nextInt(height)
            val y1 = rng.nextInt(y0 + 1, height + 1)
            val raw = ViewerSourceRect(x0, y0, x1, y1)
            val upright = requireNotNull(viewerUprightRegionForRawTile(raw, o))
            assertTrue(upright.right <= o.uprightWidth)
            assertTrue(upright.bottom <= o.uprightHeight)
            assertEquals(raw, viewerRawRegionForUprightTile(upright, o))
        }
    }

    @Test fun tiledZoomAccountsForRotationCopyAcross200MpSource() {
        for (exif in 1..8) {
            val o = requireNotNull(viewerOrientationFromExif(20_000, 10_000, exif))
            for (zoom in listOf(1f, 2.5f, 5f)) {
                val plan = requireNotNull(planViewerOrientedTiles(
                    o, 1440, 3120, zoom, 0f, 0f))
                assertEquals(plan.upright.tiles.size, plan.rawTiles.size)
                assertTrue(plan.upright.tiles.size in 1..12)
                assertTrue(plan.estimatedPeakTileBytes <= 16L * 1024 * 1024)
                val expectedMultiplier = if (
                    o.clockwiseDegrees != 0 || o.mirrorHorizontally) 2 else 1
                assertEquals(plan.upright.maxDecodedBytes * expectedMultiplier,
                    plan.estimatedPeakTileBytes)
                for (i in plan.rawTiles.indices) {
                    val raw = plan.rawTiles[i]
                    assertTrue(raw.right <= o.rawWidth && raw.bottom <= o.rawHeight)
                    assertEquals(plan.upright.tiles[i],
                        viewerUprightRegionForRawTile(raw, o))
                }
            }
        }
    }

    @Test fun orientationMemoryBudgetRejectsUnderprovisionAndCorruptInput() {
        val o = requireNotNull(viewerOrientationFromExif(20_000, 10_000, 6))
        assertNull(planViewerOrientedTiles(o, 1440, 3120, 2f, 0f, 0f,
            totalTileBufferBudgetBytes = 0))
        assertNull(planViewerOrientedTiles(o, 1440, 3120, 2f, 0f, 0f,
            totalTileBufferBudgetBytes = 1_000_000))
        assertNull(planViewerOrientedTiles(o, 1440, 3120, Float.NaN, 0f, 0f))
        assertNull(planViewerOrientedTiles(o, 1440, 3120, 2f,
            Float.POSITIVE_INFINITY, 0f))
    }

    @Test fun malformedOrientationOrOutOfSourceTileRefusesSafely() {
        assertNull(viewerOrientationFromExif(600, 400, 0))
        assertNull(viewerOrientationFromExif(600, 400, 9))
        assertNull(viewerOrientationFromExif(0, 400, 1))
        val o = requireNotNull(viewerOrientationFromExif(600, 400, 6))
        assertNull(viewerRawRegionForUprightTile(ViewerSourceRect(0, 0, 401, 600), o))
        assertNull(viewerUprightRegionForRawTile(ViewerSourceRect(0, 0, 601, 400), o))
    }
}
