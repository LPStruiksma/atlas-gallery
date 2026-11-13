package nl.atlas.gallery.featuregrid

import java.text.NumberFormat

/**
 * Fast-scroll bubble text that follows the ACTIVE sort (#514, Lukas
 * 2026-11-09 23:51Z: with Largest the scrubber still acted as a date timeline).
 *
 * - Newest/Oldest: the date group the target photo sits in. If the catalogue's
 *   chronology is stale (ungrouped), fall back to a plain position.
 * - Largest/Smallest: the file-size bucket of the target photo. Because the
 *   list is sorted by size, the buckets move monotonically with the thumb.
 * - Random: plain position only.
 *
 * O(1) per call: the caller passes the one target record's fields.
 */
fun fastScrollBubbleLabel(
    sort: TimelineSort,
    dateLabel: String?,
    sizeBytes: Long,
    mediaIndex: Int,
    mediaCount: Int
): String = when (sort) {
    TimelineSort.NEWEST, TimelineSort.OLDEST ->
        dateLabel ?: fastScrollPositionLabel(mediaIndex, mediaCount)
    TimelineSort.LARGEST, TimelineSort.SMALLEST -> sizeBucketLabel(sizeBytes)
    TimelineSort.RANDOM -> fastScrollPositionLabel(mediaIndex, mediaCount)
}

fun fastScrollPositionLabel(mediaIndex: Int, mediaCount: Int): String {
    val format = NumberFormat.getIntegerInstance()
    return "Photo ${format.format(mediaIndex + 1L)} of ${format.format(mediaCount.toLong())}"
}

/**
 * Lower bounds (decimal units, as Android's own file-size formatter uses)
 * and labels, largest first. A size belongs to the first bucket whose lower
 * bound it reaches, so exactly 1 MB is "1–2 MB".
 */
private val SIZE_BUCKETS: List<Pair<Long, String>> = listOf(
    5_000_000_000L to "Over 5 GB",
    2_000_000_000L to "2–5 GB",
    1_000_000_000L to "1–2 GB",
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
    1L to "Under 100 KB"
)

/** Bucket rank, 0 = largest. Unknown (0 or negative) sizes rank last. */
fun sizeBucketIndex(sizeBytes: Long): Int {
    val index = SIZE_BUCKETS.indexOfFirst { sizeBytes >= it.first }
    return if (index >= 0) index else SIZE_BUCKETS.size
}

fun sizeBucketLabel(sizeBytes: Long): String =
    SIZE_BUCKETS.getOrNull(sizeBucketIndex(sizeBytes))?.second ?: "Unknown size"
