package nl.atlas.gallery

import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.TimelineMedia
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.buildGridTimelineIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ViewerTimelineReturnIntegrationTest {
    private fun media(i: Int, time: Long) =
        MediaRecord(i.toLong(), "content://media/$i", "fake-$i.jpg", "DCIM/",
            5L, time, false)

    private fun index(items: List<MediaRecord>, density: GridDensity = GridDensity.MEDIUM) =
        buildGridTimelineIndex(items.map { TimelineMedia(it.uri, it.takenAtMillis) },
            TimelineSort.NEWEST, density, ZoneId.of("Europe/Amsterdam"), Locale.ROOT)

    @Test fun headerIsNotAnImageReturnTarget() {
        val first = media(1, 1728000000000L)
        val second = media(2, 1727913600000L)
        val list = listOf(first, second)
        val timeline = index(list)
        val pending = PendingViewerReturn(list, second.uri, 0, 42L)
        val slot = admittedTimelineReturnSlotOrNull(pending, list, 0, true, 1,
            timeline, timeline.slots.size, 42L)
        assertEquals(timeline.entryForMedia(1), slot)
        assertEquals(3, slot)
    }

    @Test fun staleAndPartiallyMountedTimelineAreRefused() {
        val list = listOf(media(1, 1728000000000L), media(2, 1727913600000L))
        val timeline = index(list)
        val pending = PendingViewerReturn(list, list[1].uri, 0, 42L)
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 0, false, 1,
            timeline, timeline.slots.size, 42L))
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 0, true, 1,
            timeline, timeline.slots.size, 43L))
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 0, true, 1,
            timeline, timeline.slots.size - 1, 42L))
        assertNull(admittedTimelineReturnSlotOrNull(pending, list.toMutableList(), 0, true, 1,
            timeline, timeline.slots.size, 42L))
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 1, true, 1,
            timeline, timeline.slots.size, 42L))
    }

    @Test fun mediaUriAndSlotIdentityCannotBeSubstituted() {
        val list = listOf(media(1, 1728000000000L), media(2, 1727913600000L))
        val timeline = index(list)
        val pending = PendingViewerReturn(list, list.last().uri, 0, 42L)
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 0, true, 0,
            timeline, timeline.slots.size, 42L))
    }

    @Test fun largestOrUnknownDatesUseCorrectUngroupedPosition() {
        val list = listOf(media(1, 0L), media(2, 0L))
        val timeline = buildGridTimelineIndex(
            list.map { TimelineMedia(it.uri, it.takenAtMillis) },
            TimelineSort.LARGEST, GridDensity.SMALL, ZoneId.of("UTC"), Locale.ROOT)
        val pending = PendingViewerReturn(list, list[1].uri, 0, 42L)
        assertEquals(1, admittedTimelineReturnSlotOrNull(pending, list, 0, true,
            1, timeline, timeline.slots.size, 42L))
    }

    @Test fun largeInventedPhotoCatalogueMapsWithoutHeaderDrift() {
        // One dense synthetic day proves 150k indexing without allocating
        // another 150k fake header objects in the ordinary JVM test heap.
        val list = (0 until 150_000).map { i ->
            media(i, 1728000000000L)
        }
        val timeline = index(list)
        val pending = PendingViewerReturn(list, list.last().uri, 0, 42L)
        assertEquals(timeline.entryForMedia(list.lastIndex),
            admittedTimelineReturnSlotOrNull(pending, list, 0, true,
                list.lastIndex, timeline, timeline.slots.size, 42L))
        assertNull(admittedTimelineReturnSlotOrNull(pending, list, 0, true,
            list.lastIndex, timeline, list.size, 42L))
    }

    @Test fun elevenHundredDistinctDateHeadersDoNotShiftMediaReturn() {
        val list = (0 until 1_100).map { i ->
            media(i, 1728000000000L - i * 86_400_000L)
        }
        val timeline = index(list)
        val pending = PendingViewerReturn(list, list.last().uri, 0, 42L)
        assertEquals(2_200, timeline.slots.size)
        assertEquals(2_199, admittedTimelineReturnSlotOrNull(
            pending, list, 0, true, list.lastIndex, timeline, timeline.slots.size, 42L))
    }
}
