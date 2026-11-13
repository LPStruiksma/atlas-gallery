package nl.atlas.gallery.featuregrid

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.SortOrder
import nl.atlas.gallery.sortMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #514: the fast-scroll bubble and track follow the ACTIVE sort. */
class TimelineScrubLabelTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Before fun pinLocale() = Locale.setDefault(Locale.UK)

    @Test fun sizeBucketsHaveExactLowerBounds() {
        assertEquals("Over 5 GB", sizeBucketLabel(7_000_000_000L))
        assertEquals("1–2 GB", sizeBucketLabel(1_000_000_000L))
        assertEquals("500 MB–1 GB", sizeBucketLabel(999_999_999L))
        assertEquals("100–250 MB", sizeBucketLabel(180_000_000L))
        assertEquals("5–10 MB", sizeBucketLabel(5_000_000L))
        assertEquals("2–5 MB", sizeBucketLabel(4_999_999L))
        assertEquals("1–2 MB", sizeBucketLabel(1_000_000L))
        assertEquals("500 KB–1 MB", sizeBucketLabel(700_000L))
        assertEquals("Under 100 KB", sizeBucketLabel(1L))
        assertEquals("Unknown size", sizeBucketLabel(0L))
        assertEquals("Unknown size", sizeBucketLabel(-5L))
    }

    @Test fun bubbleFollowsTheActiveSort() {
        assertEquals("3 Oct 2026", fastScrollBubbleLabel(TimelineSort.NEWEST, "3 Oct 2026", 9L, 4, 10))
        assertEquals("3 Oct 2026", fastScrollBubbleLabel(TimelineSort.OLDEST, "3 Oct 2026", 9L, 4, 10))
        // Lukas's bug: Largest must show sizes, never dates.
        assertEquals("100–250 MB",
            fastScrollBubbleLabel(TimelineSort.LARGEST, "3 Oct 2026", 180_000_000L, 4, 10))
        assertEquals("2–5 MB",
            fastScrollBubbleLabel(TimelineSort.SMALLEST, null, 3_000_000L, 4, 10))
        assertEquals("Photo 5 of 10", fastScrollBubbleLabel(TimelineSort.RANDOM, "3 Oct 2026", 9L, 4, 10))
        // Stale chronology (ungrouped) falls back to a plain position.
        assertEquals("Photo 1,235 of 150,000",
            fastScrollBubbleLabel(TimelineSort.NEWEST, null, 9L, 1234, 150_000))
    }

    @Test fun scrubbingALargestListWalksSizesFromLargestToSmallest() {
        val sizes = listOf(7_000_000_000L, 1_200_000_000L, 300_000_000L, 120_000_000L,
            40_000_000L, 12_000_000L, 6_000_000L, 3_000_000L, 1_500_000L, 600_000L, 50_000L)
        val records = sizes.mapIndexed { i, size ->
            // Dates deliberately unrelated to size.
            MediaRecord(i.toLong(), "content://fiction/$i", "f$i.jpg", "DCIM/", size,
                Instant.parse("2026-10-01T12:00:00Z").toEpochMilli() + (i * 7919L % 11) * 86_400_000L,
                false)
        }
        for ((order, sort) in listOf(SortOrder.LARGEST to TimelineSort.LARGEST,
            SortOrder.SMALLEST to TimelineSort.SMALLEST)) {
            val sorted = sortMedia(records.shuffled(java.util.Random(4)), order, 0L)
            val index = buildGridTimelineIndex(sorted.map { TimelineMedia(it.uri, it.takenAtMillis) },
                sort, GridDensity.MEDIUM, zone, Locale.UK)
            val labels = (0..100).map { step ->
                val target = index.scrubTarget(step / 100f)!!
                fastScrollBubbleLabel(sort, target.dateLabel,
                    sorted[target.mediaIndex].sizeBytes, target.mediaIndex, sorted.size)
            }
            assertTrue("$order bubble showed a date: $labels", labels.none { it.contains("2026") })
            // The fixture sizes are descending and each sits in its own bucket.
            val bucketLabels = sizes.map(::sizeBucketLabel)
            assertEquals(sizes.size, bucketLabels.toSet().size)
            val ranks = labels.map(bucketLabels::indexOf)
            assertTrue(ranks.none { it < 0 })
            val expected = if (order == SortOrder.LARGEST) ranks.sorted() else ranks.sortedDescending()
            assertEquals("$order bubble must move monotonically through sizes", expected, ranks)
            assertEquals(if (order == SortOrder.LARGEST) "Over 5 GB" else "Under 100 KB", labels.first())
            assertEquals(if (order == SortOrder.LARGEST) "Under 100 KB" else "Over 5 GB", labels.last())
        }
    }

    @Test fun trackMathsCentreTheThumbOnTheFingerAndClamp() {
        assertEquals(0f, fastScrollFractionForTouch(0f, 1000f, 100f), 0f)
        assertEquals(0f, fastScrollFractionForTouch(50f, 1000f, 100f), 0f)
        assertEquals(.5f, fastScrollFractionForTouch(500f, 1000f, 100f), 1e-6f)
        assertEquals(1f, fastScrollFractionForTouch(950f, 1000f, 100f), 0f)
        assertEquals(1f, fastScrollFractionForTouch(5000f, 1000f, 100f), 0f)
        assertEquals(0f, fastScrollFractionForTouch(500f, 80f, 100f), 0f)
        assertEquals(0f, fastScrollFractionForTouch(Float.NaN, 1000f, 100f), 0f)
    }

    @Test fun thumbReachesTheBottomWhenTheGridIsAtItsEnd() {
        val items = (0 until 40).map { TimelineMedia("m$it", 0L) }
        val index = buildGridTimelineIndex(items, TimelineSort.LARGEST, GridDensity.MEDIUM, zone)
        assertEquals(30f / 39f, index.thumbFraction(30, atEnd = false), 1e-6f)
        assertEquals(1f, index.thumbFraction(30, atEnd = true), 0f)
        // A short list that fits on screen is both at start and end: stay on top.
        assertEquals(0f, index.thumbFraction(0, atEnd = true), 0f)
    }
}
