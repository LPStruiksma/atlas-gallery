package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimelineScrubTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val futureClock = Clock.fixed(Instant.parse("2027-05-01T12:00:00Z"), zone)
    private fun time(day: Int): Long = Instant.parse("2026-10-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli()

    @Test fun emptyAndSingleMediaScrubSafely() {
        val empty = buildGridTimelineIndex(emptyList(), TimelineSort.NEWEST, GridDensity.MEDIUM, zone)
        assertNull(empty.mediaIndexAtOrBeforeSlot(0))
        assertNull(empty.scrubTarget(.5f))
        assertEquals(0f, empty.fractionForSlot(0), 0f)
        val single = buildGridTimelineIndex(listOf(TimelineMedia("one", time(10))),
            TimelineSort.NEWEST, GridDensity.LARGE, zone)
        assertEquals(0, single.scrubTarget(0.95f)!!.mediaIndex)
        assertEquals(1, single.scrubTarget(0.95f)!!.slotIndex)
        assertEquals(0f, single.fractionForSlot(1), 0f)
    }

    @Test fun headerBelongsToItsFollowingMediaAndScrubEndPointsAreExact() {
        val samples = listOf(TimelineMedia("a", time(22)), TimelineMedia("b", time(22)),
            TimelineMedia("c", time(21)), TimelineMedia("d", time(18)))
        val index = buildGridTimelineIndex(samples, TimelineSort.NEWEST,
            GridDensity.MEDIUM, zone, Locale.UK, clock = futureClock)
        assertEquals(listOf(1,2,4,6), samples.indices.map(index::entryForMedia))
        assertEquals(0, index.mediaIndexAtOrBeforeSlot(0))
        assertEquals(2, index.mediaIndexAtOrBeforeSlot(3))
        assertEquals(3, index.mediaIndexAtOrBeforeSlot(5))
        assertEquals(0, index.scrubTarget(0f)!!.mediaIndex)
        assertEquals(3, index.scrubTarget(1f)!!.mediaIndex)
        assertEquals("18 Oct 2026", index.scrubTarget(1f)!!.dateLabel)
        assertEquals(1f, index.fractionForSlot(6), 0f)
        assertNull(index.scrubTarget(Float.NaN))
    }

    @Test fun randomSortNeverFabricatesAChronologicalDate() {
        val index = buildGridTimelineIndex(listOf(TimelineMedia("a", 1),
            TimelineMedia("b", 1000), TimelineMedia("c", 4)),
            TimelineSort.RANDOM, GridDensity.SMALL, zone)
        assertEquals(1, index.scrubTarget(.5f)!!.mediaIndex)
        assertNull(index.scrubTarget(.5f)!!.dateLabel)
        assertEquals(1, index.mediaIndexAtOrBeforeSlot(1))
    }

    @Test fun mixedHeaderSizesSupport150kScrubsWithoutLinearScanning() {
        val samples = List(150_000) { i -> TimelineMedia("photo:$i", time(22) - i.toLong() * 60_000) }
        val index = buildGridTimelineIndex(samples, TimelineSort.NEWEST,
            GridDensity.MEDIUM, zone)
        for (fraction in listOf(0f, .01f, .15f, .4f, .5f, .78f, .99f, 1f)) {
            val t = index.scrubTarget(fraction)!!
            assertEquals(t.mediaIndex, index.mediaIndexAtOrBeforeSlot(t.slotIndex))
            assertTrue(kotlin.math.abs(index.fractionForSlot(t.slotIndex) - fraction) < .00002f)
            assertTrue(t.dateLabel != null)
        }
        assertEquals(149_999, index.scrubTarget(20f)!!.mediaIndex)
        assertEquals(0, index.scrubTarget(-20f)!!.mediaIndex)
    }

    @Test fun liveScrubFollowsTheThumbOnlyOnTheMountedTimeline() {
        val samples = listOf(TimelineMedia("a", time(22)), TimelineMedia("b", time(22)),
            TimelineMedia("c", time(21)), TimelineMedia("d", time(18)))
        val dated = buildGridTimelineIndex(samples, TimelineSort.NEWEST,
            GridDensity.MEDIUM, zone, Locale.UK)
        // Live slot equals the release slot, so release never changes what is shown.
        for (f in listOf(0f, .3f, .5f, .7f, 1f)) {
            assertEquals(dated.scrubTarget(f)!!.slotIndex,
                dated.liveScrubSlotOrNull(f, dated.slots.size))
        }
        assertNull(dated.liveScrubSlotOrNull(.5f, dated.slots.size - 1))
        assertNull(dated.liveScrubSlotOrNull(.5f, 0))
        assertNull(dated.liveScrubSlotOrNull(Float.NaN, dated.slots.size))
        val random = buildGridTimelineIndex(List(500) { TimelineMedia("r$it", it.toLong()) },
            TimelineSort.RANDOM, GridDensity.MEDIUM, zone)
        // Random has no headers: the live slot is the media position itself, monotone in f.
        val slots = (0..10).map { random.liveScrubSlotOrNull(it / 10f, 500)!! }
        assertEquals(slots.sorted(), slots)
        assertEquals(0, slots.first())
        assertEquals(499, slots.last())
    }
}
