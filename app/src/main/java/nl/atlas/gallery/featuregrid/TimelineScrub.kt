package nl.atlas.gallery.featuregrid

import kotlin.math.roundToInt

/**
 * Keep the scrubber's domain as media positions, not mixed grid slots: headers
 * are layout, not images. All lookup is O(log mediaCount); no per-drag scans.
 */
fun GridTimelineIndex.mediaIndexAtOrBeforeSlot(slotIndex: Int): Int? {
    if (mediaCount == 0) return null
    val target = slotIndex.coerceIn(0, slots.lastIndex)
    var low = 0
    var high = mediaEntryPositions.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val position = mediaEntryPositions[middle]
        when {
            position < target -> low = middle + 1
            position > target -> high = middle - 1
            else -> return middle
        }
    }
    // A header should be attributed to the next image in that group, not to
    // the end of yesterday. For a trailing header or malformed slot fall back.
    return low.coerceIn(0, mediaEntryPositions.lastIndex)
}

fun GridTimelineIndex.fractionForSlot(slotIndex: Int): Float {
    val index = mediaIndexAtOrBeforeSlot(slotIndex) ?: return 0f
    return if (mediaCount < 2) 0f else index.toFloat() / (mediaCount - 1)
}

/**
 * Thumb position for the live grid. The first visible photo never reaches the
 * last index, so a grid scrolled to its end pins the thumb to the bottom.
 */
fun GridTimelineIndex.thumbFraction(firstVisibleSlot: Int, atEnd: Boolean): Float =
    if (atEnd && mediaCount > 1 && firstVisibleSlot > 0) 1f else fractionForSlot(firstVisibleSlot)

fun GridTimelineIndex.scrubTarget(fraction: Float): TimelineScrubTarget? {
    if (mediaCount == 0 || !fraction.isFinite()) return null
    val index = ((mediaCount - 1).toDouble() * fraction.coerceIn(0f, 1f)).roundToInt()
    val slot = entryForMedia(index)
    return TimelineScrubTarget(index, slot, bubbleForEntry(slot))
}

data class TimelineScrubTarget(
    val mediaIndex: Int,
    val slotIndex: Int,
    /** Null for Largest/Random or ungrouped catalogues. */
    val dateLabel: String?
)

/**
 * Slot the grid should show WHILE the scrubber is still held, so the photos
 * follow the thumb live (Samsung Gallery behaviour). Without this the grid
 * stays frozen during the drag and swaps every visible photo at release,
 * which in Random order reads as a reshuffle on every stop (#493).
 * Null unless the mounted grid has exactly this timeline's slot count.
 */
fun GridTimelineIndex.liveScrubSlotOrNull(fraction: Float, mountedSlotCount: Int): Int? {
    if (mountedSlotCount <= 0 || mountedSlotCount != slots.size) return null
    return scrubTarget(fraction)?.slotIndex?.takeIf { it in 0 until mountedSlotCount }
}
