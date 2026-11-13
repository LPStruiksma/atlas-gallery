package nl.atlas.gallery

/**
 * One immutable MediaStore publication: the authorization-validation epoch
 * belongs to these exact records, never to a separately observed StateFlow.
 *
 * Epoch precedes items in the generated data-class equality method, so a
 * newer epoch never traverses a 150k-photo album merely to publish new data.
 * The authoritative UI consumes only this paired snapshot.
 */
internal data class GalleryPublishedInventory(
    val epoch: Long,
    val items: List<MediaRecord>,
    /** Permission bits captured at the beginning of this exact query. */
    val observedGrant: GalleryMediaGrant? = null,
    /** True for the on-disk snapshot shown at cold start before the first scan. */
    val fromCache: Boolean = false
) {
    init {
        require(epoch >= 0L) { "Invalid gallery inventory epoch" }
    }

    /**
     * The grant a real scan was bound to; null for the cold-start cache so
     * the normal resume/permission flow always queues a fresh scan for it.
     */
    val scanBoundGrant: GalleryMediaGrant? get() = if (fromCache) null else observedGrant

    fun satisfies(grantRequest: Long): Boolean =
        grantRequest >= 0L && epoch >= grantRequest

    /**
     * A recreated Activity may see full grants despite the retained ViewModel
     * still carrying records from a narrower, previously selected grant.
     * An unbound (legacy or initial) snapshot is never trusted for display.
     */
    fun matchesGrantedScope(grant: GalleryMediaGrant): Boolean =
        !fromCache && grant.hasAny && observedGrant != null && observedGrant == grant

    /**
     * #505: the gallery opens straight into the last published (or cached)
     * catalogue whenever Android grants any media access, and a background
     * scan swaps in the fresh inventory. Android itself enforces the access
     * boundary: MediaStore only returns permitted rows, and opening a revoked
     * URI fails (an empty tile), so a stale catalogue never exposes pixels the
     * app can no longer read. Only a snapshot that came from a granted scan
     * (or the cache of one) is shown; the empty post-revocation and initial
     * placeholders are not.
     */
    fun showable(grant: GalleryMediaGrant): Boolean =
        grant.hasAny && observedGrant?.hasAny == true

    /** Items for display and derived catalogs (sort, albums, search). O(1). */
    fun displayItems(grant: GalleryMediaGrant): List<MediaRecord> =
        if (showable(grant)) items else emptyList()

    /**
     * Strict accessor: both the grant bits and completed request epoch must
     * match. Kept for checks that want an exact, fresh publication.
     */
    fun authorizedItems(grant: GalleryMediaGrant, requiredEpoch: Long): List<MediaRecord> =
        if (matchesGrantedScope(grant) && satisfies(requiredEpoch)) items else emptyList()
}
