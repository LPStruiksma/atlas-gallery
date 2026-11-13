package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Synthetic filenames only; metadata formatting must not open an image. */
class ViewerDetailsFactsTest {
    @Test fun photoKeepsOnlyReadableNonSensitiveMetadata() {
        val photo = MediaRecord(
            id = 42L, uri = "content://synthetic/media/42",
            name = "Landscape.jpg", relativePath = "DCIM/Camera/",
            sizeBytes = 2_500_000L, takenAtMillis = 1_600_000_000_000L,
            isVideo = false, modifiedAtSeconds = 1_700_000_000L
        )
        val f = viewerDetailsFacts(photo, Locale.US)
        assertEquals("Landscape.jpg", f.filename)
        assertEquals("Photo", f.kind)
        assertEquals("Camera", f.album)
        assertEquals("DCIM/Camera", f.relativeFolder)
        assertEquals("2.5 MB", f.fileSize)
        assertNull(f.duration)
        assertEquals(1_600_000_000_000L, f.takenAtMillis)
        assertEquals(1_700_000_000_000L, f.modifiedAtMillis)
        assertFalse(f.toString().contains(photo.uri))
        assertFalse(f.toString().contains("/storage/emulated/"))
    }

    @Test fun videoAndMissingMetadataHaveHonestFallbacks() {
        val video = MediaRecord(
            id = 2L, uri = "content://synthetic/2", name = "",
            relativePath = "", sizeBytes = -1L, takenAtMillis = 0L,
            isVideo = true, durationMillis = 3_661_999L
        )
        val f = viewerDetailsFacts(video, Locale.US)
        assertEquals("Unnamed item", f.filename)
        assertEquals("Video", f.kind)
        assertEquals("Root", f.album)
        assertNull(f.relativeFolder)
        assertNull(f.fileSize)
        assertNull(f.takenAtMillis)
        assertNull(f.modifiedAtMillis)
        assertEquals("1:01:01", f.duration)
    }

    @Test fun modificationTimestampRejectsNegativeAndOverflowSeconds() {
        fun modified(seconds: Long) = viewerDetailsFacts(MediaRecord(
            id = 3L, uri = "content://synthetic/3", name = "test.jpg",
            relativePath = "DCIM/", sizeBytes = 1L, takenAtMillis = 0L,
            isVideo = false, modifiedAtSeconds = seconds
        ), Locale.US).modifiedAtMillis
        assertNull(modified(0L))
        assertNull(modified(-1L))
        assertNull(modified(Long.MAX_VALUE))
        assertEquals(1_000L, modified(1L))
        assertEquals(9_223_372_036_854_775_000L, modified(Long.MAX_VALUE / 1000L))
    }

    @Test fun sizesAndDurationsAreFiniteAcrossLongRange() {
        assertEquals("0 B", viewerFileSize(0L, Locale.US))
        assertEquals("999 B", viewerFileSize(999L, Locale.US))
        assertEquals("1.0 KB", viewerFileSize(1_000L, Locale.US))
        assertEquals("1.0 GB", viewerFileSize(1_000_000_000L, Locale.US))
        assertEquals("9.2 EB", viewerFileSize(Long.MAX_VALUE, Locale.US))
        assertEquals("Unknown", viewerFileSize(-1L, Locale.US))
        assertNull(viewerDuration(0L))
        assertNull(viewerDuration(-1L))
        assertEquals("0:01", viewerDuration(1_234L))
        assertEquals("59:59", viewerDuration(3_599_999L))
        assertEquals("1:00:00", viewerDuration(3_600_000L))
    }
}
