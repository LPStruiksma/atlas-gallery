package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class GridThumbnailBudgetTest {
    @Test fun smallPortraitAndSquareTilesAvoid900PxDecode() {
        assertEquals(96, gridPreviewRequestPixels(40, 40))
        assertEquals(160, gridPreviewRequestPixels(100, 100))
        assertEquals(288, gridPreviewRequestPixels(100, 175))
        assertEquals(288, gridPreviewRequestPixels(175, 100))
        assertTrue(gridPreviewRequestPixels(100, 175) < 900)
    }

    @Test fun largePanoramasAndPortraitsRetainBoundedFullResolution() {
        assertEquals(900, gridPreviewRequestPixels(600, 100))
        assertEquals(900, gridPreviewRequestPixels(100, 800))
        assertEquals(900, gridPreviewRequestPixels(Int.MAX_VALUE, 1))
    }

    @Test fun squareThreeFourSevenColumnBudgetsScaleWithMeasuredGeometry() {
        // 420 physical px viewport, 3px inset/gap: ceil column widths 136/102/57.
        val large = gridPreviewRequestPixels(136, 136)
        val medium = gridPreviewRequestPixels(102, 102)
        val small = gridPreviewRequestPixels(57, 57)
        assertEquals(224, large)
        assertEquals(160, medium)
        assertEquals(96, small)
        assertTrue(large > medium && medium > small)
    }

    @Test fun missingMeasurementUsesExistingSafeDefault() {
        assertEquals(400, gridPreviewRequestPixels(0, 300))
        assertEquals(400, gridPreviewRequestPixels(-5, 300))
        assertEquals(400, gridPreviewRequestPixels(400, 0))
    }

    @Test fun previewBucketsAreMonotoneBoundedAndDeterministicAtCatalogueScale() {
        var previous = 96
        repeat(150_000) { index ->
            val size = index + 1
            val value = gridPreviewRequestPixels(size, 1)
            assertTrue(value in 96..900)
            assertTrue(value >= previous)
            assertEquals(value, gridPreviewRequestPixels(size, 1))
            previous = value
        }
    }
}
