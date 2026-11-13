package nl.atlas.gallery.featuregrid

/**
 * A deferred timeline seek is authorized only for its exact immutable inventory,
 * grouping/index instance and actually mounted LazyGrid slot count. In particular,
 * media indexes and header-inclusive slot indexes must never be interchanged.
 * All comparisons are O(1), including for 150,000-photo catalogues.
 */
internal data class TimelineScrubCommit(
    val publication: GridCatalogIdentity,
    val layoutToken: Any,
    val slotIndex: Int,
    val expectedSlotCount: Int,
    /** Keep the released scrub preview steady until its exact seek settles. */
    val requestedFraction: Float? = null
) {
    fun isCurrent(
        currentPublication: GridCatalogIdentity,
        currentLayoutToken: Any?,
        mountedSlotCount: Int
    ): Boolean = publication == currentPublication &&
        layoutToken === currentLayoutToken && expectedSlotCount > 0 &&
        expectedSlotCount == mountedSlotCount && slotIndex in 0 until expectedSlotCount

    /**
     * A still-pending seek owns the date bubble/thumb position until the grid
     * actually moves. On a new publication, regroup, or unmounted slot layout,
     * fall back immediately to the live grid instead of previewing stale data.
     * This is O(1) and never probes any of the 150k photo records.
     */
    fun heldPreviewFractionOrNull(
        currentPublication: GridCatalogIdentity,
        currentLayoutToken: Any?,
        mountedSlotCount: Int
    ): Float? = requestedFraction?.takeIf {
        it.isFinite() && it in 0f..1f &&
            isCurrent(currentPublication, currentLayoutToken, mountedSlotCount)
    }
}
