package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import java.util.PriorityQueue

/** One unpersisted, user-selected MediaStore cover. Never an upload/edit/trash command. */
data class AlbumCoverChoice(
    val bucket: AlbumBucketKey,
    val media: MediaRecord,
    val accessEpoch: Long,
    /** Grant generation that issued this receipt; not a publication epoch. */
    val permissionScope: AlbumCoverPermissionScope = AlbumCoverPermissionScope(accessEpoch)
) {
    val coverUri: String get() = media.uri
}

/**
 * An exclusive page boundary, tied to the exact media scope that minted it.
 * It is navigation metadata, not a permission token or persistent bookmark.
 */
data class AlbumCoverPageCursor(
    val takenAtMillis: Long,
    val uri: String,
    val accessEpoch: Long? = null,
    val route: AlbumRoute? = null,
    val permissionScope: AlbumCoverPermissionScope? = null
)

/**
 * An ephemeral, off-main-thread preview backed ONLY by the current authorized
 * media list. No URI alone can establish present-day permission or provenance.
 */
class AlbumCoverChoiceProjection(
    val source: List<MediaRecord>,
    val accessEpoch: Long,
    val route: AlbumRoute,
    val candidates: List<MediaRecord>,
    val olderThan: AlbumCoverPageCursor? = null,
    val hasOlder: Boolean = false,
    val permissionScope: AlbumCoverPermissionScope = AlbumCoverPermissionScope(accessEpoch)
) {
    /** Exclusive next-page cursor, valid only within this inventory and epoch. */
    val nextOlderCursor: AlbumCoverPageCursor? get() =
        if (hasOlder) candidates.lastOrNull()?.let {
            AlbumCoverPageCursor(it.takenAtMillis, it.uri, accessEpoch, route, permissionScope)
        } else null
}

/**
 * Build a bounded newest-first candidate set for ONE exact album bucket and
 * ONE current media-type filter. Intended for an explicit cover-picker action,
 * never for startup or across every folder in a 150k-item gallery.
 *
 * Complexity O(media count * log(limit)); retained memory O(limit + 1) apart
 * from a bounded URI dedup map. A stable exclusive date/URI cursor lets
 * users browse ALL ages without materializing an album-wide thumbnail list.
 * No MediaStore reads, thumbnails, or mutations.
 */
fun buildAlbumCoverChoices(
    source: List<MediaRecord>,
    route: AlbumRoute,
    accessEpoch: Long,
    limit: Int = 36,
    checkActive: (() -> Unit)? = null,
    volumeOf: (MediaRecord) -> String = { it.volumeName },
    olderThan: AlbumCoverPageCursor? = null,
    permissionScope: AlbumCoverPermissionScope = AlbumCoverPermissionScope(accessEpoch)
): AlbumCoverChoiceProjection {
    require(limit in 1..64) { "Album picker limit must be 1..64" }
    require(accessEpoch >= 0L) { "An authorized nonnegative epoch is required" }
    require(olderThan == null ||
        (olderThan.uri.isNotBlank() && olderThan.accessEpoch == accessEpoch &&
            olderThan.route == route &&
            olderThan.permissionScope == permissionScope)) {
        "An older-page cursor must belong to this grant and exact album route"
    }
    val budget = limit + 1 // one extra candidate proves another page exists
    // Shared with the album summary builder; manual cover selection and
    // displayed covers must resolve the same current URI revision.
    val oldestFirst = Comparator<MediaRecord>(::compareAlbumMediaPriority)
    val kept = PriorityQueue(oldestFirst)
    // Only retain metadata for the bounded candidate heap. Providers can
    // duplicate one URI in a catalogue; retain its best current candidate,
    // independent of source iteration order, without growing a 150k URI set.
    val keptByUri = HashMap<String, MediaRecord>(budget * 2)
    source.forEachIndexed { index, item ->
        if ((index and 511) == 0) checkActive?.invoke()
        if (item.relativePath != route.relativePath ||
            volumeOf(item) != route.volumeName ||
            !route.mediaFilter.accepts(item) ||
            item.uri.isBlank() ||
            (olderThan != null && (item.takenAtMillis > olderThan.takenAtMillis ||
                (item.takenAtMillis == olderThan.takenAtMillis &&
                    item.uri >= olderThan.uri)))) return@forEachIndexed

        val earlier = keptByUri[item.uri]
        if (earlier != null) {
            if (oldestFirst.compare(item, earlier) > 0) {
                kept.remove(earlier)
                kept.add(item)
                keptByUri[item.uri] = item
            }
            return@forEachIndexed
        }
        if (kept.size < budget) {
            kept.add(item)
            keptByUri[item.uri] = item
        } else if (oldestFirst.compare(item, kept.peek()) > 0) {
            val evicted = kept.remove()
            keptByUri.remove(evicted.uri)
            kept.add(item)
            keptByUri[item.uri] = item
        }
    }
    checkActive?.invoke()
    val ranked = kept.toList().sortedWith(oldestFirst.reversed())
    val hasOlder = ranked.size > limit
    val candidates = if (hasOlder) ranked.take(limit) else ranked
    checkActive?.invoke()
    return AlbumCoverChoiceProjection(
        source, accessEpoch, route, candidates, olderThan, hasOlder, permissionScope
    )
}

/** Identity and epoch are O(1); do not call List.equals for 150k items. */
fun currentAlbumCoverChoicesOrNull(
    projection: AlbumCoverChoiceProjection?,
    source: List<MediaRecord>,
    accessEpoch: Long,
    route: AlbumRoute,
    olderThan: AlbumCoverPageCursor? = null,
    /** null = conservative default derived from [accessEpoch]; never throws. */
    permissionScope: AlbumCoverPermissionScope? = null
): List<MediaRecord>? {
    // An *OrNull admission must fail closed, not throw, for an invalid epoch:
    // a non-null default AlbumCoverPermissionScope(-1) argument threw first.
    val scope = permissionScope ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch)
        ?: return null
    return projection?.takeIf {
        accessEpoch >= 0L && it.source === source &&
            it.accessEpoch == accessEpoch && it.route == route &&
            it.permissionScope == scope &&
            (olderThan == null || olderThan.permissionScope == scope) &&
            it.olderThan == olderThan
    }?.candidates
}

/**
 * Selection never modifies a preference. I must re-check the actual Android
 * grant and compare the receipt's epoch and source against CURRENT published
 * authority before storing or applying a preferredCoverByBucket override.
 */
fun chooseAlbumCoverOrNull(
    projection: AlbumCoverChoiceProjection?,
    source: List<MediaRecord>,
    accessEpoch: Long,
    route: AlbumRoute,
    candidateUri: String,
    olderThan: AlbumCoverPageCursor? = null,
    /** null = conservative default derived from [accessEpoch]; never throws. */
    permissionScope: AlbumCoverPermissionScope? = null
): AlbumCoverChoice? {
    if (candidateUri.isBlank()) return null
    val scope = permissionScope ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch)
        ?: return null
    val candidates = currentAlbumCoverChoicesOrNull(
        projection, source, accessEpoch, route, olderThan,
        permissionScope = scope) ?: return null
    val chosen = candidates.firstOrNull { it.uri == candidateUri } ?: return null
    return AlbumCoverChoice(
        AlbumBucketKey(route.volumeName, route.relativePath),
        chosen,
        accessEpoch,
        scope
    )
}
