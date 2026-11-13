package nl.atlas.gallery

import nl.atlas.gallery.featuregrid.GridTimelineIndex
import nl.atlas.gallery.featuregrid.TimelineDisplayedSlots
import nl.atlas.gallery.featuregrid.TimelineHeaderPosition
import nl.atlas.gallery.featuregrid.TimelineSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic timeline cells only. No private media, Android or gallery DB.
 * Lazy keys are positional; anchors resolve through the displayed slots.
 */
class TimelineLayoutSwitchTest {
    private fun media(i: Int) = MediaRecord(i.toLong(), "content://media/$i",
        "fictional-$i.jpg", "DCIM/Fictional/", 1024L, i.toLong(), isVideo = false)

    /** Header, media 0, media 1, header, media 2, media 3 ... (two per day). */
    private fun displayed(source: List<MediaRecord>, epoch: Long): TimelineDisplayedSlots {
        val slots = ArrayList<TimelineSlot>()
        val positions = IntArray(source.size)
        val headers = ArrayList<TimelineHeaderPosition>()
        source.indices.forEach { i ->
            if (i % 2 == 0) {
                val header = TimelineSlot.Header("header:DAY:$i", "Day $i", i,
                    minOf(2, source.size - i))
                headers.add(TimelineHeaderPosition(slots.size, header))
                slots.add(header)
            }
            positions[i] = slots.size
            slots.add(TimelineSlot.Media("media:${source[i].uri}", i))
        }
        return TimelineDisplayedSlots(source, epoch,
            GridTimelineIndex(slots, positions, headers, grouped = true))
    }

    @Test fun headerAtViewportTopAnchorsFirstRealMedia() {
        val source = (0 until 6).map(::media)
        val d = displayed(source, 7L)
        // Slots: 0=H 1=m0 2=m1 3=H 4=m2 5=m3 ...
        assertEquals("content://media/2",
            firstTimelineVisibleMediaUri(listOf(3, 4, 5), 9, d, source, 7L))
        assertEquals("content://media/0",
            firstTimelineVisibleMediaUri(listOf(0, 1, 2), 9, d, source, 7L))
    }

    @Test fun visibleOrderIsNormalisedAndSlotNumberIsNeverAMediaIndex() {
        val source = (0 until 6).map(::media)
        val d = displayed(source, 7L)
        // Slot 4 holds media 2: a naive slot→index read would return media 4.
        assertEquals("content://media/2",
            firstTimelineVisibleMediaUri(listOf(5, 4, 3), 9, d, source, 7L))
    }

    @Test fun headersOnlyRefuseToGuessPriorGridPosition() {
        val source = (0 until 6).map(::media)
        val d = displayed(source, 7L)
        assertNull(firstTimelineVisibleMediaUri(listOf(0, 3), 9, d, source, 7L))
        assertNull(firstTimelineVisibleMediaUri(emptyList(), 9, d, source, 7L))
        assertNull(firstTimelineVisibleMediaUri(listOf(-1, 99), 9, d, source, 7L))
    }

    @Test fun staleSourceEpochOrMountedLayoutFailsClosed() {
        val source = (0 until 6).map(::media)
        val d = displayed(source, 7L)
        // Equal-valued but different list reference: a new publication.
        assertNull(firstTimelineVisibleMediaUri(listOf(1), 9, d, source.toList(), 7L))
        // Same list under a newer permission/publication epoch.
        assertNull(firstTimelineVisibleMediaUri(listOf(1), 9, d, source, 8L))
        // The grid is still mounting a different (e.g. other density) layout.
        assertNull(firstTimelineVisibleMediaUri(listOf(1), 8, d, source, 7L))
        assertNull(firstTimelineVisibleMediaUri(listOf(1), 9, null, source, 7L))
    }

    @Test fun large150kTimelineResolvesWithoutScanningTheCatalogue() {
        val source = (0 until 150_000).map(::media)
        val d = displayed(source, 3L)
        val last = d.timeline.slots.size - 1
        assertEquals(source.last().uri,
            firstTimelineVisibleMediaUri(listOf(last), d.timeline.slots.size, d, source, 3L))
    }
}
