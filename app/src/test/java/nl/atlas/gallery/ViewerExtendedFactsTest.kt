package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** #683: pure formatting regressions for the on-demand Viewer Details panel. */
class ViewerExtendedFactsTest {
    private val photo = viewerDetailsFacts(MediaRecord(
        id = 42, uri = "content://synthetic/42", name = "sample.jpg",
        relativePath = "DCIM/Camera/", sizeBytes = 2_500_000L,
        takenAtMillis = 1_600_000_000_000L, isVideo = false
    ), Locale.US)

    @Test fun exifCaptureTimeOverridesCatalogueDateAndFallsBackWhenMissing() {
        assertEquals(1_700_000_000_000L,
            viewerCaptureDateMillis(photo, ViewerExtendedMetadata(originalDateMillis = 1_700_000_000_000L)))
        assertEquals(photo.takenAtMillis, viewerCaptureDateMillis(photo, null))
        assertEquals(photo.takenAtMillis,
            viewerCaptureDateMillis(photo, ViewerExtendedMetadata(originalDateMillis = -1L)))
    }

    @Test fun cameraValuesShowUnitsAndDropMissingFields() {
        val rows = viewerCameraRows(ViewerExtendedMetadata(
            cameraMake = "Samsung", cameraModel = "SM-S928B", lens = "Wide angle",
            iso = 200, focalMm = 6.3, focal35Mm = 23, apertureF = 1.7,
            exposureSeconds = 0.008, flashFired = false,
            latitude = 52.123456, longitude = 4.987654
        ), Locale.US)
        assertEquals(listOf(
            ViewerFactRow("Camera", "Samsung SM-S928B"),
            ViewerFactRow("Lens", "Wide angle"),
            ViewerFactRow("ISO", "200"),
            ViewerFactRow("Focal length", "6.3 mm · 23 mm equiv."),
            ViewerFactRow("Aperture", "f/1.7"),
            ViewerFactRow("Exposure", "1/125 s"),
            ViewerFactRow("Flash", "Off"),
            ViewerFactRow("Location", "52.12346, 4.98765")
        ), rows)
        assertEquals(emptyList<ViewerFactRow>(), viewerCameraRows(ViewerExtendedMetadata(), Locale.US))
    }

    @Test fun malformedNumericsCannotBecomePlausibleCameraValues() {
        val rows = viewerCameraRows(ViewerExtendedMetadata(
            iso = -1, focalMm = Double.NaN, focal35Mm = 0,
            apertureF = Double.POSITIVE_INFINITY, exposureSeconds = -2.0,
            latitude = 95.0, longitude = -181.0
        ), Locale.US)
        assertEquals(emptyList<ViewerFactRow>(), rows)
    }

    @Test fun videoDurationCodecAndFpsPreferExtractedValues() {
        assertEquals(listOf(
            ViewerFactRow("Duration", "1:01"),
            ViewerFactRow("Codec", "H.264"),
            ViewerFactRow("Frame rate", "29.97 fps")
        ), viewerVideoRows(ViewerExtendedMetadata(
            videoDurationMillis = 61_000, videoCodec = "H.264", videoFps = 29.97
        ), fallbackDurationMillis = 1_000L, locale = Locale.US))
        assertEquals(listOf(ViewerFactRow("Duration", "0:02")),
            viewerVideoRows(null, fallbackDurationMillis = 2_100L, locale = Locale.US))
        assertEquals(emptyList<ViewerFactRow>(),
            viewerVideoRows(ViewerExtendedMetadata(videoFps = Double.NaN,
                videoDurationMillis = 0L, videoCodec = " "), 0L, Locale.US))
    }

    @Test fun megapixelsRequireTwoPositiveDimensions() {
        assertEquals("12.0 MP", viewerMegapixels(ViewerDimensions(4000, 3000), Locale.US))
        assertNull(viewerMegapixels(ViewerDimensions(0, 3000), Locale.US))
    }
}
