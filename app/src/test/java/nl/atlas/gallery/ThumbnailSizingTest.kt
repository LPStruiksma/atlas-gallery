package nl.atlas.gallery

import nl.atlas.gallery.featuremedia.thumbnailDecodePixels
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test fun usesActualCellPixelSizeWithoutDownsamplingAtRest() {
        assertEquals(400, thumbnailDecodePixels(cellPixels = 400, displayPixels = 1080))
        assertEquals(731, thumbnailDecodePixels(cellPixels = 731, displayPixels = 1440))
    }

    @Test fun clipsInvalidAndOversizedCellToDisplayBounds() {
        assertEquals(1, thumbnailDecodePixels(cellPixels = 0, displayPixels = 1080))
        assertEquals(1, thumbnailDecodePixels(cellPixels = -9, displayPixels = 1080))
        assertEquals(1080, thumbnailDecodePixels(cellPixels = 3000, displayPixels = 1080))
        assertEquals(1, thumbnailDecodePixels(cellPixels = Int.MAX_VALUE, displayPixels = 0))
    }

    @Test fun staysPixelExactAtEveryZoomCellWidth() {
        for (cell in listOf(72, 128, 196, 312, 480, 720, 1080)) {
            assertEquals(cell, thumbnailDecodePixels(cellPixels = cell, displayPixels = 1080))
        }
    }
}
