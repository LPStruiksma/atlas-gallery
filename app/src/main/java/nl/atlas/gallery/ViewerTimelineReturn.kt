package nl.atlas.gallery

import nl.atlas.gallery.featuregrid.GridTimelineIndex
import nl.atlas.gallery.featuregrid.TimelineSlot

/**
 * G's timeline LazyGrid counts full-span date headers as slots. Returning to
 * mediaIndex directly is wrong whenever a group header precedes the photo.
 * Resolve an immutable, permission-grant-bound viewer intent against the exact
 * G index, and fail closed until those exact slots are mounted.
 */
internal fun admittedTimelineReturnSlotOrNull(
    request: PendingViewerReturn,
    source: List<MediaRecord>,
    route: Int,
    authorized: Boolean,
    mediaIndex: Int,
    timeline: GridTimelineIndex,
    mountedItemCount: Int,
    currentAccessEpoch: Long
): Int? {
    if (!viewerReturnMatchesCurrentRoute(request, source, route,
        authorized, currentAccessEpoch)) return null
    if (mediaIndex !in source.indices || timeline.mediaCount != source.size) return null
    if (source[mediaIndex].uri != request.uri) return null
    val slotIndex = timeline.entryForMedia(mediaIndex)
    val slot = timeline.slots.getOrNull(slotIndex) as? TimelineSlot.Media ?: return null
    if (slot.mediaIndex != mediaIndex || slot.stableKey != "media:" + request.uri) return null
    if (mountedItemCount != timeline.slots.size) return null
    return slotIndex
}
