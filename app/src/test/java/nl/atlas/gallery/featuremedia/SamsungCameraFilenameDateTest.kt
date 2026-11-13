package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Invented Samsung-style camera filenames, with no Android device or real media. */
class SamsungCameraFilenameDateTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val modified = 1_700_000_000L

    private fun fromName(name: String, taken: Long = 0L): ResolvedMediaDate =
        resolveMediaDate(taken, name, modified, modified - 1L, zone)

    @Test fun galaxyCameraPhotoAndVideoNamesRestoreMissingCaptureTimes() {
        val captured = LocalDateTime.of(2026, 11, 1, 9, 30, 7)
            .atZone(zone).toInstant().toEpochMilli()
        for (name in listOf(
            "20261101_093007.jpg",
            "20261101_093007.heic",
            "20261101_093007.mp4",
            "20261101_093007(1).jpg",
            "20261101_093007123.jpg"
        )) {
            val date = fromName(name)
            assertEquals(name, MediaDateSource.FILENAME_TIMESTAMP, date.source)
            assertEquals(name, captured, date.millis)
        }
    }

    @Test fun galaxyScreenRecordingsRestoreMissingCaptureTimes() {
        val captured = LocalDateTime.of(2026, 11, 1, 9, 30, 7)
            .atZone(zone).toInstant().toEpochMilli()
        for (name in listOf(
            "Screen_Recording_20261101_093007_Samsung Internet.mp4",
            "screen_recording_20261101_093007_Chrome.mp4",
            "Screen_Recording_20261101_093007.mp4"
        )) {
            assertEquals(name, ResolvedMediaDate(captured, MediaDateSource.FILENAME_TIMESTAMP),
                fromName(name))
        }
        for (name in listOf(
            "Screen_Recording_20260229_120000_Chrome.mp4",
            "Screen_Recording_20261101_256000_Chrome.mp4",
            "prefix_Screen_Recording_20261101_093007.mp4"
        )) {
            assertEquals(name, ResolvedMediaDate(modified * 1000L, MediaDateSource.MODIFIED),
                fromName(name))
        }
    }

    @Test fun filenameTimePreservesNewestAndOldestOrderAfterBulkCopy() {
        // Synthetic bulk copies have identical modification times; filenames
        // are the only remaining evidence of chronological capture order.
        val oldDate = fromName("20261031_235959.jpg")
        val newDate = fromName("20261101_093007.jpg")
        assertTrue(oldDate.millis < newDate.millis)
        val old = MediaRecord(1, "content://example/1", "20261031_235959.jpg",
            "DCIM/Camera/", 100, oldDate.millis, false)
        val recent = MediaRecord(2, "content://example/2", "20261101_093007.jpg",
            "DCIM/Camera/", 100, newDate.millis, false)
        assertEquals(listOf(recent, old), sortMedia(listOf(old, recent), SortOrder.NEWEST, 0))
        assertEquals(listOf(old, recent), sortMedia(listOf(recent, old), SortOrder.OLDEST, 0))
    }

    @Test fun explicitlyReportedCaptureTimestampStillOverridesFilename() {
        val taken = 1_700_000_000_000L
        assertEquals(ResolvedMediaDate(taken, MediaDateSource.TAKEN),
            fromName("20261101_093007.jpg", taken))
    }

    @Test fun malformedAndNonCameraBasenamesNeverFabricateCaptureDates() {
        for (name in listOf(
            "20260229_120000.jpg",      // Not a leap year
            "20260329_023000.jpg",      // Nonexistent DST hour in Amsterdam
            "20261101_253000.mp4",      // Invalid clock
            "20261101_0930071234.jpg", // Arbitrary extra digits
            "holiday20261101_093007.jpg", // Embedded text, not camera basename
            "20261101_09300.jpg"        // Missing one clock digit
        )) {
            val date = fromName(name)
            assertEquals(name, MediaDateSource.MODIFIED, date.source)
            assertEquals(name, modified * 1000L, date.millis)
        }
    }
}
