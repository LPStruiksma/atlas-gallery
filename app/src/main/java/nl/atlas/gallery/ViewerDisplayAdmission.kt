package nl.atlas.gallery

/**
 * #505: an open full-screen viewer stays open while Android grants any media
 * access, even when a background scan publishes a newer catalogue. The viewer
 * keeps paging through the list it was opened with; an item that is no longer
 * readable just fails to load. Full access loss still closes it at once.
 *
 * Admission is O(1) even for 150,000 MediaRecords.
 */
internal fun canDisplayAuthorizedViewer(
    viewerSource: List<MediaRecord>?,
    viewerUri: String?,
    viewerAccessEpoch: Long,
    accessGranted: Boolean
): Boolean = accessGranted &&
    !viewerUri.isNullOrBlank() &&
    viewerSource != null &&
    viewerAccessEpoch >= 0L
