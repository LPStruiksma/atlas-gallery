package nl.atlas.gallery

import nl.atlas.gallery.featuregrid.TimelineDisplayedSlots
import nl.atlas.gallery.featuregrid.TimelineSlot

/**
 * The dated grid mixes full-span headers with photo/video slots. Its lazy item
 * keys are positional numbers (never private URIs), so the authoritative focus
 * source when switching layouts is the exact displayed slot list published by
 * G's AtlasTimelineGallery, indexed by the visible LazyGrid slot numbers.
 *
 * Never use a LazyGrid slot number directly as a MediaRecord index; doing so
 * drifts by every preceding header. Fails closed (null) unless the displayed
 * slots belong to the current list reference and epoch, and the mounted grid
 * has exactly as many slots as that displayed timeline. The host can then
 * switch layout without an anchor instead of guessing.
 */
internal fun firstTimelineVisibleMediaUri(
    visibleSlotIndices: List<Int>,
    mountedSlotCount: Int,
    displayed: TimelineDisplayedSlots?,
    currentSource: List<MediaRecord>,
    currentEpoch: Long
): String? {
    val snapshot = displayed ?: return null
    if (snapshot.source !== currentSource || snapshot.catalogueEpoch != currentEpoch) return null
    val slots = snapshot.timeline.slots
    if (slots.size != mountedSlotCount) return null
    val mediaIndex = visibleSlotIndices.sorted().firstNotNullOfOrNull { index ->
        (slots.getOrNull(index) as? TimelineSlot.Media)?.mediaIndex
    } ?: return null
    return currentSource.getOrNull(mediaIndex)?.uri
}
