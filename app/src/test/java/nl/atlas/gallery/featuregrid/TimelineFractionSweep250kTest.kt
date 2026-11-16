package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/**
 * DAILY-9: cross-check the two scrub APIs against the one published 250k-media index.
 * Uses only synthetic keys and a fixed UTC clock; never builds a second position index.
 */
class TimelineFractionSweep250kTest {
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), zone)
    private val count = 250_000
    private val base = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val samples = (0..256).map { it / 256f } +
        listOf(0.000001f, 0.499999f, 0.500001f, 0.999999f)

    @Test fun quarterMillionFractionsAgreeAcrossChronologicalDensitiesAndFlatSorts() {
        // A seeded, descending catalog spanning ~2,500 days: DAY has many headers,
        // MONTH fewer, YEAR few. Each bucket also contains many media entries.
        val source = List(count) { i ->
            TimelineMedia("seeded:${i * 1103515245L + 12345L}", base - (i / 100L) * 86_400_000L)
        }
        val cases = listOf(
            Triple(TimelineSort.NEWEST, GridDensity.MEDIUM, "DAY newest"),
            Triple(TimelineSort.OLDEST, GridDensity.MEDIUM, "DAY oldest"),
            Triple(TimelineSort.NEWEST, GridDensity.MONTH, "MONTH newest"),
            Triple(TimelineSort.NEWEST, GridDensity.YEAR, "YEAR newest"),
            Triple(TimelineSort.LARGEST, GridDensity.MEDIUM, "LARGEST flat"),
            Triple(TimelineSort.SMALLEST, GridDensity.MEDIUM, "SMALLEST flat"),
            Triple(TimelineSort.RANDOM, GridDensity.MEDIUM, "RANDOM flat")
        )
        for ((sort, density, label) in cases) {
            val ordered = if (sort == TimelineSort.OLDEST) source.asReversed() else source
            val index = buildGridTimelineIndex(ordered, sort, density, zone, Locale.UK, clock = clock)
            val fast = TimelineFastScrollPlan(index)
            assertEquals("$label media count", count, index.mediaCount)
            val grouped = sort == TimelineSort.NEWEST || sort == TimelineSort.OLDEST
            assertEquals("$label grouped", grouped, index.grouped)
            assertEquals("$label header state", grouped, index.headers.isNotEmpty())
            // The fast-scroll adapter uses the same index, not a second materialized
            // media-to-slot lookup. Identity checks on returned entries cover both APIs.
            assertEquals("$label first", index.entryForMedia(0), index.entryAtFraction(0f))
            assertEquals("$label last", index.entryForMedia(count - 1), index.entryAtFraction(1f))
            var previousMedia = -1
            for ((sample, fraction) in samples.withIndex()) {
                val expected = ((count - 1).toDouble() * fraction).roundToInt()
                val direct = requireNotNull(index.entryAtFraction(fraction))
                val planned = requireNotNull(fast.entryAtFraction(fraction))
                val scrub = requireNotNull(index.scrubTarget(fraction))
                val slot = index.slots[direct]
                assertTrue("$label sample=$sample fraction=$fraction header target=$direct",
                    slot is TimelineSlot.Media)
                assertEquals("$label sample=$sample rank", expected, (slot as TimelineSlot.Media).mediaIndex)
                assertTrue("$label sample=$sample rank regressed", slot.mediaIndex >= previousMedia)
                assertEquals("$label sample=$sample direct/plan", direct, planned)
                assertEquals("$label sample=$sample direct/scrub", direct, scrub.slotIndex)
                assertEquals("$label sample=$sample scrub rank", expected, scrub.mediaIndex)
                assertEquals("$label sample=$sample bubble", index.bubbleForEntry(direct), scrub.dateLabel)
                previousMedia = slot.mediaIndex
            }
            for ((fraction, expectedRank) in listOf(-Float.MAX_VALUE to 0,
                    -0.001f to 0, 1.001f to count - 1, Float.MAX_VALUE to count - 1)) {
                val expectedSlot = index.entryForMedia(expectedRank)
                assertEquals("$label clamp direct $fraction", expectedSlot, index.entryAtFraction(fraction))
                assertEquals("$label clamp fast $fraction", expectedSlot, fast.entryAtFraction(fraction))
                assertEquals("$label clamp scrub $fraction", expectedSlot, index.scrubTarget(fraction)?.slotIndex)
            }
            for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertNull("$label nonfinite direct $fraction", index.entryAtFraction(fraction))
                assertNull("$label nonfinite fast $fraction", fast.entryAtFraction(fraction))
                assertNull("$label nonfinite scrub $fraction", index.scrubTarget(fraction))
            }
        }
    }
}
