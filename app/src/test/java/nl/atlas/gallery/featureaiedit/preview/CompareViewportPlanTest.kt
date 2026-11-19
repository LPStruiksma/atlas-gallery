package nl.atlas.gallery.featureaiedit.preview

import org.junit.Assert.*
import org.junit.Test

/** Synthetic geometry only: never constructs or decodes image pixels. */
class CompareViewportPlanTest {
    private val landscape = CompareImageSize(4000, 2000)
    private val portrait = CompareImageSize(2000, 4000)
    private val squareViewport = CompareViewportSize(1000, 1000)

    private fun plan(
        original: CompareImageSize = landscape,
        candidate: CompareImageSize = portrait,
        viewport: CompareViewportSize = squareViewport,
        focus: CompareNormalizedPoint = CompareNormalizedPoint(0.5, 0.5),
        zoom: Double = 1.0,
        bytes: Long = 8L * 1024L * 1024L,
        side: Int = 2048,
    ) = CompareViewportPlanner.plan(
        original = original,
        candidate = candidate,
        viewport = viewport,
        focus = focus,
        zoom = zoom,
        maxCombinedPreviewBytes = bytes,
        maxDecodedSide = side,
    )

    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-8) {
        assertEquals(expected, actual, tolerance)
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid viewport input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid geometry and resource ceilings fail closed.
        }
    }

    private fun assertDecodeBounded(result: CompareViewportPlan, maxBytes: Long, maxSide: Int) {
        assertEquals(
            result.original.previewDecode.reservedBytes + result.candidate.previewDecode.reservedBytes,
            result.totalDecodeBytes,
        )
        assertTrue(result.totalDecodeBytes <= maxBytes)
        listOf(result.original, result.candidate).forEach { frame ->
            val decode = frame.previewDecode
            val crop = frame.visibleSource
            assertTrue(crop.left >= 0 && crop.top >= 0)
            assertTrue(crop.right <= frame.image.width && crop.bottom <= frame.image.height)
            assertTrue(crop.right > crop.left && crop.bottom > crop.top)
            assertTrue(decode.sampleLevel in 0..31)
            assertEquals(1L shl decode.sampleLevel, decode.downsampleBy)
            assertEquals(0L, decode.downsampleBy and (decode.downsampleBy - 1L))
            val expectedWidth = (crop.width.toLong() + decode.downsampleBy - 1L) / decode.downsampleBy
            val expectedHeight = (crop.height.toLong() + decode.downsampleBy - 1L) / decode.downsampleBy
            assertEquals(expectedWidth.toInt(), decode.width)
            assertEquals(expectedHeight.toInt(), decode.height)
            assertTrue(decode.width in 1..maxSide)
            assertTrue(decode.height in 1..maxSide)
            assertEquals(decode.width.toLong() * decode.height, decode.pixels)
            assertEquals(decode.pixels * CompareViewportPlanner.BYTES_PER_PIXEL, decode.reservedBytes)
            assertTrue(decode.pixels <= maxBytes / (2L * CompareViewportPlanner.BYTES_PER_PIXEL))
        }
    }

    @Test fun differentlyOrientedFramesLetterboxOnOppositeAxes() {
        val result = plan()
        assertEquals(CompareSourceRect(0, 0, 4000, 2000), result.original.visibleSource)
        assertEquals(CompareSourceRect(0, 0, 2000, 4000), result.candidate.visibleSource)
        assertEquals(CompareViewportRect(0.0, 250.0, 1000.0, 750.0), result.original.visibleViewport)
        assertEquals(CompareViewportRect(250.0, 0.0, 750.0, 1000.0), result.candidate.visibleViewport)
        assertNull(result.original.viewportToSourceOrNull(CompareViewportPoint(500.0, 100.0)))
        assertNull(result.candidate.viewportToSourceOrNull(CompareViewportPoint(100.0, 500.0)))
        close(0.5, result.original.viewportToSourceOrNull(CompareViewportPoint(500.0, 500.0))!!.x)
        close(0.5, result.candidate.viewportToSourceOrNull(CompareViewportPoint(500.0, 500.0))!!.y)
        assertDecodeBounded(result, 8L * 1024 * 1024, 2048)
    }

    @Test fun unequalResolutionsShareNormalizedCropAndCentreForMatchingAspectRatio() {
        val result = plan(
            original = CompareImageSize(4000, 3000),
            candidate = CompareImageSize(2000, 1500),
            zoom = 2.0,
        )
        assertEquals(CompareSourceRect(1000, 500, 3000, 2500), result.original.visibleSource)
        assertEquals(CompareSourceRect(500, 250, 1500, 1250), result.candidate.visibleSource)
        for (frame in listOf(result.original, result.candidate)) {
            close(500.0, frame.sourceToViewport(CompareNormalizedPoint(0.5, 0.5)).x)
            close(500.0, frame.sourceToViewport(CompareNormalizedPoint(0.5, 0.5)).y)
        }
        assertDecodeBounded(result, 8L * 1024 * 1024, 2048)
    }

    @Test fun asymmetricPanZoomKeepsOneVisualAnchorAcrossOrientations() {
        val focus = CompareNormalizedPoint(0.35, 0.65)
        val result = plan(focus = focus, zoom = 4.0)
        for (frame in listOf(result.original, result.candidate)) {
            val projected = frame.sourceToViewport(focus)
            close(500.0, projected.x)
            close(500.0, projected.y)
            val inverse = frame.viewportToSourceOrNull(projected)!!
            close(focus.x, inverse.x)
            close(focus.y, inverse.y)
            assertTrue(frame.visibleSource.width < frame.image.width)
            assertTrue(frame.visibleSource.height < frame.image.height)
        }
        assertDecodeBounded(result, 8L * 1024 * 1024, 2048)
    }

    @Test fun focusIsClampedAtBothEndsInsteadOfOverscrollingSource() {
        val result = plan(focus = CompareNormalizedPoint(0.0, 1.0), zoom = 4.0)
        for (frame in listOf(result.original, result.candidate)) {
            val leftBottom = frame.sourceToViewport(CompareNormalizedPoint(0.0, 1.0))
            close(0.0, leftBottom.x)
            close(1000.0, leftBottom.y)
            close(0.0, frame.visibleViewport.left)
            close(0.0, frame.visibleViewport.top)
            close(1000.0, frame.visibleViewport.right)
            close(1000.0, frame.visibleViewport.bottom)
        }
        assertEquals(0, result.original.visibleSource.left)
        assertEquals(2000, result.original.visibleSource.bottom)
        assertEquals(0, result.candidate.visibleSource.left)
        assertEquals(4000, result.candidate.visibleSource.bottom)
    }

    @Test fun fitScaleRetainsCentredLetterboxRegardlessOfOffCentreFocus() {
        val result = plan(focus = CompareNormalizedPoint(0.99, 0.01))
        assertEquals(CompareViewportRect(0.0, 250.0, 1000.0, 750.0), result.original.visibleViewport)
        assertEquals(CompareViewportRect(250.0, 0.0, 750.0, 1000.0), result.candidate.visibleViewport)
        assertEquals(CompareSourceRect(0, 0, 4000, 2000), result.original.visibleSource)
        assertEquals(CompareSourceRect(0, 0, 2000, 4000), result.candidate.visibleSource)
    }

    @Test fun normalizedPointsRoundTripThroughoutVisibleContent() {
        val result = plan(focus = CompareNormalizedPoint(0.47, 0.58), zoom = 7.0)
        for (frame in listOf(result.original, result.candidate)) {
            for (x in listOf(0.15, 0.4, 0.7, 0.85)) {
                for (y in listOf(0.2, 0.45, 0.55, 0.8)) {
                    val viewportPoint = CompareViewportPoint(
                        frame.visibleViewport.left + (frame.visibleViewport.right - frame.visibleViewport.left) * x,
                        frame.visibleViewport.top + (frame.visibleViewport.bottom - frame.visibleViewport.top) * y,
                    )
                    val source = frame.viewportToSourceOrNull(viewportPoint)!!
                    val back = frame.sourceToViewport(source)
                    close(viewportPoint.x, back.x, 1e-7)
                    close(viewportPoint.y, back.y, 1e-7)
                }
            }
        }
    }

    @Test fun oneSampleRequestPerFrameRespectsTightCombinedMemoryBudget() {
        val result = plan(bytes = 8000, side = 32, zoom = 1.0)
        assertDecodeBounded(result, 8000, 32)
        assertTrue(result.original.previewDecode.downsampleBy > 1)
        assertTrue(result.candidate.previewDecode.downsampleBy > 1)
        assertEquals(result, plan(bytes = 8000, side = 32, zoom = 1.0))
    }

    @Test fun extremeZoomUsesOnlySmallVisibleCropRatherThanFullImage() {
        val result = plan(zoom = CompareViewportPlanner.MAX_ZOOM, bytes = 4096, side = 16)
        assertDecodeBounded(result, 4096, 16)
        for (frame in listOf(result.original, result.candidate)) {
            assertTrue(frame.visibleSource.width < frame.image.width / 4)
            assertTrue(frame.visibleSource.height < frame.image.height / 4)
        }
    }

    @Test fun integerMaximumImagesNeverCausePixelProductOverflowOrBitmapAllocation() {
        val giant = CompareImageSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val result = plan(original = giant, candidate = giant, bytes = 8, side = 1)
        assertDecodeBounded(result, 8, 1)
        assertEquals(31, result.original.previewDecode.sampleLevel)
        assertEquals(1, result.original.previewDecode.width)
        assertEquals(1, result.original.previewDecode.height)
        assertEquals(8L, result.totalDecodeBytes)
    }

    @Test fun sampledDimensionsNeverExceedVisibleSourceOrConfiguredSideLimit() {
        val result = plan(
            original = CompareImageSize(1, 1),
            candidate = CompareImageSize(12000, 9000),
            viewport = CompareViewportSize(1440, 1920),
            bytes = 16L * 1024L,
            side = 64,
        )
        assertDecodeBounded(result, 16L * 1024L, 64)
        assertEquals(1, result.original.previewDecode.width)
        assertEquals(1, result.original.previewDecode.height)
        assertTrue(result.candidate.previewDecode.pixels > 1)
    }

    @Test fun invalidGeometryZoomOrMemoryCeilingsRejectBeforePlanning() {
        rejects { CompareImageSize(0, 200) }
        rejects { CompareImageSize(-1, 2) }
        rejects { CompareViewportSize(10, 0) }
        rejects { CompareViewportSize(CompareViewportPlanner.MAX_VIEWPORT_SIDE + 1, 10) }
        rejects { CompareNormalizedPoint(Double.NaN, 0.5) }
        rejects { CompareNormalizedPoint(0.5, Double.POSITIVE_INFINITY) }
        rejects { CompareNormalizedPoint(-0.01, 0.5) }
        rejects { CompareNormalizedPoint(0.5, 1.01) }
        rejects { CompareViewportPoint(Double.NaN, 0.0) }
        rejects { plan(zoom = 0.99) }
        rejects { plan(zoom = Double.POSITIVE_INFINITY) }
        rejects { plan(zoom = 65.0) }
        rejects { plan(bytes = 7) }
        rejects { plan(side = 0) }
        rejects { plan(side = CompareViewportPlanner.MAX_DECODED_SIDE + 1) }
    }

    @Test fun subpixelProjectionIsRejectedInsteadOfReturningInvalidCrop() {
        rejects {
            plan(
                original = CompareImageSize(Int.MAX_VALUE, 1),
                candidate = portrait,
                viewport = CompareViewportSize(1, 1000),
            )
        }
    }
}
