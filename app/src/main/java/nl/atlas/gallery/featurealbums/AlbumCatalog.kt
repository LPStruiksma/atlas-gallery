package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import java.text.Normalizer
import java.util.Locale

/** Exact MediaStore RELATIVE_PATH is the navigation key: similarly named folders never merge. */
data class AlbumSummary(
    val relativePath: String,
    val title: String,
    val mediaCount: Int,
    val photoCount: Int,
    val videoCount: Int,
    val coverUri: String?,
    val coverIsVideo: Boolean,
    val latestAtMillis: Long,
    val volumeName: String = "external",
    /** Current authorized source item, never a persisted or free-standing URI cache key. */
    val coverMedia: MediaRecord? = null,
    /** Shown only when otherwise identical album names need distinguishing. */
    val locationLabel: String? = null
)

enum class AlbumSort { RECENT, NAME, COUNT, CUSTOM }

/** A user-visible type filter, never inferred from a folder title or filename. */
enum class AlbumMediaFilter {
    ALL, PHOTOS, VIDEOS;

    fun accepts(media: MediaRecord): Boolean = when (this) {
        ALL -> true
        PHOTOS -> !media.isVideo
        VIDEOS -> media.isVideo
    }
}

/**
 * Route identity includes the filter that produced a cover/count. The caller
 * must apply it when opening the exact-path album, including after Back.
 */
data class AlbumRoute(
    val relativePath: String,
    val mediaFilter: AlbumMediaFilter,
    val volumeName: String = "external"
)

/** The same folder on two mounted volumes is two distinct albums. */
data class AlbumBucketKey(val volumeName: String, val relativePath: String)

/**
 * A completed off-main-thread Albums projection. MediaStore permissions can
 * change while grouping 150k items; consumers must not show a result from
 * any older authorized inventory, sort/filter choice, or cover-preference
 * revision. Source reference identity is intentional, not structural equality.
 */
class AlbumProjectionSnapshot(
    val source: List<MediaRecord>,
    val sort: AlbumSort,
    val mediaFilter: AlbumMediaFilter,
    val coverRevision: Long,
    val albums: List<AlbumSummary>,
    val accessEpoch: Long = 0L,
    /** Monotonic presentation revision; compare in O(1) rather than comparing album paths. */
    val customOrderRevision: Long = 0L
)

/**
 * Constant-time reference identity for a completed Albums presentation.
 * Kotlin List.equals may compare tens of thousands of items on the UI thread;
 * equal-value lists can also be different permission/source publications.
 *
 * This temporary key stays in Compose memory only. The saveable LazyGrid keys
 * remain non-sensitive page-local integer slots.
 */
internal class AlbumPresentationListKey(private val list: List<AlbumSummary>) {
    override fun equals(other: Any?): Boolean =
        other is AlbumPresentationListKey && list === other.list

    override fun hashCode(): Int = System.identityHashCode(list)
}

/** Do not present stale thumbnails after a MediaStore permission change. */
fun currentAlbumProjectionOrNull(
    snapshot: AlbumProjectionSnapshot?,
    source: List<MediaRecord>,
    sort: AlbumSort,
    mediaFilter: AlbumMediaFilter,
    coverRevision: Long,
    accessEpoch: Long = 0L,
    customOrderRevision: Long = 0L
): List<AlbumSummary>? = snapshot?.takeIf {
    it.source === source && it.sort == sort &&
        it.mediaFilter == mediaFilter && it.coverRevision == coverRevision &&
        it.accessEpoch == accessEpoch &&
        it.customOrderRevision == customOrderRevision
}?.albums

private class Accumulator(val key: AlbumBucketKey) {
    var photos = 0
    var videos = 0
    var mostRecent: MediaRecord? = null
    var newestPhoto: MediaRecord? = null
    var requestedCover: MediaRecord? = null

    fun include(media: MediaRecord, requestedUri: String?) {
        if (media.isVideo) videos++ else photos++
        if (newer(media, mostRecent)) mostRecent = media
        if (!media.isVideo && newer(media, newestPhoto)) newestPhoto = media
        // A saved cover is only honoured while the exact item remains accessible
        // in this exact album. Never display stale/revoked content from a cache.
        if (requestedUri != null && requestedUri == media.uri &&
            newer(media, requestedCover)) requestedCover = media
    }

    fun snapshot(): AlbumSummary {
        val cover = requestedCover ?: newestPhoto ?: mostRecent
        return AlbumSummary(
            relativePath = key.relativePath,
            title = albumDisplayName(key.relativePath),
            mediaCount = photos + videos,
            photoCount = photos,
            videoCount = videos,
            coverUri = cover?.uri,
            coverIsVideo = cover?.isVideo == true,
            latestAtMillis = mostRecent?.takenAtMillis ?: 0L,
            volumeName = key.volumeName,
            coverMedia = cover
        )
    }
}

private fun newer(candidate: MediaRecord, current: MediaRecord?): Boolean =
    current == null || compareAlbumMediaPriority(candidate, current) > 0

/** Album names are presentation only; callbacks must use the exact relativePath. */
fun albumDisplayName(relativePath: String): String =
    relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "On device" }

/**
 * One catalogue pass with bounded cancellation checkpoints. The cover defaults
 * to the newest accessible photo, then video. An optional user-selected cover
 * is used ONLY when its URI is still present in the exact album.
 *
 * Does not read, copy, index, or modify any media. Call from a background
 * dispatcher at 150k scale; no Compose frame should rebuild this list.
 */
fun buildAlbumSummaries(
    items: List<MediaRecord>,
    sort: AlbumSort = AlbumSort.RECENT,
    preferredCoverByPath: Map<String, String> = emptyMap(),
    checkActive: (() -> Unit)? = null,
    mediaFilter: AlbumMediaFilter = AlbumMediaFilter.ALL,
    volumeOf: (MediaRecord) -> String = { "external" },
    preferredCoverByBucket: Map<AlbumBucketKey, String> = emptyMap(),
    customOrder: List<AlbumBucketKey> = emptyList()
): List<AlbumSummary> {
    val buckets = HashMap<AlbumBucketKey, Accumulator>()
    items.forEachIndexed { index, media ->
        if ((index and 511) == 0) checkActive?.invoke()
        if (mediaFilter.accepts(media)) {
            val key = AlbumBucketKey(volumeOf(media), media.relativePath)
            buckets.getOrPut(key) { Accumulator(key) }
                .include(media,
                    preferredCoverByBucket[key] ?: preferredCoverByPath[media.relativePath])
        }
    }
    checkActive?.invoke()
    // Normalize each album title exactly once. With thousands of folders,
    // normalizing inside every comparator call is O(albums log albums) Unicode
    // normalization/temporary allocations and causes unnecessary GC churn.
    var normalized = 0
    val entries = buckets.values.map { bucket ->
        if (((normalized++) and 511) == 0) checkActive?.invoke()
        val album = bucket.snapshot()
        SortableAlbum(album, normalizedAlbumKey(album.title))
    }
    checkActive?.invoke()
    // Sorting a hostile catalogue containing tens of thousands of distinct
    // directories must be cancellable too. A permission revocation or newer
    // inventory invalidates this expensive result before publication.
    var comparisons = 0
    val customRanks = customOrder.withIndex().associate { it.value to it.index }
    val byName = Comparator<SortableAlbum> { a, b ->
        compareNormalizedAlbumKeys(a.nameKey, b.nameKey).takeIf { it != 0 }
            ?: a.album.relativePath.compareTo(b.album.relativePath).takeIf { it != 0 }
            ?: a.album.volumeName.compareTo(b.album.volumeName)
    }
    // Check the *outer* comparison. Counting only name-tie comparisons would
    // never observe cancellation while sorting albums with unique dates/sizes.
    val cancellable = Comparator<SortableAlbum> { a, b ->
        when (sort) {
            AlbumSort.RECENT ->
                b.album.latestAtMillis.compareTo(a.album.latestAtMillis).takeIf { it != 0 }
                    ?: byName.compare(a, b)
            AlbumSort.NAME -> byName.compare(a, b)
            AlbumSort.CUSTOM -> {
                val rankA = customRanks[AlbumBucketKey(a.album.volumeName, a.album.relativePath)] ?: Int.MAX_VALUE
                val rankB = customRanks[AlbumBucketKey(b.album.volumeName, b.album.relativePath)] ?: Int.MAX_VALUE
                rankA.compareTo(rankB).takeIf { it != 0 } ?: byName.compare(a, b)
            }
            AlbumSort.COUNT ->
                b.album.mediaCount.compareTo(a.album.mediaCount).takeIf { it != 0 }
                    ?: byName.compare(a, b)
        }
    }
    val ordered = entries.sortedWith(Comparator { a, b ->
        if (((++comparisons) and 1023) == 0) checkActive?.invoke()
        val pinnedA = albumPinRank(a.album.title)
        val pinnedB = albumPinRank(b.album.title)
        if (pinnedA != pinnedB) pinnedA.compareTo(pinnedB)
        else cancellable.compare(a, b)
    })
    checkActive?.invoke()
    // Visually identical album names can refer to two distinct folders or
    // volumes. Resolve just the labels; never alter exact folder route keys.
    val nameCounts = HashMap<String, Int>()
    val pathCounts = HashMap<String, Int>()
    ordered.forEachIndexed { index, entry ->
        if ((index and 511) == 0) checkActive?.invoke()
        nameCounts[entry.nameKey] = (nameCounts[entry.nameKey] ?: 0) + 1
        val path = entry.album.relativePath
        pathCounts[path] = (pathCounts[path] ?: 0) + 1
    }
    checkActive?.invoke()
    val firstPass = ordered.mapIndexed { index, entry ->
        if ((index and 511) == 0) checkActive?.invoke()
        val album = entry.album
        if ((nameCounts[entry.nameKey] ?: 0) <= 1) album
        else album.copy(
            locationLabel = albumLocationContext(
                album.relativePath, album.volumeName,
                samePathAcrossVolumes = (pathCounts[album.relativePath] ?: 0) > 1
            )
        )
    }
    checkActive?.invoke()
    // Siblings differing only in capitalization can share the same parent
    // caption. Escalate ambiguous captions to exact folder+volume instead of
    // silently presenting two visually identical album cards.
    val locations = HashMap<Pair<String, String>, Int>()
    firstPass.forEachIndexed { index, album ->
        if ((index and 511) == 0) checkActive?.invoke()
        album.locationLabel?.let { label ->
            val key = ordered[index].nameKey to label
            locations[key] = (locations[key] ?: 0) + 1
        }
    }
    val snapshots = firstPass.mapIndexed { index, album ->
        if ((index and 511) == 0) checkActive?.invoke()
        val label = album.locationLabel
        if (label == null ||
            (locations[ordered[index].nameKey to label] ?: 0) <= 1) album
        else album.copy(
            locationLabel = album.relativePath.ifBlank { "Storage root" } +
                " · " + albumStorageCaption(album.volumeName)
        )
    }
    checkActive?.invoke()
    return snapshots
}

/** Samsung's first three albums keep their positions regardless of user sort. */
fun albumPinRank(title: String): Int = when (title.trim().lowercase(Locale.ROOT)) {
    "camera" -> 0
    "screenshots" -> 1
    "black and white" -> 2
    else -> 3
}

/**
 * Moves [from] to [to] among unpinned albums only. Returns [order] itself when
 * nothing changes (out of range, a pinned source or target, or the same slot).
 */
fun moveUnpinnedAlbum(order: List<AlbumSummary>, from: Int, to: Int): List<AlbumSummary> {
    if (from !in order.indices || to !in order.indices || from == to) return order
    if (albumPinRank(order[from].title) != 3 || albumPinRank(order[to].title) != 3) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/** The persisted custom order: unpinned albums only, by exact bucket. */
fun customAlbumOrder(order: List<AlbumSummary>): List<AlbumBucketKey> =
    order.filter { albumPinRank(it.title) == 3 }.map { AlbumBucketKey(it.volumeName, it.relativePath) }

private class SortableAlbum(val album: AlbumSummary, val nameKey: String)

/** Location is explanatory text only, never a lookup key or permission. */
private fun albumLocationContext(
    relativePath: String, volumeName: String, samePathAcrossVolumes: Boolean
): String {
    val parent = if (relativePath.isEmpty()) "Storage root"
        else relativePath.trimEnd('/').substringBeforeLast('/', "")
            .ifBlank { "Top-level folder" }
    return if (samePathAcrossVolumes)
        "$parent · ${albumStorageCaption(volumeName)}" else parent
}

private fun albumStorageCaption(volumeName: String): String = when (volumeName) {
    "external_primary" -> "Device storage"
    "external" -> "Shared storage"
    else -> "Storage " + volumeName
}

/**
 * Context line for the chooser. Visible folder names can collide across
 * physical parents and mounted volumes, so an album title alone is not enough
 * for users to verify which folder they are editing.
 *
 * Presentation only: navigation still uses the exact route, never this label.
 * Derived solely from the same authorized folder/volume as the card.
 */
fun albumCoverRouteContext(route: AlbumRoute): String =
    albumDisplayName(route.relativePath) + " · " +
        albumLocationContext(
            route.relativePath, route.volumeName, samePathAcrossVolumes = true
        )

/** Locale-stable, accent-insensitive, case-insensitive natural sorting. */
fun naturalAlbumCompare(left: String, right: String): Int =
    compareNormalizedAlbumKeys(normalizedAlbumKey(left), normalizedAlbumKey(right))

/** Both keys must already be NFD-normalized, case-folded and accent-stripped. */
private fun compareNormalizedAlbumKeys(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val aDigit = a[i] in '0'..'9'
        val bDigit = b[j] in '0'..'9'
        if (aDigit && bDigit) {
            val ai = i
            val bj = j
            while (i < a.length && a[i] in '0'..'9') i++
            while (j < b.length && b[j] in '0'..'9') j++
            val an = a.substring(ai, i).trimStart('0')
            val bn = b.substring(bj, j).trimStart('0')
            // Length-first comparison avoids integer overflow for long filenames.
            if (an.length != bn.length) return an.length.compareTo(bn.length)
            val n = an.compareTo(bn)
            if (n != 0) return n
            val zeroTie = (i - ai).compareTo(j - bj)
            if (zeroTie != 0) return zeroTie
        } else {
            val d = a[i].compareTo(b[j])
            if (d != 0) return d
            i++
            j++
        }
    }
    return if (i == a.length && j == b.length) 0 else
        if (i == a.length) -1 else 1
}

private fun normalizedAlbumKey(s: String): String = buildString {
    for (char in Normalizer.normalize(s, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT)) {
        if (Character.getType(char) != Character.NON_SPACING_MARK.toInt() &&
            Character.getType(char) != Character.COMBINING_SPACING_MARK.toInt() &&
            Character.getType(char) != Character.ENCLOSING_MARK.toInt()) {
            append(char)
        }
    }
}
