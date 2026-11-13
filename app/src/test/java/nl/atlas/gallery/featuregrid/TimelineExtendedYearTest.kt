package nl.atlas.gallery.featuregrid

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimelineExtendedYearTest {
    private val monthMillis = 45L * 24 * 60 * 60 * 1_000
    private val zone = ZoneOffset.UTC

    @Test fun enormousPositiveYearsRetainMonthIdentityWithoutTruncation() {
        val source = listOf(
            TimelineMedia("aug", Long.MAX_VALUE),
            TimelineMedia("jul", Long.MAX_VALUE - monthMillis)
        )
        val index = buildGridTimelineIndex(
            source, TimelineSort.NEWEST, GridDensity.SMALL, zone, Locale.UK)
        assertEquals(listOf("header:MONTH:+292278994-08", "header:MONTH:+292278994-07"),
            index.headers.map { it.header.stableKey })
        assertEquals(2, index.headers.size)
        assertEquals(4, index.slots.size)
    }

    @Test fun enormousNegativeYearsRetainTheirSignAndMonthIdentity() {
        val source = listOf(
            TimelineMedia("may", Long.MIN_VALUE),
            TimelineMedia("june", Long.MIN_VALUE + monthMillis)
        )
        val index = buildGridTimelineIndex(
            source, TimelineSort.OLDEST, GridDensity.SMALL, zone, Locale.UK)
        assertEquals(listOf("header:MONTH:-292275055-05", "header:MONTH:-292275055-06"),
            index.headers.map { it.header.stableKey })
        assertEquals(2, index.headers.size)
        assertEquals(4, index.slots.size)
    }

    @Test fun ordinaryMonthKeysStayBackwardCompatible() {
        val source = listOf(
            TimelineMedia("a", 1_760_000_000_000L),
            TimelineMedia("b", 1_700_000_000_000L)
        )
        val index = buildGridTimelineIndex(
            source, TimelineSort.NEWEST, GridDensity.SMALL, zone, Locale.UK)
        assertEquals(listOf("header:MONTH:2025-10", "header:MONTH:2023-11"),
            index.headers.map { it.header.stableKey })
        assertEquals(2, index.headers.size)
    }
}
