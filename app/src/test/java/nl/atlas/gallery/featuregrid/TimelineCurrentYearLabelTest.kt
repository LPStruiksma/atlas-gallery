package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** #682: Samsung-style dates use the device-local current calendar year. */
class TimelineCurrentYearLabelTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val locale = Locale.UK

    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(amsterdam)
            .toInstant().toEpochMilli()

    private val clock2026 = Clock.fixed(Instant.parse("2026-11-13T11:00:00Z"), amsterdam)

    @Test fun dayMonthAndFastScrollUseShortCurrentYearButKeepOlderYear() {
        val items = listOf(
            TimelineMedia("now", at(2026, 11, 12)),
            TimelineMedia("old", at(2025, 11, 12))
        )
        val days = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM,
            amsterdam, locale, clock = clock2026)
        assertEquals(listOf("12 Nov", "12 Nov 2025"),
            days.headers.map { it.header.label })
        assertEquals("12 Nov", days.bubbleForEntry(days.entryForMedia(0)))
        assertEquals("12 Nov 2025", days.bubbleForEntry(days.entryForMedia(1)))
        assertEquals(listOf("header:DAY:2026-11-12", "header:DAY:2025-11-12"),
            days.headers.map { it.header.stableKey })

        val months = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.SMALL,
            amsterdam, locale, clock = clock2026)
        assertEquals(listOf("November", "November 2025"),
            months.headers.map { it.header.label })
        assertEquals("November", months.bubbleForEntry(months.entryForMedia(0)))
        assertEquals("November 2025", months.bubbleForEntry(months.entryForMedia(1)))
    }

    @Test fun sparsePersonPageRangesUseYearOfTheRange() {
        val items = listOf(
            TimelineMedia("now9", at(2026, 11, 9)),
            TimelineMedia("now8", at(2026, 11, 8)),
            TimelineMedia("old10", at(2025, 11, 10)),
            TimelineMedia("old9", at(2025, 11, 9))
        )
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM,
            amsterdam, locale, mergeSparseDaysBelow = 4, clock = clock2026)
        assertEquals(listOf("8–9 Nov", "9–10 Nov 2025"),
            index.headers.map { it.header.label })
        assertEquals(listOf(2, 2), index.headers.map { it.header.mediaCount })
        assertEquals("8–9 Nov", index.bubbleForEntry(index.entryForMedia(1)))
    }

    @Test fun newYearBoundaryKeepsBucketsSeparateAndUsesEachYear() {
        val clock2027 = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), amsterdam)
        val items = listOf(
            TimelineMedia("jan", at(2027, 1, 1)),
            TimelineMedia("dec", at(2026, 12, 31))
        )
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM,
            amsterdam, locale, mergeSparseDaysBelow = 4, clock = clock2027)
        assertEquals(listOf("1 Jan", "31 Dec 2026"), index.headers.map { it.header.label })
        assertEquals(listOf("header:DAY:2027-01-01", "header:DAY:2026-12-31"),
            index.headers.map { it.header.stableKey })
        assertEquals(listOf(1, 1), index.headers.map { it.header.mediaCount })
    }

    @Test fun utcNewYearDoesNotOverrideDeviceTimeZoneYear() {
        val instant = Instant.parse("2027-01-01T00:30:00Z")
        val clock = Clock.fixed(instant, ZoneId.of("UTC"))
        val items = listOf(
            TimelineMedia("jan", Instant.parse("2027-01-01T12:00:00Z").toEpochMilli()),
            TimelineMedia("dec", Instant.parse("2026-12-31T12:00:00Z").toEpochMilli())
        )
        val losAngeles = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM,
            ZoneId.of("America/Los_Angeles"), locale, clock = clock)
        assertEquals(listOf("1 Jan 2027", "31 Dec"),
            losAngeles.headers.map { it.header.label })
        val amsterdamIndex = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.MEDIUM,
            amsterdam, locale, clock = clock)
        assertEquals(listOf("1 Jan", "31 Dec 2026"),
            amsterdamIndex.headers.map { it.header.label })
    }
}
