package nl.atlas.gallery.featuregrid

import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.SortOrder
import nl.atlas.gallery.selectionIndex
import nl.atlas.gallery.sortMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #602: synthetic, pure JVM acceptance twin for the size-sort -> scrub -> viewer-key path.
 * No assertions about a running Compose screen, device scrolling, or persisted navigation.
 */
class TimelineSizeSortAuditTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val locale = Locale.UK
    private val seed = 414L

    private fun item(i: Int, bytes: Long, date: Long = i.toLong() * 86_400_000L) =
        MediaRecord(
            id = i.toLong(),
            uri = "content://synthetic/photos/$i",
            name = "synthetic-$i.jpg",
            relativePath = "DCIM/Synthetic/",
            sizeBytes = bytes,
            takenAtMillis = date,
            isVideo = false
        )

    private val boundaries = listOf(
        7_000_000_000L to "Over 5 GB",
        5_000_000_000L to "Over 5 GB",
        4_999_999_999L to "2–5 GB",
        2_000_000_000L to "2–5 GB",
        1_000_000_000L to "1–2 GB",
        999_999_999L to "500 MB–1 GB",
        500_000_000L to "500 MB–1 GB",
        250_000_000L to "250–500 MB",
        100_000_000L to "100–250 MB",
        50_000_000L to "50–100 MB",
        25_000_000L to "25–50 MB",
        10_000_000L to "10–25 MB",
        5_000_000L to "5–10 MB",
        2_000_000L to "2–5 MB",
        1_000_000L to "1–2 MB",
        500_000L to "500 KB–1 MB",
        100_000L to "100–500 KB",
        1L to "Under 100 KB",
        0L to "Unknown size",
        -1L to "Unknown size"
    )

    private fun timeline(items: List<MediaRecord>, order: SortOrder): GridTimelineIndex =
        buildGridTimelineIndex(
            items.map { TimelineMedia(it.uri, it.takenAtMillis) },
            when (order) {
                SortOrder.NEWEST -> TimelineSort.NEWEST
                SortOrder.OLDEST -> TimelineSort.OLDEST
                SortOrder.LARGEST -> TimelineSort.LARGEST
                SortOrder.SMALLEST -> TimelineSort.SMALLEST
                SortOrder.RANDOM -> TimelineSort.RANDOM
            },
            GridDensity.MEDIUM, zone, locale
        )

    @Test fun sizeBoundariesAndUnknownsUseExactDecimalBuckets() {
        boundaries.forEach { (size, label) ->
            assertEquals("size=$size", label, sizeBucketLabel(size))
        }
        assertEquals(sizeBucketIndex(0L), sizeBucketIndex(-1L))
        assertTrue(sizeBucketIndex(1L) < sizeBucketIndex(0L))
    }

    @Test fun largeCatalogOrdersAndScrubsByMediaNotGridSlots() {
        val sizes = boundaries.map { it.first }.toLongArray()
        val source = (0 until 20_000).map { i ->
            item(i, sizes[(i * 7) % sizes.size], (i * 13L % 31L) * 86_400_000L)
        }.shuffled(java.util.Random(92))

        for (order in listOf(SortOrder.LARGEST, SortOrder.SMALLEST)) {
            val sorted = sortMedia(source, order, seed)
            val expected = if (order == SortOrder.LARGEST)
                compareByDescending<MediaRecord> { it.sizeBytes }.thenBy { it.id }.thenBy { it.uri }
            else
                compareBy<MediaRecord> { it.sizeBytes }.thenBy { it.id }.thenBy { it.uri }
            assertEquals("$order exact comparator, including equal size/id tie", source.sortedWith(expected), sorted)
            assertEquals(source.size, sorted.map { it.uri }.toSet().size)

            val index = timeline(sorted, order)
            assertFalse("$order must not fabricate date headers", index.grouped)
            assertEquals(sorted.size, index.mediaCount)
            assertEquals(sorted.size, index.slots.size)
            val ranks = (0..100).map { n ->
                val fraction = n / 100f
                val target = index.scrubTarget(fraction)!!
                val expectedIndex = ((sorted.lastIndex).toDouble() * fraction).roundToInt()
                assertEquals("$order fraction=$fraction", expectedIndex, target.mediaIndex)
                assertEquals(index.entryForMedia(expectedIndex), target.slotIndex)
                assertEquals(
                    "media:" + sorted[expectedIndex].uri,
                    index.slots[target.slotIndex].stableKey
                )
                assertNull(target.dateLabel)
                assertEquals(
                    sizeBucketLabel(sorted[expectedIndex].sizeBytes),
                    fastScrollBubbleLabel(
                        if (order == SortOrder.LARGEST) TimelineSort.LARGEST else TimelineSort.SMALLEST,
                        target.dateLabel, sorted[expectedIndex].sizeBytes, target.mediaIndex, sorted.size
                    )
                )
                sizeBucketIndex(sorted[target.mediaIndex].sizeBytes)
            }
            assertEquals(
                "$order size buckets must be monotonic",
                if (order == SortOrder.LARGEST) ranks.sorted() else ranks.sortedDescending(),
                ranks
            )
            assertEquals(0, index.scrubTarget(-100f)!!.mediaIndex)
            assertEquals(sorted.lastIndex, index.scrubTarget(100f)!!.mediaIndex)
            assertNull(index.scrubTarget(Float.NaN))
        }
    }

    @Test fun selectionKeySurvivesSortSwitchAndRandomNewestStayStable() {
        val duplicateSize = 1_000_000L
        val source = (0 until 1200).map { i ->
            item(i, if (i % 17 == 0) 0L else if (i % 3 == 0) duplicateSize else (i % 53 + 1L) * 250_000L,
                (i % 29).toLong() * 86_400_000L)
        }.shuffled(java.util.Random(17))
        val chosen = source[321].uri
        val newestBefore = sortMedia(source, SortOrder.NEWEST, seed)
        val randomBefore = sortMedia(source, SortOrder.RANDOM, seed)
        assertEquals(
            source.sortedWith(compareByDescending<MediaRecord> { it.takenAtMillis }
                .thenByDescending { it.id }.thenBy { it.uri }),
            newestBefore
        )
        for (order in listOf(SortOrder.LARGEST, SortOrder.SMALLEST, SortOrder.RANDOM, SortOrder.NEWEST)) {
            val sorted = sortMedia(source, order, seed)
            val selectedIndex = selectionIndex(sorted, chosen)
            assertTrue("$order selected media not found", selectedIndex >= 0)
            val target = timeline(sorted, order).entryForMedia(selectedIndex)
            assertEquals("media:$chosen", timeline(sorted, order).slots[target].stableKey)
            assertEquals(chosen, sorted[selectedIndex].uri)
            assertEquals(chosen, sorted[selectionIndex(sorted, chosen)].uri)
        }
        assertEquals(newestBefore, sortMedia(source, SortOrder.NEWEST, seed))
        assertEquals(randomBefore, sortMedia(source, SortOrder.RANDOM, seed))
        assertEquals(randomBefore.map { it.uri }.toSet(), source.map { it.uri }.toSet())
        assertEquals("Photo 1 of 1,200",
            fastScrollBubbleLabel(TimelineSort.RANDOM, "ignored", 0L, 0, source.size))
    }

    @Test fun equalSizesHaveDeterministicIdThenUriTieAndEmptyScrubIsNull() {
        val ties = listOf(
            item(4, 0L),
            item(2, 1_000_000L),
            item(1, 1_000_000L),
            item(3, 0L)
        )
        assertEquals(listOf(1L, 2L, 3L, 4L),
            sortMedia(ties, SortOrder.LARGEST, seed).map { it.id })
        assertEquals(listOf(3L, 4L, 1L, 2L),
            sortMedia(ties, SortOrder.SMALLEST, seed).map { it.id })
        assertNull(timeline(emptyList(), SortOrder.LARGEST).scrubTarget(.5f))
    }
}
