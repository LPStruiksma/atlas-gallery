package nl.atlas.gallery.featureaiedit.tiling

import org.junit.Assert.*
import org.junit.Test

/** Geometry-only fixtures; no bitmap, provider, Android framework or real image bytes. */
class EditTilePlanTest {
    private fun plan(
        width: Int,
        height: Int,
        maxWidth: Int = 2048,
        maxHeight: Int = 2048,
        overlapX: Int = 128,
        overlapY: Int = 128,
        enabled: Boolean = true,
        maxTiles: Int = 4096,
    ): EditTilePlan = EditTilePlanner.plan(
        imageWidth = width,
        imageHeight = height,
        maxTileWidth = maxWidth,
        maxTileHeight = maxHeight,
        overlapX = overlapX,
        overlapY = overlapY,
        enabled = enabled,
        maxTiles = maxTiles,
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid geometry to be rejected")
        } catch (_: IllegalArgumentException) {
            // Geometry is rejected before allocating tile arrays.
        }
    }

    /** Every destination is unique, row-major, contiguous, and an exact local crop of its source. */
    private fun assertExactPartition(plan: EditTilePlan) {
        assertTrue(plan.tiles.isNotEmpty())
        var expectedTop = 0
        var row = 0
        var offset = 0
        while (offset < plan.tiles.size) {
            val rowTiles = plan.tiles.drop(offset).takeWhile { it.row == row }
            assertTrue(rowTiles.isNotEmpty())
            val bandTop = rowTiles.first().destination.top
            val bandBottom = rowTiles.first().destination.bottom
            assertEquals(expectedTop, bandTop)
            assertTrue(bandBottom > bandTop)
            var expectedLeft = 0
            rowTiles.forEachIndexed { col, tile ->
                assertEquals(row, tile.row)
                assertEquals(col, tile.column)
                assertEquals(expectedLeft, tile.destination.left)
                assertEquals(bandTop, tile.destination.top)
                assertEquals(bandBottom, tile.destination.bottom)
                assertTrue(tile.destination.right > tile.destination.left)
                assertEquals(tile.source.left + tile.crop.left, tile.destination.left)
                assertEquals(tile.source.top + tile.crop.top, tile.destination.top)
                assertEquals(tile.source.left + tile.crop.right, tile.destination.right)
                assertEquals(tile.source.top + tile.crop.bottom, tile.destination.bottom)
                assertTrue(tile.source.left >= 0 && tile.source.top >= 0)
                assertTrue(tile.source.right <= plan.imageWidth && tile.source.bottom <= plan.imageHeight)
                assertTrue(tile.crop.left >= 0 && tile.crop.top >= 0)
                assertTrue(tile.crop.right <= tile.source.width && tile.crop.bottom <= tile.source.height)
                assertEquals(tile.destination.width, tile.crop.width)
                assertEquals(tile.destination.height, tile.crop.height)
                expectedLeft = tile.destination.right
            }
            assertEquals(plan.imageWidth, expectedLeft)
            expectedTop = bandBottom
            offset += rowTiles.size
            row++
        }
        assertEquals(plan.imageHeight, expectedTop)
        assertEquals(row, plan.rowCount)
        assertEquals(plan.columnCount * plan.rowCount, plan.tiles.size)
    }

    @Test fun tinyImageStaysWholeWithLocalCropAtOrigin() {
        val result = plan(7, 5)
        assertFalse(result.tiled)
        assertEquals(1, result.columnCount)
        assertEquals(1, result.rowCount)
        val tile = result.tiles.single()
        assertEquals(EditTileRect(0, 0, 7, 5), tile.source)
        assertEquals(EditTileRect(0, 0, 7, 5), tile.crop)
        assertEquals(EditTileRect(0, 0, 7, 5), tile.destination)
        assertExactPartition(result)
    }

    @Test fun eightKUsesStableRowMajorProviderBoundedRectangles() {
        val first = plan(7680, 4320)
        assertEquals(first, plan(7680, 4320))
        assertTrue(first.tiled)
        assertExactPartition(first)
        assertTrue(first.tiles.all { it.source.width <= 2048 && it.source.height <= 2048 })
        assertEquals(0, first.tiles.first().row)
        assertEquals(0, first.tiles.first().column)
        assertEquals(1, first.tiles[1].column)
        assertEquals(0, first.tiles[first.columnCount].column)
        assertEquals(1, first.tiles[first.columnCount].row)
    }

    @Test fun panoramaAndHundredMegapixelsCoverWithoutPixelAllocation() {
        val panorama = plan(12000, 777, maxWidth = 1536, maxHeight = 1024, overlapX = 96, overlapY = 48)
        assertEquals(1, panorama.rowCount)
        assertExactPartition(panorama)
        assertTrue(panorama.tiles.all { it.source.width <= 1536 })

        val hundredMp = plan(10000, 10000)
        assertEquals(10000L * 10000L, hundredMp.imageWidth.toLong() * hundredMp.imageHeight)
        assertExactPartition(hundredMp)
        assertTrue(hundredMp.tiles.all { it.source.width <= 2048 && it.source.height <= 2048 })
    }

    @Test fun oddOverlapSeamsChooseDeterministicFloorMidpoint() {
        val result = plan(19, 17, maxWidth = 8, maxHeight = 7, overlapX = 3, overlapY = 2)
        assertExactPartition(result)
        val first = result.tiles[0]
        val second = result.tiles[1]
        assertEquals(EditTileRect(0, 0, 8, 7), first.source)
        assertEquals(EditTileRect(5, 0, 13, 7), second.source)
        assertEquals(6, first.destination.right)
        assertEquals(6, second.destination.left)
        assertEquals(6, first.crop.right)
        assertEquals(1, second.crop.left)
        assertEquals(6, result.tiles.first().destination.bottom) // y seam: 7/5 -> 6
        assertEquals(6, result.tiles[result.columnCount].destination.top)
    }

    @Test fun zeroOverlapAndExactBoundariesHaveNoExtraTiles() {
        val exact = plan(16, 8, maxWidth = 8, maxHeight = 8, overlapX = 0, overlapY = 0)
        assertEquals(2, exact.tiles.size)
        assertExactPartition(exact)
        assertEquals(8, exact.tiles.first().destination.right)
        assertEquals(8, exact.tiles.last().destination.left)

        val oneExtra = plan(17, 9, maxWidth = 8, maxHeight = 8, overlapX = 0, overlapY = 0)
        assertEquals(6, oneExtra.tiles.size)
        assertExactPartition(oneExtra)
    }

    @Test fun disabledTilingPreservesOriginalSizeEvenBeyondProviderCaps() {
        val result = plan(
            Int.MAX_VALUE, Int.MAX_VALUE,
            maxWidth = 512, maxHeight = 512,
            overlapX = 32, overlapY = 32,
            enabled = false,
        )
        assertFalse(result.tiled)
        assertEquals(1, result.tiles.size)
        assertEquals(EditTileRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE), result.tiles.single().source)
        assertExactPartition(result)
    }

    @Test fun nearIntegerMaximumCoordinatesDoNotWrap() {
        val width = Int.MAX_VALUE
        val result = plan(
            width, 2, maxWidth = Int.MAX_VALUE - 1, maxHeight = 2,
            overlapX = 0, overlapY = 0,
        )
        assertExactPartition(result)
        assertEquals(2, result.tiles.size)
        assertEquals(width - 1, result.tiles[1].source.left)
        assertEquals(width, result.tiles[1].source.right)
        assertEquals(1, result.tiles[1].source.width)
    }

    @Test fun invalidSizesOverlapAndCapAreRejected() {
        rejects { plan(0, 20) }
        rejects { plan(1, -1) }
        rejects { plan(10, 10, maxWidth = 0) }
        rejects { plan(10, 10, maxHeight = -2) }
        rejects { plan(10, 10, overlapX = -1) }
        rejects { plan(10, 10, overlapY = -1) }
        rejects { plan(10, 10, maxWidth = 128, overlapX = 128) }
        rejects { plan(10, 10, maxHeight = 128, overlapY = 129) }
        rejects { plan(10, 10, maxTiles = 0) }
        rejects { plan(10, 10, maxTiles = 4097) }
    }

    @Test fun giantInputFailsBeforeAllocatingOrOverflowingTileCount() {
        rejects { plan(Int.MAX_VALUE, Int.MAX_VALUE, maxWidth = 1024, maxHeight = 1024, overlapX = 0, overlapY = 0) }
        rejects { plan(100, 100, maxWidth = 32, maxHeight = 32, overlapX = 0, overlapY = 0, maxTiles = 15) }
        assertEquals(16, plan(100, 100, maxWidth = 32, maxHeight = 32, overlapX = 0, overlapY = 0, maxTiles = 16).tiles.size)
    }

    @Test fun highOverlapStillProgressesAndRespectsTileCap() {
        val result = plan(13, 13, maxWidth = 8, maxHeight = 8, overlapX = 7, overlapY = 7, maxTiles = 36)
        assertEquals(36, result.tiles.size)
        assertExactPartition(result)
        rejects { plan(13, 13, maxWidth = 8, maxHeight = 8, overlapX = 7, overlapY = 7, maxTiles = 35) }
    }
}
