package nl.atlas.gallery

/**
 * #63: one shared instance per content-collection prefix
 * (`content://media/external_primary/images/media/` and the like), so a
 * [MediaRecord] keeps a reference plus its id instead of a full URI string.
 * Bounded: past [MAX] distinct prefixes a record keeps its full string.
 */
internal object MediaUriPrefixes {
    private const val MAX = 256
    private val table = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The shared prefix when [uri] is exactly that prefix followed by [id]; else null. */
    fun prefixOf(uri: String, id: Long): String? {
        if (id < 0L) return null
        var digits = 0
        var rest = id
        do { digits++; rest /= 10 } while (rest != 0L)
        val cut = uri.length - digits
        if (cut < 2 || uri[cut - 1] != '/') return null
        // The tail must be exactly the decimal digits of id (no sign, no zero padding).
        var value = id
        for (i in uri.length - 1 downTo cut) {
            if (uri[i] != ('0' + (value % 10).toInt())) return null
            value /= 10
        }
        val prefix = uri.substring(0, cut)
        table[prefix]?.let { return it }
        if (table.size >= MAX) return null
        return table.putIfAbsent(prefix, prefix) ?: prefix
    }
}

/**
 * No Android dependencies; testable on the JVM.
 *
 * #63: behaves as the former data class (same constructor, properties,
 * equals, copy) but does not store a URI string per row: [uri] is rebuilt
 * from a shared collection prefix plus [id] when it has that shape, which
 * every MediaStore row does. A 250k-photo library no longer holds 250k URI
 * strings on the heap. [hashCode] leaves the URI out (allowed: equal records
 * still hash equal) so hashing allocates nothing.
 */
class MediaRecord(
    val id: Long,
    uri: String,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val takenAtMillis: Long,
    val isVideo: Boolean,
    val durationMillis: Long = 0L,
    // From MediaStore.DATE_MODIFIED (seconds), for a fail-closed
    // revalidation before any user-approved Trash request.
    val modifiedAtSeconds: Long = 0L,
    // Per-row MediaStore provenance. Keep trailing defaults for existing tests
    // and all callers constructing a basic MediaRecord.
    val volumeName: String = "external",
    val providerVersion: String? = null,
    val generationModified: Long = 0L,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
    val orientationDegrees: Int = 0,
    val isFavorite: Boolean = false,
    // False alone is not evidence of 'not favourited' on older providers.
    val favoriteKnown: Boolean = false,
    val dateSource: MediaDateSource = MediaDateSource.UNKNOWN
) {
    /** The shared prefix ([uriIsPrefix]) or, for an unusual URI, the whole string. */
    private val uriPart: String
    private val uriIsPrefix: Boolean

    init {
        val prefix = MediaUriPrefixes.prefixOf(uri, id)
        uriPart = prefix ?: uri
        uriIsPrefix = prefix != null
    }

    val uri: String get() = if (uriIsPrefix) uriPart + id else uriPart

    /** Same URI as [other], without building either string when both are prefix + id. */
    fun hasSameUri(other: MediaRecord): Boolean =
        if (uriIsPrefix && other.uriIsPrefix) id == other.id && uriPart == other.uriPart
        else uri == other.uri

    // Legacy V4 reviewed assignments use exact paths. A removable volume
    // must NEVER impersonate /storage/emulated/0 just because its relative
    // folder and filename happen to match a primary-storage photograph.
    // This is a location hint, not permanent media identity; V3 moves need
    // explicit reconciled evidence rather than filename guessing.
    val absolutePath: String
        get() = (if (volumeName == "external_primary" || volumeName == "external")
            "/storage/emulated/0/" else "/storage/" + volumeName + "/") +
            relativePath.trimStart('/') + name
    val album: String get() = relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Root" }

    fun copy(
        id: Long = this.id,
        uri: String = this.uri,
        name: String = this.name,
        relativePath: String = this.relativePath,
        sizeBytes: Long = this.sizeBytes,
        takenAtMillis: Long = this.takenAtMillis,
        isVideo: Boolean = this.isVideo,
        durationMillis: Long = this.durationMillis,
        modifiedAtSeconds: Long = this.modifiedAtSeconds,
        volumeName: String = this.volumeName,
        providerVersion: String? = this.providerVersion,
        generationModified: Long = this.generationModified,
        widthPixels: Int = this.widthPixels,
        heightPixels: Int = this.heightPixels,
        orientationDegrees: Int = this.orientationDegrees,
        isFavorite: Boolean = this.isFavorite,
        favoriteKnown: Boolean = this.favoriteKnown,
        dateSource: MediaDateSource = this.dateSource
    ): MediaRecord = MediaRecord(id, uri, name, relativePath, sizeBytes, takenAtMillis,
        isVideo, durationMillis, modifiedAtSeconds, volumeName, providerVersion,
        generationModified, widthPixels, heightPixels, orientationDegrees, isFavorite,
        favoriteKnown, dateSource)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaRecord) return false
        return id == other.id && sizeBytes == other.sizeBytes &&
            takenAtMillis == other.takenAtMillis && isVideo == other.isVideo &&
            durationMillis == other.durationMillis &&
            modifiedAtSeconds == other.modifiedAtSeconds &&
            generationModified == other.generationModified &&
            widthPixels == other.widthPixels && heightPixels == other.heightPixels &&
            orientationDegrees == other.orientationDegrees &&
            isFavorite == other.isFavorite && favoriteKnown == other.favoriteKnown &&
            dateSource == other.dateSource && name == other.name &&
            relativePath == other.relativePath && volumeName == other.volumeName &&
            providerVersion == other.providerVersion && hasSameUri(other)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + relativePath.hashCode()
        h = 31 * h + sizeBytes.hashCode()
        h = 31 * h + takenAtMillis.hashCode()
        h = 31 * h + isVideo.hashCode()
        h = 31 * h + durationMillis.hashCode()
        h = 31 * h + modifiedAtSeconds.hashCode()
        h = 31 * h + volumeName.hashCode()
        h = 31 * h + (providerVersion?.hashCode() ?: 0)
        h = 31 * h + generationModified.hashCode()
        h = 31 * h + widthPixels
        h = 31 * h + heightPixels
        h = 31 * h + orientationDegrees
        h = 31 * h + isFavorite.hashCode()
        h = 31 * h + favoriteKnown.hashCode()
        h = 31 * h + dateSource.hashCode()
        return h
    }

    override fun toString(): String = "MediaRecord(id=$id, uri=$uri, name=$name, " +
        "relativePath=$relativePath, sizeBytes=$sizeBytes, takenAtMillis=$takenAtMillis, " +
        "isVideo=$isVideo, durationMillis=$durationMillis, " +
        "modifiedAtSeconds=$modifiedAtSeconds, volumeName=$volumeName, " +
        "providerVersion=$providerVersion, generationModified=$generationModified, " +
        "widthPixels=$widthPixels, heightPixels=$heightPixels, " +
        "orientationDegrees=$orientationDegrees, isFavorite=$isFavorite, " +
        "favoriteKnown=$favoriteKnown, dateSource=$dateSource)"
}

/** Samsung Gallery's sort sheet plus ATLAS's size sorts (#514: Largest and Smallest). */
enum class SortOrder { NEWEST, OLDEST, LARGEST, SMALLEST, RANDOM }

/**
 * The UI must never display a stale ordering while an asynchronous sort is
 * still running. Match catalogue identity by reference: equal-looking source
 * lists may be different MediaStore generations with different URI access.
 */
/**
 * Compose effect keys normally use structural equality. MediaStore may
 * produce a new list with equal metadata; an identity-guarded async sort
 * must still restart for that distinct inventory generation.
 */
class CatalogSourceKey(private val source: Any) {
    override fun equals(other: Any?): Boolean =
        other is CatalogSourceKey && source === other.source

    override fun hashCode(): Int = System.identityHashCode(source)
}

class CatalogSortSnapshot(
    val source: List<MediaRecord>,
    val order: SortOrder,
    val seed: Long,
    val sorted: List<MediaRecord>
)

fun currentSortedCatalogOrNull(
    snapshot: CatalogSortSnapshot?,
    source: List<MediaRecord>,
    order: SortOrder,
    seed: Long
): List<MediaRecord>? =
    snapshot?.takeIf {
        it.source === source && it.order == order && it.seed == seed
    }?.sorted

/**
 * A single explicit navigation command for thumbnail taps and coarse-seek
 * release. Each gesture replaces the prior command; even a repeated index
 * gets a fresh sequence so it can cancel an unfinished earlier jump.
 * Indices are scoped strictly to the current immutable viewer list.
 */
internal data class ViewerJumpIntent(val index: Int, val sequence: Long)

internal fun nextViewerJumpIntent(
    previous: ViewerJumpIntent?, index: Int, itemCount: Int
): ViewerJumpIntent {
    require(index in 0 until itemCount) { "Selection must be inside current album" }
    return ViewerJumpIntent(index, (previous?.sequence ?: 0L) + 1L)
}

/**
 * End-of-fling may commit the physically centered tile only if a newer
 * explicit tap/seek has not already superseded that gesture.
 */
internal fun mayCommitFinishedStripGesture(
    wasScrubbing: Boolean,
    coarseSeeking: Boolean,
    controlledScroll: Boolean,
    newerExplicitIntent: ViewerJumpIntent?
): Boolean = wasScrubbing && !coarseSeeking && !controlledScroll &&
    newerExplicitIntent == null

/**
 * A canceled Compose sort should relinquish its CPU, not merely suppress its
 * finished UI result. Check once before/after, and every 1024 comparisons
 * during TimSort; the null callback takes the original comparator fast path.
 * Caller callback may throw CancellationException (e.g. coroutine ensureActive).
 */
private fun <T> Comparator<T>.withSortCancellation(
    cancellationCheckpoint: (() -> Unit)?
): Comparator<T> {
    if (cancellationCheckpoint == null) return this
    var comparisons = 0
    return Comparator { first, second ->
        if ((++comparisons and 1023) == 0) cancellationCheckpoint()
        compare(first, second)
    }
}

/**
 * Stable order for a seed; optional cooperative cancellation for 150k media.
 * The checkpoint does not change ordering, source objects, or collection
 * identity. An abort throws before the caller receives any partial list.
 */
fun sortMedia(
    items: List<MediaRecord>, order: SortOrder, seed: Long,
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> {
    cancellationCheckpoint?.invoke()
    val result = when (order) {
        SortOrder.NEWEST -> items.sortedWith(
            compareByDescending<MediaRecord> { it.takenAtMillis }
                .thenByDescending { it.id }.thenBy { it.uri }
                .withSortCancellation(cancellationCheckpoint))
        SortOrder.OLDEST -> items.sortedWith(
            compareBy<MediaRecord> { it.takenAtMillis }
                .thenBy { it.id }.thenBy { it.uri }
                .withSortCancellation(cancellationCheckpoint))
        SortOrder.LARGEST -> items.sortedWith(
            compareByDescending<MediaRecord> { it.sizeBytes }
                .thenBy { it.id }.thenBy { it.uri }
                .withSortCancellation(cancellationCheckpoint))
        SortOrder.SMALLEST -> items.sortedWith(
            compareBy<MediaRecord> { it.sizeBytes }
                .thenBy { it.id }.thenBy { it.uri }
                .withSortCancellation(cancellationCheckpoint))
        // Decorate exactly once per item; do not hash in the comparator.
        // Check allocation and projection loops as well as TimSort itself.
        SortOrder.RANDOM -> items.mapIndexed { index, item ->
            if ((index and 511) == 0) cancellationCheckpoint?.invoke()
            SeededMedia(item, shuffledKey(item.randomShuffleDiscriminator(), seed))
        }.sortedWith(
            compareBy<SeededMedia> { it.key }
                .thenBy { it.media.id }
                .thenBy { it.media.isVideo }
                .thenBy { it.media.uri }
                .withSortCancellation(cancellationCheckpoint))
            .mapIndexed { index, item ->
                if ((index and 511) == 0) cancellationCheckpoint?.invoke()
                item.media
            }
    }
    cancellationCheckpoint?.invoke()
    return result
}

/** Lightweight temporary decoration for one seed-specific random sort. */
private data class SeededMedia(val media: MediaRecord, val key: Long)

private fun shuffledKey(id: Long, seed: Long): Long {
    var x = id xor seed
    x = (x xor (x ushr 33)) * -49064778989728563L
    x = (x xor (x ushr 33)) * -4265267296055464877L
    return x xor (x ushr 33)
}

fun selectAlbum(
    items: List<MediaRecord>,
    relativePath: String?,
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> = if (relativePath == null) items else
    items.filterIndexed { index, media ->
        if ((index and 511) == 0) cancellationCheckpoint?.invoke()
        media.relativePath == relativePath
    }.also { cancellationCheckpoint?.invoke() }

/** Selected-album photo projection with a stable source and exact path. */
class AlbumSelectionSnapshot(
    val source: List<MediaRecord>,
    val relativePath: String?,
    val selected: List<MediaRecord>
)

fun currentAlbumSelectionOrNull(
    snapshot: AlbumSelectionSnapshot?,
    source: List<MediaRecord>,
    relativePath: String?
): List<MediaRecord>? = snapshot?.takeIf {
    it.source === source && it.relativePath == relativePath
}?.selected

/**
 * Off-main-thread album navigation projection. Preserve the *exact* MediaStore
 * relative paths and natural String order used by the old synchronous
 * `map().distinct().sorted()` implementation. Check cancellation while
 * scanning a large inventory, without producing half-populated UI snapshots.
 */
fun albumPaths(
    items: List<MediaRecord>,
    cancellationCheckpoint: (() -> Unit)? = null
): List<String> {
    val paths = HashSet<String>()
    items.forEachIndexed { index, item ->
        if ((index and 511) == 0) cancellationCheckpoint?.invoke()
        paths.add(item.relativePath)
    }
    cancellationCheckpoint?.invoke()
    return paths.sorted()
}

/** Never display stale albums from a different MediaStore inventory generation. */
class AlbumCatalogSnapshot(
    val source: List<MediaRecord>, val paths: List<String>
)

fun currentAlbumCatalogOrNull(
    snapshot: AlbumCatalogSnapshot?, media: List<MediaRecord>
): List<String>? = snapshot?.takeIf { it.source === media }?.paths

fun filterMedia(
    items: List<MediaRecord>,
    query: String,
    peoplePaths: Set<String> = emptySet(),
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> {
    val q = query.trim().lowercase()
    return if (q.isBlank()) items else items.filterIndexed { index, asset ->
        // Check cancellation infrequently enough not to penalize matching,
        // but often enough not to burn CPU on superseded typed queries.
        if ((index and 511) == 0) cancellationCheckpoint?.invoke()
        asset.name.lowercase().contains(q) ||
            asset.album.lowercase().contains(q) ||
            // Reviewed V4 CSV paths describe confirmed IMAGES, not video.
            // Avoid building a path String for every unrelated 150k-row text
            // search when no reviewed People names matched the query.
            (!asset.isVideo && peoplePaths.isNotEmpty() &&
                asset.absolutePath in peoplePaths)
    }
}

/**
 * Search results are a read-only projection of one gallery generation and one
 * imported confirmed-People generation. Preserve filename/album matching and
 * exact V4 image paths; never infer or merge identities by matching names.
 */
class CatalogSearchSnapshot(
    val source: List<MediaRecord>,
    val people: List<PersonEntry>,
    val query: String,
    val matches: List<MediaRecord>
)

fun currentSearchCatalogOrNull(
    result: CatalogSearchSnapshot?,
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    query: String
): List<MediaRecord>? = result?.takeIf {
    it.source === media && it.people === people && it.query == query
}?.matches

fun searchCatalog(
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    query: String,
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> {
    if (query.isBlank()) return media
    val term = query.trim()
    val paths = people.asSequence()
        .filter { it.name.contains(term, ignoreCase = true) }
        .flatMap { it.imagePaths.asSequence() }
        .toSet()
    cancellationCheckpoint?.invoke()
    return filterMedia(media, query, paths, cancellationCheckpoint)
}

/**
 * Precisely identify V3's destination folder, including intentional child
 * folders but never similarly named neighboring folders. This is a LOCATION
 * filter, not an assertion that every file is true monochrome.
 */
fun isInBlackAndWhiteFolder(relativePath: String): Boolean {
    val folder = relativePath.replace('\\', '/').trimStart('/').trimEnd('/')
    val root = "DCIM/Black and White"
    return folder == root || folder.startsWith("$root/")
}

/** A confirmed V4 membership is required; no person is inferred from imagery. */
fun selectPersonPhotos(
    items: List<MediaRecord>,
    confirmedPaths: Set<String>,
    blackAndWhiteFolderOnly: Boolean = false,
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> = items.filterIndexed { index, item ->
    if ((index and 511) == 0) cancellationCheckpoint?.invoke()
    // A provider can report a video with the same relative path/name as
    // a confirmed V4 photo. A path collision must not grant People identity.
    !item.isVideo && item.absolutePath in confirmedPaths &&
        (!blackAndWhiteFolderOnly || isInBlackAndWhiteFolder(item.relativePath))
}.also { cancellationCheckpoint?.invoke() }

/**
 * Approved People membership is always from the exact supplied reviewed
 * person ID and known paths. Never derive person identity from filenames,
 * portraits, clothing, album labels or context.
 */
class PersonAlbumSnapshot(
    val source: List<MediaRecord>,
    val people: List<PersonEntry>,
    val personId: Long,
    val blackAndWhiteFolderOnly: Boolean,
    val selected: List<MediaRecord>
)

fun currentPersonAlbumOrNull(
    result: PersonAlbumSnapshot?,
    source: List<MediaRecord>,
    people: List<PersonEntry>,
    personId: Long?,
    blackAndWhiteFolderOnly: Boolean
): List<MediaRecord>? = result?.takeIf {
    it.source === source && it.people === people &&
        it.personId == personId &&
        it.blackAndWhiteFolderOnly == blackAndWhiteFolderOnly
}?.selected

/**
 * Lightweight, exact confirmed-People overview. Only metadata for paths
 * present in the explicitly imported reviewed People roster is retained:
 * no all-media O(150k) HashMap in a Compose frame or in the UI snapshot.
 *
 * Video rows are excluded: a V4 reviewed path authenticates a photo only.
 * Duplicate photo absolute paths keep the LAST MediaStore row, matching the
 * previous `media.associateBy { it.absolutePath }` cover behavior. No paths
 * are inferred from names, folder labels, or visual similarity.
 */
class PeopleCoversSnapshot(
    val media: List<MediaRecord>,
    val people: List<PersonEntry>,
    val coversByPath: Map<String, MediaRecord>,
    val coversByPersonId: Map<Long, MediaRecord>,
    val accessibleCounts: Map<Long, Int>
)

fun currentPeopleCoversOrNull(
    result: PeopleCoversSnapshot?,
    media: List<MediaRecord>,
    people: List<PersonEntry>
): PeopleCoversSnapshot? = result?.takeIf {
    it.media === media && it.people === people
}

fun buildPeopleCovers(
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    cancellationCheckpoint: (() -> Unit)? = null
): PeopleCoversSnapshot {
    val confirmed = HashSet<String>()
    people.forEachIndexed { index, person ->
        if ((index and 31) == 0) cancellationCheckpoint?.invoke()
        confirmed.addAll(person.imagePaths)
    }
    cancellationCheckpoint?.invoke()
    val linked = HashMap<String, MediaRecord>()
    media.forEachIndexed { index, item ->
        if ((index and 511) == 0) cancellationCheckpoint?.invoke()
        // Never count or show a video as an imported, reviewed V4 photograph.
        if (!item.isVideo) {
            val path = item.absolutePath
            if (path in confirmed) linked[path] = item
        }
    }
    cancellationCheckpoint?.invoke()
    val present = linked.keys
    val counts = HashMap<Long, Int>(people.size)
    val personCovers = HashMap<Long, MediaRecord>()
    people.forEachIndexed { index, person ->
        if ((index and 31) == 0) cancellationCheckpoint?.invoke()
        counts[person.id] = person.imagePaths.count { it in present }
        // Match the original per-row firstNotNullOfOrNull iteration order,
        // but do it here, away from Compose's draw/recomposition path.
        person.imagePaths.firstNotNullOfOrNull { linked[it] }?.let {
            personCovers[person.id] = it
        }
    }
    cancellationCheckpoint?.invoke()
    return PeopleCoversSnapshot(media, people, linked, personCovers, counts)
}

/** Exact, read-only coverage check for imported V4 confirmed photo references. */
fun countAccessiblePersonPhotos(personPaths: Set<String>, accessiblePaths: Set<String>): Int =
    personPaths.count { it in accessiblePaths }

/** On selection, keep the same source ordering even if the gallery's filters later change. */
fun selectionIndex(items: List<MediaRecord>, uri: String): Int = items.indexOfFirst { it.uri == uri }

/** RFC 4180-like CSV parser: quoted commas, embedded newlines, escaped quotes, CRLF. */
/**
 * Verify that the dedicated V4 live-sync file was written in full.
 * Legacy one-off CSV imports do not have this footer and remain supported
 * through the separate Import V4 button.
 */
fun validatedLiveV4Csv(text: String): String {
    val marker = "#ATLAS_SYNC_END,"
    val markerPos = text.lastIndexOf(marker)
    require(markerPos > 0 && text[markerPos - 1] == '\n') {
        "Live V4 export is incomplete or not a sync-enabled CSV"
    }
    val body = text.substring(0, markerPos)
    val tail = text.substring(markerPos).trimEnd('\r', '\n')
    val footer = parseCsv(tail).singleOrNull()
    require(footer != null && footer.size == 3 && footer[0] == "#ATLAS_SYNC_END") {
        "Invalid V4 sync-completion marker"
    }
    val reported = footer[1].toIntOrNull()
    val rows = parseCsv(body)
    require(reported != null && reported >= 0 && reported == rows.size - 1) {
        "V4 export row count did not match; retaining existing People"
    }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    require(footer[2] == digest) {
        "V4 export checksum mismatch; retaining existing People"
    }
    return body
}

fun parseCsv(csv: String): List<List<String>> {
    val result = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < csv.length) {
        val c = csv[i]
        when {
            c == '"' && quoted && i + 1 < csv.length && csv[i + 1] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { row.add(field.toString()); field.clear() }
            (c == '\n' || c == '\r') && !quoted -> {
                row.add(field.toString()); field.clear()
                if (row.any { it.isNotEmpty() }) result.add(row)
                row = mutableListOf()
                if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
            }
            else -> field.append(c)
        }
        i++
    }
    require(!quoted) { "CSV contains an unfinished quoted field" }
    row.add(field.toString())
    if (row.any { it.isNotEmpty() }) result.add(row)
    return result
}

/**
 * Small, bounded warm-cache window for the native viewer. Only neighboring
 * previews are decoded; a rapid scrub cancels the caller's pending work.
 * Closest items first, alternating forward and backward; no whole-album scans.
 */
fun nearbyPreviewIndices(focus: Int, count: Int, radius: Int = 2): List<Int> {
    if (count <= 1 || focus !in 0 until count || radius <= 0) return emptyList()
    return (1..radius.coerceAtMost(4)).flatMap { distance ->
        listOf(focus + distance, focus - distance).filter { it in 0 until count }
    }
}

/**
 * Prioritize the user's most recently traversed direction after the viewer
 * settles. Reuse the existing bounded neighboring-thumbnail window; no
 * catalogue scan, extra bitmap budget or change to source item ordering.
 *
 * This only changes the order of prefetch requests, not which photo is shown.
 * With no valid previous focus, retain the original symmetric order.
 */
fun nearbyPreviewIndicesForMotion(
    focus: Int, previousFocus: Int, count: Int, radius: Int = 2
): List<Int> {
    if (count <= 1 || focus !in 0 until count || radius <= 0) return emptyList()
    val direction = when {
        previousFocus !in 0 until count || previousFocus == focus -> 0
        focus > previousFocus -> 1
        else -> -1
    }
    if (direction == 0) return nearbyPreviewIndices(focus, count, radius)
    return (1..radius.coerceAtMost(4)).flatMap { distance ->
        listOf(focus + direction * distance, focus - direction * distance)
            .filter { it in 0 until count }
    }
}

/** Single-source bounds for external scrub controls; never wrap at either end. */
fun clampedScrubTarget(requestedIndex: Int, itemCount: Int): Int {
    require(itemCount > 0) { "Cannot scrub an empty collection" }
    return requestedIndex.coerceIn(0, itemCount - 1)
}

/** Fractional coarse-jump mapping, independent of display density and photo count. */
fun seekByFraction(fraction: Float, itemCount: Int): Int {
    require(itemCount > 0) { "Cannot seek an empty collection" }
    if (!fraction.isFinite()) return 0
    return (fraction.coerceIn(0f, 1f) * (itemCount - 1)).toInt()
}
