package nl.atlas.gallery

/**
 * The Albums screen renders Loading BEFORE Error. A failed cancellable
 * catalogue worker must therefore NEVER continue advertising Loading,
 * otherwise the explicit Retry action can be hidden behind a permanent spinner.
 *
 * The independent gallery scan's own busy flag is handled separately by I.
 * No MediaStore, IO, Compose or personal media access here.
 */
internal fun shouldWaitForAlbumProjection(
    albumsVisible: Boolean,
    inventoryCount: Int,
    hasProjection: Boolean,
    projectionFailed: Boolean
): Boolean {
    require(inventoryCount >= 0)
    return albumsVisible && inventoryCount > 0 &&
        !hasProjection && !projectionFailed
}

/**
 * #676: Albums shows "Loading albums…" only while it has nothing to show. A
 * background gallery scan ([scanBusy]) keeps the current cards, exactly as
 * Photos keeps its grid (#505): the cold-start rescan after an app update
 * used to hide every album for its whole duration.
 */
internal fun albumsShowLoading(
    scanBusy: Boolean,
    inventoryCount: Int,
    projectionPending: Boolean
): Boolean {
    require(inventoryCount >= 0)
    return projectionPending || (scanBusy && inventoryCount == 0)
}
