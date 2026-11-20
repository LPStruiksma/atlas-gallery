package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord

/**
 * An Activity-only selection snapshot. Never write this to a disk preference.
 * The caller must validate BOTH a restored publication identity and the live
 * Android permission grant before admitting keys to an actionable selection.
 */
internal data class SelectionRotationState(
    val epoch: Long,
    val keys: Set<String>,
    val albumPath: String?,
    val albumVolume: String?,
    val albumFilter: String?,
    val grantBits: String
) {
    companion object {
        const val MAX_KEYS = 500
        fun capture(
            selection: GalleryBatchSelection?,
            path: String?,
            volume: String?,
            filter: String?,
            grantBits: String
        ): SelectionRotationState? {
            val active = selection ?: return null
            if (active.epoch < 0L || active.uris.size > MAX_KEYS) return null
            return SelectionRotationState(active.epoch, active.uris.toSet(), path, volume,
                filter, grantBits)
        }
    }

    /**
     * A mismatched publication is never equivalent to a reload of the same
     * source. Reconciliation is an O(catalogue) operation executed on Default.
     */
    fun restore(
        source: List<MediaRecord>,
        currentEpoch: Long,
        currentGrantBits: String,
        currentPath: String?,
        currentVolume: String?,
        currentFilter: String?,
        authorized: Boolean,
        cancellationCheckpoint: () -> Unit = {}
    ): GalleryBatchSelection? {
        if (!authorized || epoch < 0 || epoch != currentEpoch ||
            grantBits != currentGrantBits ||
            albumPath != currentPath || albumVolume != currentVolume ||
            albumFilter != currentFilter || keys.size > MAX_KEYS ||
            keys.any { it.isBlank() }) return null
        val valid = HashSet<String>()
        source.forEachIndexed { index, item ->
            if ((index and 511) == 0) cancellationCheckpoint()
            if (item.uri in keys) valid.add(item.uri)
        }
        // Empty mode is valid only if it was empty when saved. Missing keys
        // cannot be silently made actionable by a stale publication.
        if (keys.isNotEmpty() && valid.isEmpty()) return null
        return if (valid.isEmpty()) GalleryBatchSelection.empty(source, currentEpoch)
        else GalleryBatchSelection.of(source, currentEpoch, valid)
    }
}
