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

/** #602: more changed rows than this since the checkpoint: a full scan is simpler. */
internal const val COLD_DELTA_MAX_CHANGES = 4096

/** #602: a saved checkpoint a cold-start delta may start from (API 30+ shape). */
internal fun coldDeltaCheckpointUsable(checkpoint: GalleryMediaCheckpoint): Boolean =
    checkpoint.versions.isNotEmpty() &&
        checkpoint.versions.values.all { !it.isNullOrBlank() } &&
        checkpoint.generations.keys == checkpoint.versions.keys &&
        checkpoint.generations.values.all { it != null && it >= 0L }

/**
 * #602: the provider now is the provider the checkpoint was taken from: the
 * same mounted volumes with the same versions (no reset) and no generation
 * behind the saved one.
 */
internal fun coldDeltaMayResume(
    checkpoint: GalleryMediaCheckpoint,
    versions: Map<String, String?>,
    generations: Map<String, Long?>
): Boolean = coldDeltaCheckpointUsable(checkpoint) &&
    versions == checkpoint.versions && generations.keys == versions.keys &&
    generations.all { (volume, now) ->
        val saved = checkpoint.generations[volume]
        now != null && saved != null && now >= saved
    }

/** #602: every cached row whose identity the provider no longer lists. */
internal fun coldDeltaDeletions(
    previous: List<MediaRecord>, present: Set<GalleryMediaDeltaKey>
): List<GalleryMediaDeltaChange> = previous.mapNotNull { record ->
    val key = GalleryMediaDeltaKey.of(record)
    if (key in present) null else GalleryMediaDeltaChange(key, null)
}

/**
 * #602: one row of the cold delta's presence pass, in the full scan's own
 * order (`DATE_ADDED DESC` per collection). [changed] is true when the row's
 * GENERATION_ADDED or GENERATION_MODIFIED is above the saved checkpoint.
 */
internal data class GalleryColdPresence(val key: GalleryMediaDeltaKey, val changed: Boolean)

/**
 * #602: the catalogue a full scan would list, in its order (images, then
 * videos, each as the provider sorted them), built from the presence pass,
 * the re-read [changed] rows and the cached [previous] rows. Rows the
 * presence pass did not see but [changed] holds (added during the read) go
 * first in their collection, as the newest. Null when a present row has no
 * record at all, or a changed one was not re-read: the caller then runs the
 * full scan. Returns [previous] itself when nothing differs.
 */
internal fun assembleColdDelta(
    previous: List<MediaRecord>,
    images: List<GalleryColdPresence>,
    videos: List<GalleryColdPresence>,
    changed: Map<GalleryMediaDeltaKey, MediaRecord>
): List<MediaRecord>? {
    val cached = HashMap<GalleryMediaDeltaKey, MediaRecord>(previous.size * 2)
    for (record in previous) cached[GalleryMediaDeltaKey.of(record)] = record
    val seen = HashSet<GalleryMediaDeltaKey>(images.size + videos.size)
    images.forEach { seen += it.key }
    videos.forEach { seen += it.key }
    val result = ArrayList<MediaRecord>(images.size + videos.size + changed.size)
    for ((video, presence) in listOf(false to images, true to videos)) {
        for ((key, record) in changed) {
            if (key.video == video && key !in seen) result += record
        }
        for (row in presence) {
            val record = if (row.changed) changed[row.key]
                else changed[row.key] ?: cached[row.key]
            result += record ?: return null
        }
    }
    if (result.size == previous.size &&
        result.indices.all { result[it] === previous[it] }) return previous
    return result
}
