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
