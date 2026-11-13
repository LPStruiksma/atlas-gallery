package nl.atlas.gallery.featuregrid

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #507: pinch handoff between the square timeline and original rows. */
class ZoomEntryHandoffTest {
    @Test fun takenOnceByTheMatchingDestination() {
        ZoomEntryHandoff.offer(true, 7L, 300f, Offset(500f, 900f), nowMillis = 1_000L)
        assertNull("other direction", ZoomEntryHandoff.take(false, 7L, nowMillis = 1_100L))
        ZoomEntryHandoff.offer(true, 7L, 300f, Offset(500f, 900f), nowMillis = 1_000L)
        assertNull("other catalogue", ZoomEntryHandoff.take(true, 8L, nowMillis = 1_100L))
        ZoomEntryHandoff.offer(true, 7L, 300f, Offset(500f, 900f), nowMillis = 1_000L)
        assertNotNull(ZoomEntryHandoff.take(true, 7L, nowMillis = 1_100L))
        assertNull("one-shot", ZoomEntryHandoff.take(true, 7L, nowMillis = 1_150L))
    }

    @Test fun staleOrInvalidHandoffsAreDropped() {
        ZoomEntryHandoff.offer(false, 7L, 300f, Offset(1f, 1f), nowMillis = 1_000L)
        assertNull(ZoomEntryHandoff.take(false, 7L,
            nowMillis = 1_001L + ZoomEntryHandoff.MAX_AGE_MS))
        ZoomEntryHandoff.offer(false, 7L, Float.NaN, Offset(1f, 1f), nowMillis = 1_000L)
        assertNull(ZoomEntryHandoff.take(false, 7L, nowMillis = 1_010L))
    }

    @Test fun startsAtTheLeftSizeAndFocusThenRestsAtIdentity() {
        val entry = ZoomEntryHandoff.Entry(true, 7L, 600f, Offset(800f, 400f), 0L)
        val center = Offset(540f, 1170f)
        val (s0, dx0, dy0) = ZoomEntryHandoff.frame(entry, 400f, center, 0f)
        assertEquals(1.5f, s0, 1e-4f)
        assertEquals(260f, dx0, 1e-3f)
        assertEquals(-770f, dy0, 1e-3f)
        val (s1, dx1, dy1) = ZoomEntryHandoff.frame(entry, 400f, center, 1f)
        assertEquals(1f, s1, 1e-4f)
        assertEquals(0f, dx1, 1e-4f)
        assertEquals(0f, dy1, 1e-4f)
        // Clamped like the live pinch.
        assertEquals(GalleryPinchTracker.MAX_ZOOM,
            ZoomEntryHandoff.frame(entry, 10f, center, 0f).first, 1e-4f)
    }
}
