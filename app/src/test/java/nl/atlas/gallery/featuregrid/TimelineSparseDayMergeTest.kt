package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** #642: sparse single days merge into one header (spec GRID:T1-T2). */
class TimelineSparseDayMergeTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val medium = GridDensity.entries.first { it.columns == 4 }
    private val futureClock = Clock.fixed(Instant.parse("2027-11-13T11:00:00Z"), zone)
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun media(vararg times: Long) = times.mapIndexed { i, t -> TimelineMedia("m$i", t) }
    private fun headers(items: List<TimelineMedia>, below: Int) =
        buildGridTimelineIndex(items, TimelineSort.NEWEST, medium, zone, Locale.UK,
            mergeSparseDaysBelow = below, clock = futureClock).headers.map { it.header.label to it.header.mediaCount }

    @Test fun sixPhotosOnSixDaysShareOneHeader() {
        val items = media(at(2026, 11, 9), at(2026, 11, 8), at(2026, 11, 6),
            at(2026, 11, 5), at(2026, 11, 4), at(2026, 11, 3))
        assertEquals(listOf("3–9 Nov 2026" to 6), headers(items, 4))
        // Off by default: one header per day, as on the Photos timeline.
        assertEquals(6, headers(items, 0).size)
    }

    @Test fun aFullRowDayKeepsItsOwnHeaderAndSplitsTheRun() {
        val items = media(at(2026, 11, 9), at(2026, 11, 8),
            at(2026, 11, 7, 12), at(2026, 11, 7, 11), at(2026, 11, 7, 10), at(2026, 11, 7, 9),
            at(2026, 11, 5), at(2026, 11, 4))
        assertEquals(listOf("8–9 Nov 2026" to 2, "7 Nov 2026" to 4, "4–5 Nov 2026" to 2),
            headers(items, 4))
    }

    @Test fun monthsNeverMergeAndASingleSparseDayKeepsItsDate() {
        val items = media(at(2026, 11, 2), at(2026, 11, 1), at(2026, 10, 31), at(2026, 9, 30))
        val got = headers(items, 4)
        assertEquals(listOf(2, 1, 1), got.map { it.second })
        assertEquals("1\u20132 Nov 2026", got[0].first)
        assertEquals("31 Oct 2026", got[1].first)
    }

    @Test fun mergedHeaderKeysStayUnique() {
        val items = media(at(2026, 11, 9), at(2026, 11, 8), at(2026, 10, 9), at(2026, 10, 8))
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, medium, zone, Locale.UK,
            mergeSparseDaysBelow = 4)
        val keys = index.slots.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(2, index.headers.size)
    }
}
