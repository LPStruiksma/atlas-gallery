package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

/** PERF-23: synthetic geometry at archive scale, without MediaStore or UI. */
class TimelineGridGeometryScaleTest {
    private fun index(mediaCount: Int, headerFrequency: Int): GridTimelineIndex {
        val slots = ArrayList<TimelineSlot>(mediaCount + if (headerFrequency > 0) mediaCount else 0)
        val positions = IntArray(mediaCount)
        val headers = ArrayList<TimelineHeaderPosition>()
        for (media in 0 until mediaCount) {
            if (headerFrequency > 0 && media % headerFrequency == 0) {
                val header = TimelineSlot.Header("header:$media", "Day $media", media, 1)
                headers.add(TimelineHeaderPosition(slots.size, header))
                slots.add(header)
            }
            positions[media] = slots.size
            slots.add(TimelineSlot.Media("media:$media", media))
        }
        return GridTimelineIndex(slots, positions, headers, headerFrequency > 0)
    }

    @Test fun zeroAndOneSlotHaveExactBoundsAndFinalCheckpoint() {
        for (count in listOf(0, 1)) for (frequency in listOf(0, 1)) {
            val timeline = index(count, frequency)
            var checkpoints = 0
            val geometry = planTimelineVisualRows(
                timeline, 3, GridViewportGeometry(420, 820, 3, 3, count), 36
            ) { checkpoints++ }
            assertTrue(checkpoints >= 1)
            assertEquals(count + if (count > 0 && frequency == 1) 1 else 0, geometry.rows.size)
            assertEquals(if (count == 0) 0L else geometry.rows.sumOf { it.heightPx.toLong() } +
                (geometry.rows.size - 1L) * 3L, geometry.heightPx)
            if (count == 1) {
                assertEquals(0, geometry.columnOfMedia(0))
                assertEquals(geometry.rows.first().firstSlotIndex,
                    geometry.targetMedia(0, 0f, 0f).firstSlotIndex)
            }
        }
    }

    @Test fun twoHundredFiftyThousandSlotsMapEveryRowAndBoundCancellation() {
        val count = 250_000
        val columns = listOf(1, 3, 4, 7, 12, 28)
        for (frequency in listOf(0, 1, 127, count)) {
            val timeline = index(count, frequency)
            for (columnCount in columns) {
                val viewport = GridViewportGeometry(421, 810, 3, 3, count)
                var checkpoints = 0
                val geometry = planTimelineVisualRows(timeline, columnCount, viewport, 36) {
                    checkpoints++
                }
                var cursor = 0
                var top = 0L
                var headerRows = 0
                var photoRows = 0
                for (row in geometry.rows) {
                    assertEquals(cursor, row.firstSlotIndex)
                    assertEquals(top, row.topPx)
                    assertTrue(row.slotCount in 1..columnCount)
                    if (row.header) {
                        headerRows++
                        assertEquals(1, row.slotCount)
                        assertTrue(timeline.slots[cursor] is TimelineSlot.Header)
                    } else {
                        photoRows++
                        for (slot in cursor until cursor + row.slotCount)
                            assertTrue(timeline.slots[slot] is TimelineSlot.Media)
                    }
                    cursor += row.slotCount
                    top += row.heightPx.toLong() + viewport.gapPx.toLong()
                }
                assertEquals(timeline.slots.size, cursor)
                assertEquals(timeline.headers.size, headerRows)
                val expectedPhotos = if (frequency == 1) count else if (frequency == 0) {
                    (count + columnCount - 1) / columnCount
                } else {
                    val fullRuns = count / frequency
                    val remainder = count % frequency
                    fullRuns * ((frequency + columnCount - 1) / columnCount) +
                        if (remainder == 0) 0 else (remainder + columnCount - 1) / columnCount
                }
                assertEquals(expectedPhotos, photoRows)
                assertEquals(top - viewport.gapPx, geometry.heightPx)
                assertTrue("checkpoint frequency", checkpoints >= geometry.rows.size / 256)
                assertTrue("checkpoint ceiling", checkpoints <= geometry.rows.size / 256 + 2)
                for (media in listOf(0, count / 2, count - 1)) {
                    val entry = timeline.entryForMedia(media)
                    val row = geometry.rows.first { !it.header &&
                        entry >= it.firstSlotIndex && entry < it.firstSlotIndex + it.slotCount }
                    assertEquals(entry - row.firstSlotIndex, geometry.columnOfMedia(media))
                    val target = geometry.targetMedia(media, .5f, 405f)
                    val start = geometry.rows.first { it.firstSlotIndex == target.firstSlotIndex }
                    val wanted = row.topPx.toDouble() + row.heightPx * .5 - 405.0
                    val bounded = wanted.coerceIn(
                        0.0, (geometry.heightPx - viewport.heightPx).coerceAtLeast(0L).toDouble()
                    )
                    val reconstructed = start.topPx + target.firstSlotScrollOffsetPx.toLong()
                    assertTrue("media=$media frequency=$frequency columns=$columnCount",
                        kotlin.math.abs(reconstructed.toDouble() - bounded) <= .501)
                }
            }
        }
    }

    @Test fun extremeFiniteGeometryKeepsLongOffsetsAndClampedTargets() {
        val count = 250_000
        val timeline = index(count, 0)
        val viewport = GridViewportGeometry(Int.MAX_VALUE, 1, 0, Int.MAX_VALUE, count)
        val plan = planTimelineVisualRows(timeline, 1, viewport, Int.MAX_VALUE)
        val stride = Int.MAX_VALUE.toLong() * 2L
        assertEquals(count.toLong() * stride - Int.MAX_VALUE, plan.heightPx)
        assertTrue(plan.heightPx > Int.MAX_VALUE.toLong())
        // The final row is at maxScroll; the last item cannot fit its full height in the viewport.
        for (media in listOf(0, count / 2)) {
            val target = plan.targetMedia(media, 0f, 0f)
            assertEquals(media, target.firstSlotIndex)
            assertEquals(0, target.firstSlotScrollOffsetPx)
        }
        // A maximum-width final row is clipped by exactly one viewport pixel.
        val last = plan.targetMedia(count - 1, 0f, 0f)
        assertEquals(count - 2, last.firstSlotIndex)
        assertEquals(Int.MAX_VALUE - 1, last.firstSlotScrollOffsetPx)
        assertFalse(last.clampedByBounds)
        assertTrue(plan.targetMedia(0, 0f, Float.MAX_VALUE).clampedByBounds)
        assertTrue(plan.targetMedia(count - 1, 1f, -Float.MAX_VALUE).clampedByBounds)
    }
}
