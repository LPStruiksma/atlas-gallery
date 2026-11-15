package nl.atlas.gallery

import nl.atlas.gallery.featuremedia.thumbnailDecodePixels
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test fun preservesSharpOversamplingAcrossZoomLevels() {
        assertEquals(96, thumbnailDecodePixels(40, 40))
        assertEquals(160, thumbnailDecodePixels(100, 100))
        assertEquals(288, thumbnailDecodePixels(100, 175))
        assertEquals(288, thumbnailDecodePixels(175, 100))
        assertEquals(608, thumbnailDecodePixels(400, 400))
        assertEquals(900, thumbnailDecodePixels(900, 900))
    }

    @Test fun handlesUnknownDimensionsAndPreservesMemoryBuckets() {
        assertEquals(400, thumbnailDecodePixels(0, 400))
        assertEquals(400, thumbnailDecodePixels(400, -1))
        assertEquals(96, thumbnailDecodePixels(1, 1))
        assertEquals(160, thumbnailDecodePixels(101, 101))
        assertEquals(900, thumbnailDecodePixels(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
