package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/**
 * Independent synthetic contracts for UTC ordering versus historical local-calendar rollbacks.
 * This is deliberately test-only: production TimelineIndex.kt belongs to DAILY-5 (#289).
 */
class TimelineTimeZoneAdversarialTest {
    private val historicalZone = ZoneId.of("America/St_Johns")
    private val fixedClock = Clock.fixed(Instant.parse("2027-03-01T12:00:00Z"), ZoneOffset.UTC)
    private fun millis(utc: String): Long = Instant.parse(utc).toEpochMilli()

    // Historical local midnight moved backwards in Newfoundland in 1987:
    // UTC remains ascending while local calendar dates repeat in disjoint runs.
    private val rollback = listOf(
        "1987-10-25T02:29:30Z",
        "1987-10-25T02:30:30Z",
        "1987-10-25T02:31:30Z",
        "1987-10-25T03:31:30Z"
    ).mapIndexed { index, utc -> TimelineMedia("rollback:$index", millis(utc)) }

    private fun index(
        source: List<TimelineMedia>,
        sort: TimelineSort,
        density: GridDensity,
        zone: ZoneId = historicalZone,
        locale: Locale = Locale.UK,
        clock: Clock = fixedClock,
        checkpoint: (() -> Unit)? = null
    ) = buildGridTimelineIndex(source, sort, density, zone, locale,
        mergeSparseDaysBelow = 0, clock = clock, cancellationCheckpoint = checkpoint)

    @Test fun rollbackCreatesFourDistinctDayRunsInBothChronologicalDirections() {
        for ((sort, source, expected) in listOf(
            Triple(TimelineSort.OLDEST, rollback, listOf("24", "25", "24", "25")),
            Triple(TimelineSort.NEWEST, rollback.reversed(), listOf("25", "24", "25", "24"))
        )) {
            val result = index(source, sort, GridDensity.MEDIUM)
            assertTrue(result.grouped)
            assertEquals(expected.map { "$it Oct 1987" }, result.headers.map { it.header.label })
            val keys = result.headers.map { it.header.stableKey }
            assertEquals(4, keys.toSet().size)
            assertEquals("header:DAY:1987-10-" + expected[0], keys.first())
            assertTrue(keys[2].endsWith("@2"))
            assertTrue(keys[3].endsWith("@3"))
            assertEquals(listOf(1, 3, 5, 7), source.indices.map(result::entryForMedia))
            assertEquals(source.indices.toList(), result.slots.filterIsInstance<TimelineSlot.Media>().map { it.mediaIndex })
            for (i in source.indices)
                assertEquals("${expected[i]} Oct 1987", result.bubbleForEntry(result.entryForMedia(i)))
        }
    }

    @Test fun monthAndYearOverviewsKeepOneHeaderForTheSameRollback() {
        for ((density, key, label) in listOf(
            Triple(GridDensity.MONTH, "header:MONTH:1987-10", "October 1987"),
            Triple(GridDensity.YEAR, "header:YEAR:1987", "1987")
        )) {
            assertTrue(density.columns == 12 || density.columns == 28)
            for ((sort, source) in listOf(
                TimelineSort.OLDEST to rollback,
                TimelineSort.NEWEST to rollback.reversed()
            )) {
                val result = index(source, sort, density)
                assertTrue(result.grouped)
                assertEquals(listOf(key), result.headers.map { it.header.stableKey })
                assertEquals(label, result.headers.single().header.label)
                assertEquals(5, result.slots.size)
                assertEquals(listOf(1, 2, 3, 4), source.indices.map(result::entryForMedia))
                assertEquals(source.indices.toList(), result.slots.filterIsInstance<TimelineSlot.Media>().map { it.mediaIndex })
            }
        }
    }

    @Test fun zoneLocaleAndClockAreInjectedRatherThanReadFromSystemDefaults() {
        val source = listOf(
            TimelineMedia("new-year", millis("2026-12-31T23:30:00Z")),
            TimelineMedia("old-year", millis("2026-12-31T22:30:00Z"))
        )
        val amsterdam = index(source, TimelineSort.NEWEST, GridDensity.MEDIUM,
            ZoneId.of("Europe/Amsterdam"), Locale.UK,
            Clock.fixed(Instant.parse("2027-01-04T00:00:00Z"), ZoneOffset.UTC))
        val utc = index(source, TimelineSort.NEWEST, GridDensity.MEDIUM,
            ZoneOffset.UTC, Locale.UK,
            Clock.fixed(Instant.parse("2027-01-04T00:00:00Z"), ZoneOffset.UTC))
        val dutch = index(source, TimelineSort.NEWEST, GridDensity.MEDIUM,
            ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"),
            Clock.fixed(Instant.parse("2027-01-04T00:00:00Z"), ZoneOffset.UTC))
        assertEquals(listOf("header:DAY:2027-01-01", "header:DAY:2026-12-31"),
            amsterdam.headers.map { it.header.stableKey })
        assertEquals(listOf("1 Jan", "31 Dec 2026"), amsterdam.headers.map { it.header.label })
        assertEquals(listOf("header:DAY:2026-12-31"), utc.headers.map { it.header.stableKey })
        assertEquals(listOf("1 jan.", "31 dec. 2026"), dutch.headers.map { it.header.label })
        assertEquals(listOf(1, 3), source.indices.map(amsterdam::entryForMedia))
    }

    @Test fun zeroAndExtremeEpochValuesRemainDistinctWithoutReordering() {
        val source = listOf(
            TimelineMedia("maximum", Long.MAX_VALUE),
            TimelineMedia("regular", millis("2026-01-01T00:00:00Z")),
            TimelineMedia("unknown", 0L),
            TimelineMedia("minimum", Long.MIN_VALUE)
        )
        for (density in listOf(GridDensity.MEDIUM, GridDensity.MONTH, GridDensity.YEAR)) {
            val result = index(source, TimelineSort.NEWEST, density,
                ZoneOffset.UTC, Locale.UK)
            assertTrue(result.grouped)
            assertEquals(4, result.headers.size)
            assertEquals(4, result.headers.map { it.header.stableKey }.toSet().size)
            assertEquals("Unknown date", result.headers[2].header.label)
            assertTrue(result.headers[2].header.stableKey.endsWith(":unknown"))
            assertEquals(listOf(1, 3, 5, 7), source.indices.map(result::entryForMedia))
            assertEquals(source.indices.toList(), result.slots.filterIsInstance<TimelineSlot.Media>().map { it.mediaIndex })
        }
    }

    @Test fun contradictoryChronologicalMetadataFailsClosedWithoutSortingOrHeaders() {
        val wrong = listOf(
            TimelineMedia("first", 5L), TimelineMedia("second", 6L),
            TimelineMedia("third", 2L)
        )
        for (density in listOf(GridDensity.MEDIUM, GridDensity.MONTH, GridDensity.YEAR)) {
            val result = index(wrong, TimelineSort.NEWEST, density)
            assertFalse(result.grouped)
            assertTrue(result.headers.isEmpty())
            assertEquals(listOf(0, 1, 2), wrong.indices.map(result::entryForMedia))
            assertEquals(listOf("media:first", "media:second", "media:third"),
                result.slots.map { it.stableKey })
            assertNull(result.bubbleForEntry(0))
        }
    }

    @Test fun twoHundredFiftyThousandSameDayEntriesAreCancellableWithinScan() {
        val base = millis("2026-10-22T12:00:00Z")
        val source = List(250_000) { i -> TimelineMedia("synthetic:$i", base - i.toLong()) }
        var called = 0
        try {
            index(source, TimelineSort.NEWEST, GridDensity.MEDIUM, ZoneOffset.UTC,
                checkpoint = {
                    called++
                    if (called == 20) throw IllegalStateException("cancelled-generation")
                })
            fail("expected an early cancellation checkpoint")
        } catch (expected: IllegalStateException) {
            assertEquals("cancelled-generation", expected.message)
        }
        assertEquals(20, called)
        // A separate complete 250k index validates media mapping and unique keys.
        val complete = index(source, TimelineSort.NEWEST, GridDensity.YEAR, ZoneOffset.UTC)
        assertTrue(complete.grouped)
        assertEquals(250_000, complete.mediaCount)
        assertEquals(1, complete.headers.size)
        assertEquals(250_001, complete.slots.size)
        for (i in listOf(0, 1, 1024, 125_000, 249_999)) {
            val entry = complete.entryForMedia(i)
            assertEquals("media:synthetic:$i", complete.slots[entry].stableKey)
            assertEquals(i, (complete.slots[entry] as TimelineSlot.Media).mediaIndex)
        }
        assertEquals(complete.entryForMedia(249_999), complete.entryAtFraction(1f))
    }
}
