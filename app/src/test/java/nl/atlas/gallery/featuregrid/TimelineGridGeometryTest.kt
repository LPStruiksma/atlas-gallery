package nl.atlas.gallery.featuregrid

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class TimelineGridGeometryTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val origin = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private fun fixture(count: Int): List<TimelineMedia> = List(count) { i ->
        TimelineMedia("media:$i", origin - i * 60_000L)
    }

    @Test fun eachHeaderHasFullSpanAndDatesNeverGetPlacedInsidePhotoRow() {
        val items = fixture(1_500)
        for (level in GridDensity.entries) {
            val timeline = buildGridTimelineIndex(items, TimelineSort.NEWEST, level, zone, Locale.UK)
            val bounds = GridViewportGeometry(420, 810, 3, 3, items.size)
            val plan = planTimelineVisualRows(timeline, level.columns, bounds, 36)
            assertTrue(plan.rows.isNotEmpty())
            var entryCursor = 0
            var top = 0L
            for (row in plan.rows) {
                assertEquals(entryCursor, row.firstSlotIndex)
                assertEquals(top, row.topPx)
                assertTrue(row.slotCount in 1..level.columns)
                if (row.header) {
                    assertEquals(1, row.slotCount)
                    assertEquals(36, row.heightPx)
                    assertTrue(timeline.slots[row.firstSlotIndex] is TimelineSlot.Header)
                } else {
                    for (entry in row.firstSlotIndex until row.firstSlotIndex + row.slotCount)
                        assertTrue(timeline.slots[entry] is TimelineSlot.Media)
                }
                entryCursor += row.slotCount
                top += row.heightPx + bounds.gapPx
            }
            assertEquals(timeline.slots.size, entryCursor)
            assertEquals(top - bounds.gapPx, plan.heightPx)
        }
    }

    @Test fun groupedAnchorSurvivesMonthDayRegroupingFor150kMedia() {
        val items = fixture(150_000)
        for (density in GridDensity.entries) {
            val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, density, zone)
            val bounds = GridViewportGeometry(420, 820, 3, 3, items.size)
            val geometry = planTimelineVisualRows(index, density.columns, bounds, 36)
            for (i in 1000 until 148000 step 1789) {
                val fraction = (i % 100) / 100f
                val target = geometry.targetMedia(i, fraction, 410f)
                assertTrue(!target.clampedByBounds)
                val screenStart = geometry.rows.first { it.firstSlotIndex == target.firstSlotIndex }
                val anchorEntry = index.entryForMedia(i)
                val mediaRow = geometry.rows.first {
                    !it.header && anchorEntry in it.firstSlotIndex until it.firstSlotIndex + it.slotCount
                }
                val actual = mediaRow.topPx.toDouble() + mediaRow.heightPx * fraction -
                    screenStart.topPx - target.firstSlotScrollOffsetPx - 410.0
                assertTrue("$density i=$i error=$actual", abs(actual) <= .51)
            }
        }
    }

    @Test fun emptyAndUnsortedLayoutsStaySafe() {
        val empty = buildGridTimelineIndex(emptyList(), TimelineSort.RANDOM, GridDensity.MEDIUM, zone)
        val bounds = GridViewportGeometry(420, 820, 3, 3, 0)
        val result = planTimelineVisualRows(empty, 4, bounds, 36)
        assertTrue(result.rows.isEmpty())
        assertEquals(0L, result.heightPx)
        val unsorted = listOf(TimelineMedia("a", 3), TimelineMedia("b", 10))
        val non = buildGridTimelineIndex(unsorted, TimelineSort.NEWEST, GridDensity.SMALL, zone)
        val ungrouped = planTimelineVisualRows(non, 7, bounds.copy(itemCount = 2), 36)
        assertEquals(1, ungrouped.rows.size)
        assertTrue(!ungrouped.rows[0].header)
    }

    @Test fun headerSpansAndRemainderPixelsCannotShiftFocalRowsAtScale() {
        val items = fixture(150_000)
        for (screenWidth in listOf(389, 393, 411, 420, 1080)) {
            for (level in GridDensity.entries) {
                val bounds = GridViewportGeometry(screenWidth, 820, 3, 3, items.size)
                val timeline = buildGridTimelineIndex(items, TimelineSort.NEWEST,
                    level, zone, Locale.UK)
                val geometry = planTimelineVisualRows(timeline, level.columns, bounds, 36)
                val available = screenWidth - 6 - (level.columns - 1) * 3
                val independentRowHeight = List(level.columns) { index ->
                    available / level.columns +
                        if (index < available % level.columns) 1 else 0
                }.max()
                val photoRows = geometry.rows.filter { !it.header }
                assertTrue(photoRows.isNotEmpty())
                assertTrue(photoRows.all { it.heightPx == independentRowHeight })
                for (mediaIndex in listOf(1_234, 30_001, 102_345, 148_123)) {
                    val target = geometry.targetMedia(mediaIndex, .4f, 306f)
                    assertFalse(target.clampedByBounds)
                    val slot = timeline.entryForMedia(mediaIndex)
                    val mediaRow = geometry.rows.first {
                        !it.header && slot in it.firstSlotIndex until it.firstSlotIndex + it.slotCount
                    }
                    val startRow = geometry.rows.first { it.firstSlotIndex == target.firstSlotIndex }
                    val focal = mediaRow.topPx.toDouble() +
                        .4 * mediaRow.heightPx - startRow.topPx -
                        target.firstSlotScrollOffsetPx
                    assertTrue("width=$screenWidth cols=${level.columns} media=$mediaIndex focal=$focal",
                        abs(focal - 306) <= .51)
                }
            }
        }
    }
}
