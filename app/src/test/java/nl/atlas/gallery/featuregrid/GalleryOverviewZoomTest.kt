package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** #16: Samsung's two additional overview stops are real grouped timeline levels. */
class GalleryOverviewZoomTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val clock = Clock.fixed(Instant.parse("2028-02-01T12:00:00Z"), zone)
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun sixStopsAreOrderedAndEveryDensityHasAReversibleMapping() {
        assertEquals(listOf(3, 4, 7, 12, 28), GridDensity.entries.map { it.columns })
        assertEquals(listOf(GalleryZoomStop.ORIGINAL, GalleryZoomStop.LARGE,
            GalleryZoomStop.MEDIUM, GalleryZoomStop.SMALL, GalleryZoomStop.MONTH,
            GalleryZoomStop.YEAR), GalleryZoomStop.entries)
        assertEquals(GridDensity.MONTH, GridDensity.fromColumns(12))
        assertEquals(GridDensity.YEAR, GridDensity.fromColumns(28))
        for (density in GridDensity.entries) {
            assertEquals(density, GalleryZoomStop.forDensity(density).squareDensityOrNull())
        }
        val month = GalleryZoomGesture(GalleryZoomStop.SMALL)
        month.addZoomFactor(.82f)
        assertEquals(GalleryZoomStop.MONTH, month.settle())
        val year = GalleryZoomGesture(GalleryZoomStop.MONTH)
        year.addZoomFactor(.82f)
        assertEquals(GalleryZoomStop.YEAR, year.settle())
        val reverse = GalleryZoomGesture(GalleryZoomStop.YEAR)
        reverse.addZoomFactor(1.2f)
        assertEquals(GalleryZoomStop.MONTH, reverse.settle())
        val clamp = GalleryZoomGesture(GalleryZoomStop.YEAR)
        repeat(20) { clamp.addZoomFactor(.8f) }
        assertEquals(GalleryZoomStop.YEAR, clamp.settle())
    }

    @Test fun overviewGroupsUseLocalCalendarMonthAndYearWithoutLosingItems() {
        val source = listOf(
            TimelineMedia("a", time("2027-10-22T23:30:00Z")),
            TimelineMedia("b", time("2027-10-15T12:00:00Z")),
            TimelineMedia("c", time("2027-09-30T12:00:00Z")),
            TimelineMedia("d", time("2026-12-31T23:30:00Z")),
            TimelineMedia("e", 0L)
        )
        val month = buildGridTimelineIndex(source, TimelineSort.NEWEST,
            GridDensity.MONTH, zone, Locale.UK, clock = clock)
        assertEquals(listOf("October 2027", "September 2027", "January 2027", "Unknown date"),
            month.headers.map { it.header.label })
        assertEquals(listOf("header:MONTH:2027-10", "header:MONTH:2027-09",
            "header:MONTH:2027-01", "header:MONTH:unknown"),
            month.headers.map { it.header.stableKey })

        val year = buildGridTimelineIndex(source, TimelineSort.NEWEST,
            GridDensity.YEAR, zone, Locale.UK, clock = clock)
        assertEquals(listOf("2027", "Unknown date"), year.headers.map { it.header.label })
        assertEquals(listOf("header:YEAR:2027", "header:YEAR:unknown"),
            year.headers.map { it.header.stableKey })
        assertEquals(listOf(4, 1), year.headers.map { it.header.mediaCount }) // four known items share the same local year
        assertEquals(source.indices.toList(), source.indices.map {
            (year.slots[year.entryForMedia(it)] as TimelineSlot.Media).mediaIndex
        })
        assertEquals(year.entryForMedia(4), year.entryAtFraction(1f))
    }

    @Test fun overviewOnNonDateSortOrStaleChronologyNeverFabricatesHeaders() {
        val unsorted = listOf(TimelineMedia("b", 1_000L),
            TimelineMedia("a", 3_000L), TimelineMedia("c", 2_000L))
        for (density in listOf(GridDensity.MONTH, GridDensity.YEAR)) {
            for (sort in listOf(TimelineSort.LARGEST, TimelineSort.SMALLEST, TimelineSort.RANDOM,
                TimelineSort.NEWEST)) {
                val index = buildGridTimelineIndex(unsorted, sort, density, zone, Locale.UK)
                assertFalse("$density $sort", index.grouped)
                assertTrue(index.headers.isEmpty())
                assertNull(index.bubbleForEntry(1))
                assertEquals(listOf(0, 1, 2), unsorted.indices.map(index::entryForMedia))
            }
        }
    }

    @Test fun yearGroupingAtScaleKeepsRealFocalMediaAfterHeaderReflow() {
        val first = time("2027-12-31T12:00:00Z")
        val source = List(150_000) { i ->
            TimelineMedia("synthetic:$i", first - i.toLong() * 20L * 60_000L)
        }
        val viewport = GridViewportGeometry(1080, 2340, 9, 9, source.size)
        for (density in listOf(GridDensity.SMALL, GridDensity.MONTH, GridDensity.YEAR)) {
            val index = buildGridTimelineIndex(source, TimelineSort.NEWEST,
                density, zone, Locale.UK, clock = clock)
            val geometry = planTimelineVisualRows(index, density.columns, viewport, 126)
            assertEquals(150_000, index.mediaCount)
            for (focused in listOf(3_000, 75_000, 145_000)) {
                val target = geometry.targetMedia(focused, .6f, 870f)
                assertFalse("$density $focused clipped", target.clampedByBounds)
                val slotIndex = index.entryForMedia(focused)
                val targetRow = geometry.rows.last { it.firstSlotIndex <= target.firstSlotIndex }
                val photoRow = geometry.rows.first {
                    slotIndex in it.firstSlotIndex until (it.firstSlotIndex + it.slotCount)
                }
                val renderedY = photoRow.topPx - targetRow.topPx -
                    target.firstSlotScrollOffsetPx + photoRow.heightPx * .6
                assertEquals("$density focused media $focused", 870.0, renderedY, 1.0)
            }
        }
    }
}
