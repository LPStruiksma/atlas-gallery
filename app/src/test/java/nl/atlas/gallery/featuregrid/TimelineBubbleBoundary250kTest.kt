package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Boundary-focused scrub oracle; all records are synthetic and the clock is fixed. */
class TimelineBubbleBoundary250kTest {
    private val zone = ZoneId.of("America/St_Johns")
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), zone)
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    private fun checkBoundaries(name: String, index: GridTimelineIndex) {
        assertEquals("$name media count", 250_000, index.mediaCount)
        assertTrue("$name should have headers", index.grouped && index.headers.isNotEmpty())
        val boundaries = linkedSetOf(0, index.mediaCount - 1)
        for (header in index.headers) {
            val first = header.header.firstMediaIndex
            boundaries.add(first)
            if (first > 0) boundaries.add(first - 1)
            val last = first + header.header.mediaCount - 1
            boundaries.add(last)
            if (last + 1 < index.mediaCount) boundaries.add(last + 1)
        }
        // Probe inside the dense run without allocating a second 250k-sized oracle.
        for (i in 0 until index.mediaCount step 4093) boundaries.add(i)
        for (i in boundaries) {
            val slot = index.entryForMedia(i)
            val owner = index.headers.lastOrNull { it.entryIndex <= slot }
            assertNotNull("$name media=$i slot=$slot missing header", owner)
            val expected = owner!!.header.label
            assertEquals("$name media=$i slot=$slot bubble", expected, index.bubbleForEntry(slot))
            val fraction = i.toFloat() / (index.mediaCount - 1)
            assertTrue("$name media=$i fraction finite", fraction.isFinite())
            val target = index.scrubTarget(fraction)
            assertNotNull("$name media=$i scrub null", target)
            val actual = target!!
            assertTrue("$name media=$i scrub index out of range", actual.mediaIndex in 0 until index.mediaCount)
            assertEquals("$name scrub slot", index.entryForMedia(actual.mediaIndex), actual.slotIndex)
            val targetOwner = index.headers.last { it.entryIndex <= actual.slotIndex }
            assertEquals("$name scrub header media=$i", targetOwner.header.label, actual.dateLabel)
            assertEquals("$name scrub bubble media=$i", index.bubbleForEntry(actual.slotIndex), actual.dateLabel)
        }
        assertEquals("$name first", 0, index.scrubTarget(-10f)!!.mediaIndex)
        assertEquals("$name last", index.mediaCount - 1, index.scrubTarget(10f)!!.mediaIndex)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull("$name invalid fraction=$invalid", index.scrubTarget(invalid))
        }
        assertNull("$name invalid entry", index.bubbleForEntry(-1))
        assertNull("$name past last entry", index.bubbleForEntry(index.slots.size))
    }

    @Test fun denseAndSparseBoundariesAcrossDayMonthYearAndRollback() {
        val dense = at("2026-11-15T12:00:00Z")
        val crossings = listOf(
            at("2026-11-14T23:59:00Z"),
            at("2026-10-31T23:59:00Z"),
            at("2025-12-31T23:59:00Z"),
            at("1987-10-25T03:31:30Z"),
            at("1987-10-25T02:31:30Z"),
            at("1987-10-25T02:30:30Z"),
            at("1987-10-25T02:29:30Z"),
            0L, 0L
        )
        val media = (0 until 249_991).map { TimelineMedia("repeat-key", dense - it % 1000) } +
            crossings.map { TimelineMedia("repeat-key", it) }
        val ordered = media.sortedByDescending { it.takenAtMillis }
        for (density in listOf(GridDensity.MEDIUM, GridDensity.MONTH, GridDensity.YEAR)) {
            val index = buildGridTimelineIndex(
                ordered, TimelineSort.NEWEST, density, zone, Locale.UK, clock = clock
            )
            checkBoundaries("density=$density", index)
            val rebuilt = buildGridTimelineIndex(
                ordered, TimelineSort.NEWEST, density, zone, Locale.UK, clock = clock
            )
            assertEquals("density=$density deterministic headers", index.headers, rebuilt.headers)
            assertEquals("density=$density deterministic last label",
                index.scrubTarget(1f)!!.dateLabel, rebuilt.scrubTarget(1f)!!.dateLabel)
        }
    }

    @Test fun flatAndNonMonotoneCataloguesNeverShowBubble() {
        val media = List(250_000) { i ->
            TimelineMedia("repeat-key", at("2026-11-15T12:00:00Z") - i.toLong() * 1000L)
        }
        for (sort in listOf(TimelineSort.LARGEST, TimelineSort.RANDOM)) {
            val index = buildGridTimelineIndex(media, sort, GridDensity.MEDIUM, zone, Locale.UK, clock = clock)
            assertFalse("$sort grouped", index.grouped)
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val target = index.scrubTarget(fraction)!!
                assertNull("$sort scrub fraction=$fraction", target.dateLabel)
                assertNull("$sort bubble fraction=$fraction", index.bubbleForEntry(target.slotIndex))
            }
        }
        val reversed = media.reversed()
        val index = buildGridTimelineIndex(reversed, TimelineSort.NEWEST,
            GridDensity.MEDIUM, zone, Locale.UK, clock = clock)
        assertFalse("nonmonotone NEWEST must be flat", index.grouped)
        assertNull(index.scrubTarget(1f)!!.dateLabel)
    }
}
