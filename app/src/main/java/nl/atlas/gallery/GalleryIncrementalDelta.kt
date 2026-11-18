package nl.atlas.gallery

/**
 * A provider row is identified by its physical volume, media kind and numeric ID.
 * Image/video and removable-volume IDs may overlap. No URI parsing or indexing
 * based on filename/relative path is used to decide which existing row changes.
 */
internal data class GalleryMediaDeltaKey(
    val volume: String, val video: Boolean, val id: Long
) {
    companion object {
        fun of(record: MediaRecord): GalleryMediaDeltaKey =
            GalleryMediaDeltaKey(record.volumeName, record.isVideo, record.id)
    }
}

/** null record means a provider-confirmed deletion for this exact identity. */
internal data class GalleryMediaDeltaChange(
    val key: GalleryMediaDeltaKey, val record: MediaRecord?
) {
    init {
        require(key.id > 0L && key.volume.isNotBlank())
        require(record == null || GalleryMediaDeltaKey.of(record) == key)
    }
}

/**
 * Pure, JVM-testable diff. Existing rows retain their position and object
 * identity unless metadata changed. New rows append; established sort/album
 * projections own presentation order and are not altered here.
 *
 * One O(n + changed) copy on a background thread, not a provider re-query.
 * The caller publishes only after rechecking provider generation, observer
 * revision, grant and its current scan/request ownership.
 */
internal fun mergeGalleryMediaDelta(
    previous: List<MediaRecord>,
    changes: List<GalleryMediaDeltaChange>
): List<MediaRecord> {
    if (changes.isEmpty()) return previous
    val pending = LinkedHashMap<GalleryMediaDeltaKey, MediaRecord?>()
    changes.forEach { pending[it.key] = it.record }
    val result = ArrayList<MediaRecord>(previous.size + pending.size)
    var modified = false
    for (before in previous) {
        val key = GalleryMediaDeltaKey.of(before)
        if (pending.containsKey(key)) {
            val now = pending.remove(key)
            if (now == null) modified = true
            else if (before == now) result.add(before)
            else { modified = true; result.add(now) }
        } else {
            result.add(before)
        }
    }
    for (newItem in pending.values) {
        if (newItem != null) { result.add(newItem); modified = true }
    }
    return if (modified) result else previous
}
