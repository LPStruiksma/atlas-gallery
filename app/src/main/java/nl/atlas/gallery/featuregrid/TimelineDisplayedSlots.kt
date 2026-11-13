package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.MediaRecord

/**
 * Host-facing (Lane I) snapshot of the exact timeline slots that
 * [AtlasTimelineGallery] currently displays.
 *
 * Lazy-grid keys are positional ([gridLazySaveablePositionKey]) and never
 * carry private MediaStore URIs, so a host that needs "the first visible
 * photo" maps a visible LazyGrid slot index through [timeline]. A host must
 * admit a snapshot only when [source] is the very same list reference it is
 * showing (`===`) and [catalogueEpoch] equals its current publication epoch.
 */
class TimelineDisplayedSlots internal constructor(
    val source: List<MediaRecord>,
    val catalogueEpoch: Long,
    val timeline: GridTimelineIndex
)
