package nl.atlas.gallery

import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #23: deterministic geometry and grant/URI cancellation; synthetic metadata only. */
@RunWith(AndroidJUnit4::class)
class SharedPhotoMotionContractTest {
    private fun photo(i: Long) = MediaRecord(i,
        "content://media/external/images/media/$i",
        "Invented $i", "Pictures/ATLAS-TEST/", 4L, 0L, false)

    @Test fun clickIsOneShotAndBoundToPhotoUriPublicationAndFreshFrame() {
        val a = photo(71)
        val bounds = Rect(24f, 220f, 310f, 506f)
        SharedPhotoOrigins.offer(a, 4L, bounds, nowMillis = 1_000L)
        assertNull("new grant must not use the old pixel", SharedPhotoOrigins.take(
            a.uri, 5L, nowMillis = 1_030L))
        assertNull("stale offer was consumed", SharedPhotoOrigins.take(
            a.uri, 4L, nowMillis = 1_040L))

        SharedPhotoOrigins.offer(a, 4L, bounds, nowMillis = 2_000L)
        assertNull("a fast scrub must not use the tapped cell for another photo",
            SharedPhotoOrigins.take(photo(72).uri, 4L, nowMillis = 2_020L))

        SharedPhotoOrigins.offer(a, 4L, bounds, nowMillis = 3_000L)
        assertNull("late surface must skip photo animation", SharedPhotoOrigins.take(
            a.uri, 4L, nowMillis = 3_000L + SharedPhotoOrigins.MAX_AGE_MS + 1L))
        SharedPhotoOrigins.offer(a, 4L, bounds, nowMillis = 4_000L)
        assertEquals(bounds, SharedPhotoOrigins.take(a.uri, 4L, 4_030L)?.bounds)
        assertNull("one-shot only", SharedPhotoOrigins.take(a.uri, 4L, 4_040L))
    }

    @Test fun invalidSourceAndCancelledEntryCannotLeakMotion() {
        val a = photo(73)
        SharedPhotoOrigins.offer(a, 1L, Rect(Float.NaN, 0f, 1f, 1f), 5_000L)
        assertNull(SharedPhotoOrigins.take(a.uri, 1L, 5_010L))
        SharedPhotoOrigins.offer(a, 1L, Rect(0f, 0f, 9f, 9f), 6_000L)
        SharedPhotoOrigins.clear()
        assertNull(SharedPhotoOrigins.take(a.uri, 1L, 6_010L))
    }

    @Test fun interpolatedFrameStartsAtActualTileAndEndsAtViewer() {
        val tile = Rect(24f, 220f, 310f, 506f)
        val viewer = Rect(0f, 0f, 1080f, 2340f)
        assertEquals(tile, sharedPhotoRect(tile, viewer, -1f))
        assertEquals(viewer, sharedPhotoRect(tile, viewer, 2f))
        val half = sharedPhotoRect(tile, viewer, 0.5f)
        assertTrue(half.width > tile.width && half.width < viewer.width)
        assertTrue(half.height > tile.height && half.height < viewer.height)
    }
}
