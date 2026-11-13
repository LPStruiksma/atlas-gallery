package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimelineBucketEfficiencyTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val futureClock = Clock.fixed(Instant.parse("2027-05-01T12:00:00Z"), zone)
    private val start = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()

    @Test fun hundredFiftyThousandSameDayMediaNeedOnlyOneDayHeader() {
        val source = List(150_000) { i -> TimelineMedia("uri:$i", start - i.toLong()) }
        for (density in listOf(GridDensity.LARGE, GridDensity.MEDIUM, GridDensity.SMALL)) {
            val index = buildGridTimelineIndex(source, TimelineSort.NEWEST, density, zone, Locale.UK, clock = futureClock)
            assertTrue(index.grouped)
            assertEquals(1, index.headers.size)
            assertEquals(150_001, index.slots.size)
            assertEquals(150_000, index.mediaCount)
            assertEquals(150_000, index.entryForMedia(149_999))
            assertEquals("22 Oct 2026".takeIf { density != GridDensity.SMALL } ?: "October 2026",
                index.bubbleForEntry(index.entryForMedia(100_000)))
        }
    }

    @Test fun localCalendarAndKeyContractHoldAcrossDstAndYearChange() {
        val source = listOf(
            TimelineMedia("a", Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()),
            TimelineMedia("b", Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()),
            TimelineMedia("c", Instant.parse("2026-10-24T00:30:00Z").toEpochMilli()),
            TimelineMedia("d", Instant.parse("2025-12-31T23:30:00Z").toEpochMilli())
        )
        val days = buildGridTimelineIndex(source, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, Locale.UK, clock = futureClock)
        assertEquals(3, days.headers.size)
        assertEquals(listOf("25 Oct 2026", "24 Oct 2026", "1 Jan 2026"),
            days.headers.map { it.header.label })
        assertEquals(listOf("header:DAY:2026-10-25", "header:DAY:2026-10-24", "header:DAY:2026-01-01"),
            days.headers.map { it.header.stableKey })
        assertEquals(listOf(1, 2, 4, 6), source.indices.map(days::entryForMedia))
        val months = buildGridTimelineIndex(source, TimelineSort.NEWEST, GridDensity.SMALL, zone, Locale.UK)
        assertEquals(listOf("header:MONTH:2026-10", "header:MONTH:2026-01"),
            months.headers.map { it.header.stableKey })
    }

    @Test fun unknownDateAndUnsortedCataloguesRemainFailClosed() {
        val source = listOf(TimelineMedia("known", start), TimelineMedia("unknown", 0L))
        val days = buildGridTimelineIndex(source, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, Locale.UK, clock = futureClock)
        assertEquals("Unknown date", days.headers.last().header.label)
        assertEquals("header:DAY:unknown", days.headers.last().header.stableKey)
        val unsorted = buildGridTimelineIndex(source.reversed(), TimelineSort.NEWEST,
            GridDensity.MEDIUM, zone, Locale.UK)
        assertFalse(unsorted.grouped)
        assertTrue(unsorted.headers.isEmpty())
        for (sort in listOf(TimelineSort.RANDOM, TimelineSort.LARGEST)) {
            assertFalse(buildGridTimelineIndex(source, sort, GridDensity.SMALL, zone).grouped)
        }
    }

    @Test fun cancellationInsideSingle150kDayStillStopsDuringBucketScan() {
        val source = List(150_000) { i -> TimelineMedia("photo:$i", start - i.toLong()) }
        var checkpoints = 0
        try {
            buildGridTimelineIndex(source, TimelineSort.NEWEST, GridDensity.MEDIUM, zone, Locale.UK, clock = futureClock) {
                checkpoints++
                if (checkpoints == 120) throw IllegalStateException("superseded")
            }
            throw AssertionError("expected early cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("superseded", expected.message)
        }
        assertEquals(120, checkpoints)
    }
}
