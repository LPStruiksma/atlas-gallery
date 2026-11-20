package nl.atlas.gallery.featuregrid

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fraction-to-media mapping coverage without Android or a second media index. */
class TimelineFastScrollPlanTest {
    private val zone = ZoneId.of("UTC")

    @Test fun emptyAndSingleItemBoundaries() {
        val empty = buildGridTimelineIndex(emptyList(), TimelineSort.NEWEST, GridDensity.MEDIUM, zone)
        assertNull(TimelineFastScrollPlan(empty).entryAtFraction(0f))
        assertNull(TimelineFastScrollPlan(empty).entryAtFraction(1f))
        val single = buildGridTimelineIndex(listOf(TimelineMedia("one", 1_000L)), TimelineSort.NEWEST, GridDensity.MEDIUM, zone)
        val plan = TimelineFastScrollPlan(single)
        for (fraction in listOf(-100f, 0f, .5f, 1f, 100f)) {
            assertEquals(single.entryForMedia(0), plan.entryAtFraction(fraction))
        }
    }

    @Test fun nonFiniteFractionsAreRejectedAndFiniteValuesClamped() {
        val index = buildGridTimelineIndex(List(3) { TimelineMedia("$it", 3_000L - it * 1_000L) },
            TimelineSort.NEWEST, GridDensity.MEDIUM, zone)
        val plan = TimelineFastScrollPlan(index)
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(plan.entryAtFraction(fraction))
        }
        assertEquals(index.entryForMedia(0), plan.entryAtFraction(Float.NEGATIVE_INFINITY.coerceAtLeast(-Float.MAX_VALUE)))
        assertEquals(index.entryForMedia(2), plan.entryAtFraction(Float.MAX_VALUE))
        assertEquals(index.entryForMedia(1), plan.entryAtFraction(.5f))
    }

    @Test fun quarterMillionGroupedHeadersAndFlatSortsMapOnlyToMedia() {
        val count = 250_000
        // One media per UTC day produces 250k headers in the date-grouped case.
        val data = List(count) { i -> TimelineMedia("m$i", 1_800_000_000_000L - i.toLong() * 86_400_000L) }
        for (sort in listOf(TimelineSort.NEWEST, TimelineSort.OLDEST, TimelineSort.LARGEST,
                TimelineSort.SMALLEST, TimelineSort.RANDOM)) {
            val source = if (sort == TimelineSort.OLDEST) data.asReversed() else data
            val index = buildGridTimelineIndex(source, sort, GridDensity.MEDIUM, zone)
            val plan = TimelineFastScrollPlan(index)
            assertEquals(count, index.mediaCount)
            assertEquals(sort == TimelineSort.NEWEST || sort == TimelineSort.OLDEST, index.grouped)
            assertEquals(index.entryForMedia(0), plan.entryAtFraction(0f))
            assertEquals(index.entryForMedia(count - 1), plan.entryAtFraction(1f))
            var previous = -1
            for (step in 0..1_000) {
                val fraction = step / 1_000f
                val entry = requireNotNull(plan.entryAtFraction(fraction))
                assertTrue("non-monotone target for $sort at $step", entry >= previous)
                val slot = index.slots[entry] as TimelineSlot.Media
                val expectedMedia = ((count - 1).toDouble() * fraction).toInt()
                assertTrue("fraction target too far from media rank", kotlin.math.abs(slot.mediaIndex - expectedMedia) <= 1)
                previous = entry
            }
        }
    }
}
