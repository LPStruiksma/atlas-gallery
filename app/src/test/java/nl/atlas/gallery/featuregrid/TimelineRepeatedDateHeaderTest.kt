package nl.atlas.gallery.featuregrid

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimelineRepeatedDateHeaderTest {
    private val zone = ZoneId.of("America/St_Johns")

    // 1987-10-25 00:01 local clock jumped back to 1987-10-24 23:01.
    // Valid UTC chronological source order therefore has October 24/25/24/25
    // in *local* dates. Older gallery implementations generated duplicate
    // LazyGrid header keys and could crash when all four runs were rendered.
    private val ascending = listOf(
        "1987-10-25T02:29:30Z",
        "1987-10-25T02:30:30Z",
        "1987-10-25T02:31:30Z",
        "1987-10-25T03:31:30Z"
    ).mapIndexed { i, time ->
        TimelineMedia("historical:$i", Instant.parse(time).toEpochMilli())
    }

    @Test fun oldestHasUniqueHeaderKeysDespiteRepeatedLocalDates() {
        val index = buildGridTimelineIndex(
            ascending, TimelineSort.OLDEST, GridDensity.MEDIUM, zone, Locale.UK)
        val headers = index.headers.map { it.header }
        assertEquals(listOf("24 Oct 1987", "25 Oct 1987", "24 Oct 1987", "25 Oct 1987"),
            headers.map { it.label })
        assertEquals(listOf(
            "header:DAY:1987-10-24", "header:DAY:1987-10-25",
            "header:DAY:1987-10-24@2", "header:DAY:1987-10-25@3"
        ), headers.map { it.stableKey })
        assertEquals(4, headers.map { it.stableKey }.toSet().size)
        assertEquals(listOf(1, 3, 5, 7), ascending.indices.map(index::entryForMedia))
        assertEquals("24 Oct 1987", index.bubbleForEntry(index.entryForMedia(2)))
    }

    @Test fun newestAndMonthGroupingPreserveMediaIdentityAndExistingKeys() {
        val newest = buildGridTimelineIndex(ascending.reversed(), TimelineSort.NEWEST,
            GridDensity.LARGE, zone, Locale.UK)
        assertEquals(listOf(
            "header:DAY:1987-10-25", "header:DAY:1987-10-24",
            "header:DAY:1987-10-25@2", "header:DAY:1987-10-24@3"
        ), newest.headers.map { it.header.stableKey })
        assertEquals(8, newest.slots.size)
        val months = buildGridTimelineIndex(ascending, TimelineSort.OLDEST,
            GridDensity.SMALL, zone, Locale.UK)
        assertEquals(listOf("header:MONTH:1987-10"),
            months.headers.map { it.header.stableKey })
        assertEquals(5, months.slots.size)
    }

    @Test fun oneHundredFiftyThousandChronologicalRecordsHaveNoDuplicateHeaderKeys() {
        val base = Instant.parse("1987-10-24T03:00:00Z").toEpochMilli()
        val source = List(150_000) { i -> TimelineMedia("uri:$i", base + i * 1_000L) }
        val index = buildGridTimelineIndex(source, TimelineSort.OLDEST,
            GridDensity.MEDIUM, zone, Locale.UK)
        val allKeys = index.slots.map { it.stableKey }
        assertEquals(allKeys.size, allKeys.toSet().size)
        assertEquals(source.size, index.mediaCount)
        assertEquals(0, index.entryForMedia(0) - 1)
        assertEquals(index.slots.lastIndex, index.entryForMedia(source.lastIndex))
        assertTrue(index.headers.size >= 4)
    }

    @Test fun ordinaryDatesRetainTheirOriginalUnadornedStableKeys() {
        val source = listOf(
            TimelineMedia("one", Instant.parse("2026-09-12T12:00:00Z").toEpochMilli()),
            TimelineMedia("two", Instant.parse("2026-09-13T12:00:00Z").toEpochMilli())
        )
        val index = buildGridTimelineIndex(source, TimelineSort.OLDEST,
            GridDensity.MEDIUM, ZoneId.of("Europe/Amsterdam"), Locale.UK)
        assertEquals(listOf("header:DAY:2026-09-12", "header:DAY:2026-09-13"),
            index.headers.map { it.header.stableKey })
    }
}
