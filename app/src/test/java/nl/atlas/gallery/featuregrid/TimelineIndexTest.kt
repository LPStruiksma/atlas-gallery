package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimelineIndexTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val locale = Locale.UK
    private val futureClock = Clock.fixed(Instant.parse("2027-05-01T12:00:00Z"), zone)
    private fun stamp(s: String): Long = Instant.parse(s).toEpochMilli()

    @Test fun groupedDayPositionsPreserveAllMediaKeysAndHeaderSpans() {
        val items = listOf(
            TimelineMedia("uri://3", stamp("2026-10-22T22:20:00Z")),
            TimelineMedia("uri://2", stamp("2026-10-22T14:20:00Z")),
            TimelineMedia("uri://1", stamp("2026-10-21T13:00:00Z"))
        )
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, locale, clock = futureClock)
        assertTrue(index.grouped)
        // First timestamp is after Dutch midnight and therefore a different day.
        assertEquals(3, index.headers.size)
        assertEquals(6, index.slots.size)
        assertEquals(listOf(1, 3, 5), items.indices.map(index::entryForMedia))
        assertEquals("uri://2", (index.slots[index.entryForMedia(1)] as TimelineSlot.Media).stableKey.removePrefix("media:"))
        assertEquals("23 Oct 2026", index.bubbleForEntry(1))
        assertEquals("22 Oct 2026", index.bubbleForEntry(3))
        assertEquals("21 Oct 2026", index.bubbleForEntry(5))
    }

    @Test fun sevenColumnModeUsesMonthAndAvoidsExtraDayHeaders() {
        val items = listOf(
            TimelineMedia("a", stamp("2026-10-22T19:00:00Z")),
            TimelineMedia("b", stamp("2026-10-02T19:00:00Z")),
            TimelineMedia("c", stamp("2026-09-02T19:00:00Z"))
        )
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.SMALL, zone, locale, clock = futureClock)
        assertEquals(2, index.headers.size)
        assertEquals(listOf(1, 2, 4), items.indices.map(index::entryForMedia))
        assertEquals("October 2026", index.bubbleForEntry(2))
        assertEquals("September 2026", index.bubbleForEntry(4))
    }

    @Test fun largestAndRandomNeverReceiveFabricatedChronologicalHeaders() {
        val items = listOf(TimelineMedia("b", 10), TimelineMedia("a", 5000), TimelineMedia("z", 3))
        for (sort in listOf(TimelineSort.LARGEST, TimelineSort.RANDOM)) {
            val index = buildGridTimelineIndex(items, sort, GridDensity.MEDIUM, zone)
            assertTrue(!index.grouped)
            assertEquals(0, index.headers.size)
            assertEquals(listOf(0, 1, 2), items.indices.map(index::entryForMedia))
            assertEquals(null, index.bubbleForEntry(1))
        }
    }

    @Test fun staleChronologicalSortFailsClosedToUngrouped() {
        val invalid = listOf(TimelineMedia("a", 1000), TimelineMedia("b", 2000), TimelineMedia("c", 800))
        val index = buildGridTimelineIndex(invalid, TimelineSort.NEWEST, GridDensity.LARGE, zone)
        assertTrue(!index.grouped)
        assertEquals(listOf(0, 1, 2), invalid.indices.map(index::entryForMedia))
    }

    @Test fun unknownDatesAreIsolatedNotPresentedAsJanuary1970() {
        val items = listOf(TimelineMedia("real", stamp("2021-04-05T12:00:00Z")), TimelineMedia("unknown", 0L))
        val idx = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, locale, clock = futureClock)
        assertEquals(2, idx.headers.size)
        assertEquals("Unknown date", idx.bubbleForEntry(idx.entryForMedia(1)))
    }

    @Test fun scrubFractionsTargetMediaNotDividerSlots() {
        val items = List(36) { i -> TimelineMedia("key:$i", stamp("2026-10-22T12:00:00Z") - i * 86_400_000L) }
        val idx = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, locale, clock = futureClock)
        assertEquals(idx.entryForMedia(0), idx.entryAtFraction(0f))
        assertEquals(idx.entryForMedia(35), idx.entryAtFraction(1f))
        assertEquals(idx.entryForMedia(18), idx.entryAtFraction(.5f))
        assertEquals(null, idx.entryAtFraction(Float.NaN))
        assertEquals(idx.entryForMedia(0), idx.entryAtFraction(-20f))
        assertEquals(idx.entryForMedia(35), idx.entryAtFraction(20f))
        assertEquals(null, buildGridTimelineIndex(emptyList(), TimelineSort.OLDEST, GridDensity.SMALL, zone).entryAtFraction(.5f))
    }

    @Test fun enormousSyntheticArchiveKeepsStablePositionMapping() {
        val origin = stamp("2026-10-22T12:00:00Z")
        val source = List(150_000) { i -> TimelineMedia("content://media/x/$i", origin - i.toLong() * 60_000L) }
        val idx = buildGridTimelineIndex(source, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, locale)
        assertEquals(150_000, idx.mediaCount)
        assertTrue(idx.grouped)
        for (mediaIndex in 0 until source.size step 719) {
            val slot = idx.slots[idx.entryForMedia(mediaIndex)] as TimelineSlot.Media
            assertEquals(mediaIndex, slot.mediaIndex)
            assertEquals("media:" + source[mediaIndex].key, slot.stableKey)
            assertTrue(idx.bubbleForEntry(idx.entryForMedia(mediaIndex)) != null)
        }
        assertEquals(idx.entryForMedia(149_999), idx.entryAtFraction(1f))
    }
}
