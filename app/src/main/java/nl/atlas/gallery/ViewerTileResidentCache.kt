package nl.atlas.gallery

/**
 * Pure resource ownership for a future tiled viewer. Owns only the resources
 * accepted into this cache; callers hand ownership to [offer] even on failure.
 *
 * - Never stores images/keys beyond the current authorized viewport.
 * - Revocation, source replacement, editor revision or viewer close disposes
 *   resources immediately, not merely hiding them.
 * - Disposes superseded LRU entries before accepting new decoded buffers.
 * - Runs on one owner thread (normally Compose's UI publication owner).
 *
 * This is intentionally generic: tests own synthetic tokens; a real Android
 * adapter would supply Bitmap.recycle() as dispose and its *actual* allocation
 * size, including rotation copies. No Bitmap or file work occurs in this class.
 */
internal class ViewerTileResidentCache<T : Any>(
    private val capacityBytes: Long = 16L * 1024 * 1024,
    private val dispose: (T) -> Unit
) {
    init { require(capacityBytes in 1L..(64L * 1024 * 1024)) }

    private data class Resident<T>(val value: T, val allocatedBytes: Long)
    private val residents = LinkedHashMap<ViewerTileKey, Resident<T>>(16, .75f, true)
    private var authorizedSource: ViewerTileSource? = null
    private var visibleKeys: Set<ViewerTileKey> = emptySet()

    internal var usedBytes: Long = 0L
        private set

    /** Number of unexpected disposer failures; no URI, object or bitmap is logged. */
    internal var disposalFailures: Int = 0
        private set

    internal val size: Int get() = residents.size

    /** New trusted catalogue epoch/viewport; null means permission withdrawn. */
    fun show(source: ViewerTileSource?, activeKeys: Set<ViewerTileKey>) {
        val valid = source?.takeIf {
            it.uri.isNotBlank() && it.grantEpoch > 0L && it.contentRevision >= 0L
        }
        if (authorizedSource != valid) clearOwned()
        authorizedSource = valid
        // A forged oversized viewport is not partially accepted. Every
        // published resource must have one unambiguous, complete active set.
        visibleKeys = if (valid == null || activeKeys.size > 12) emptySet()
            else activeKeys.filterTo(LinkedHashSet()) { it.source == valid }
        // Detach *all* stale references first; a failed or reentrant cleanup
        // callback must never expose another unauthorized resident.
        val stale = ArrayList<T>()
        val it = residents.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!viewerTileMayPublish(e.key, valid, visibleKeys)) {
                it.remove()
                usedBytes -= e.value.allocatedBytes
                stale += e.value.value
            }
        }
        stale.forEach(::safeDispose)
    }

    /**
     * Transfer a decoded resource to the cache. Invalid or oversized results
     * are disposed immediately. A duplicate object at the same key is retained
     * unchanged, avoiding accidentally recycling a live Bitmap twice.
     */
    fun offer(key: ViewerTileKey, value: T, actualAllocatedBytes: Long): Boolean {
        if (!viewerTileMayPublish(key, authorizedSource, visibleKeys) ||
            actualAllocatedBytes <= 0L || actualAllocatedBytes > capacityBytes
        ) {
            // A misbehaving decoder may return the *same* resource object
            // under a different tile key. Do not dispose a live owned object.
            if (residents.values.none { it.value === value }) safeDispose(value)
            return false
        }
        val existing = residents[key]
        if (existing?.value === value) return true
        // Do not store the same Bitmap/token under two keys: the first tile's
        // eviction would recycle bytes still returned by the second key.
        // The decoder should always create independently owned tile buffers.
        if (residents.values.any { it.value === value }) return false
        if (existing != null) {
            residents.remove(key)
            usedBytes -= existing.allocatedBytes
            safeDispose(existing.value)
        }
        while (usedBytes > capacityBytes - actualAllocatedBytes) {
            val eldest = residents.entries.iterator()
            if (!eldest.hasNext()) break
            val item = eldest.next()
            eldest.remove()
            usedBytes -= item.value.allocatedBytes
            safeDispose(item.value.value)
        }
        residents[key] = Resident(value, actualAllocatedBytes)
        usedBytes += actualAllocatedBytes
        return true
    }

    /** Never return an image from a changed photo, revoked grant or viewport. */
    fun get(key: ViewerTileKey): T? =
        if (viewerTileMayPublish(key, authorizedSource, visibleKeys))
            residents[key]?.value
        else null

    /** Use only keys actually resident after admission and LRU eviction. */
    fun residentKeys(): Set<ViewerTileKey> = residents.keys.toSet()

    /** Call on close, onTrimMemory or when the caller loses authorization. */
    fun close() {
        authorizedSource = null
        visibleKeys = emptySet()
        clearOwned()
    }

    private fun clearOwned() {
        // Remove all references BEFORE callbacks, so a failing external
        // disposer cannot leave an inaccessible resource resident in cache.
        val old = residents.values.toList()
        residents.clear()
        usedBytes = 0L
        old.forEach { safeDispose(it.value) }
    }

    private fun safeDispose(value: T) {
        try {
            dispose(value)
        } catch (_: RuntimeException) {
            // A vendor-side Bitmap.recycle exception must not leave other
            // stale resources accessible or prevent revocation cleanup.
            disposalFailures++
        }
    }
}
