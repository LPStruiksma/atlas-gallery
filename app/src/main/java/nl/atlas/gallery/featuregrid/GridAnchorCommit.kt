package nl.atlas.gallery.featuregrid

/**
 * An asynchronous pinch commit is valid only inside the exact immutable
 * inventory publication, view, density and measured viewport that created it.
 * Never resurrect a URI from a revoked catalogue or scroll with stale cell math.
 * Comparing immutable publication keys is O(1), even at 150k photos.
 */
internal data class GridAnchorCommit(
    val publication: GridCatalogIdentity,
    val viewKey: String,
    val density: GridDensity,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val mediaKey: String,
    val mediaIndex: Int,
    val destination: GridScrollTarget
) {
    fun isCurrent(
        currentPublication: GridCatalogIdentity,
        currentViewKey: String,
        currentDensity: GridDensity,
        currentViewportWidthPx: Int,
        currentViewportHeightPx: Int,
        currentMediaKeyAtIndex: String?
    ): Boolean = publication == currentPublication &&
        viewKey == currentViewKey && density == currentDensity &&
        viewportWidthPx == currentViewportWidthPx &&
        viewportHeightPx == currentViewportHeightPx &&
        mediaIndex >= 0 && mediaKey.isNotBlank() &&
        mediaKey == currentMediaKeyAtIndex && destination.firstItemIndex >= 0 &&
        destination.firstItemScrollOffsetPx >= 0
}
