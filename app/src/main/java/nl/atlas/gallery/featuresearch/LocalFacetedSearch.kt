package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import nl.atlas.gallery.featurepeopleui.IndexedFaceCount
import java.util.TimeZone

enum class SearchMediaKind { ALL, PHOTOS, VIDEOS }

const val MAX_SEARCH_QUERY_CHARS = 160
const val MAX_SEARCH_QUERY_TERMS = 12

/**
 * Keep one character beyond the engine limit. Silently clipping a pasted
 * query to exactly the allowed length could DROP restrictive terms and show
 * broader results than the user asked for. The 161st character preserves the
 * rejected-over-limit state without retaining an unbounded paste in Compose.
 */
fun boundSearchQueryInput(input: String): String =
    input.take(MAX_SEARCH_QUERY_CHARS + 1)

/**
 * MediaStore location, not merely the displayed album name. These two fields
 * must come from the same authorized MediaRecord. An unqualified folder path
 * cannot prove it belongs to a specific external volume.
 */
data class SearchAlbumLocation(val volumeName: String, val relativePath: String) {
    init { require(volumeName.isNotBlank()) }
}

data class LocalSearchFacets(
    val text: String = "",
    /** AND: every selected, separately confirmed Person must occur in a photo. */
    val confirmedPersonIds: Set<Long> = emptySet(),
    val kind: SearchMediaKind = SearchMediaKind.ALL,
    /** Actual relative paths, not folder display names. */
    val albumPaths: Set<String> = emptySet(),
    /** Only when independently bound sidecar total counts are available. */
    val onlyTheseIndexedFaces: Boolean = false,
    /** Precise (provider volume, relative folder) selections. OR within albums. */
    val albumLocations: Set<SearchAlbumLocation> = emptySet(),
    /** Absolute local-calendar interval; null means no date restriction. */
    val dateWindow: SearchDateWindow? = null,
    /**
     * #554: native People the user picked from a name suggestion (opaque
     * PersonKey values). AND: every picked person must be in the photo.
     */
    val nativePersonKeys: Set<String> = emptySet()
)

/** A facet is active independently of the entered lexical search words. */
fun LocalSearchFacets.hasActiveFilterFacets(): Boolean =
    confirmedPersonIds.isNotEmpty() || kind != SearchMediaKind.ALL ||
        albumPaths.isNotEmpty() || albumLocations.isNotEmpty() ||
        onlyTheseIndexedFaces || dateWindow != null

/** Explicit date clear must keep the query and every other chosen facet. */
fun LocalSearchFacets.clearDateFilter(): LocalSearchFacets = copy(dateWindow = null)

/**
 * The user's "clear filters" command is distinct from "reset search".
 * Preserve the complete lexical query verbatim, including any invalid
 * over-limit sentinel (never truncate restrictive words into a broad match).
 * All typed album, reviewed-People, face-count, kind and date constraints go.
 */
fun LocalSearchFacets.clearFiltersKeepingQuery(): LocalSearchFacets =
    LocalSearchFacets(text = text)

/**
 * Rebuild on worker thread only. The media source has already been restricted
 * to the current Android permission epoch by I; never pass a stale source.
 */
fun searchWithFacets(
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    filters: LocalSearchFacets,
    /** URI-keyed verified counts: never copy unbound raw V4 image-path totals. */
    verifiedFaceCountsByUri: Map<String, IndexedFaceCount> = emptyMap(),
    /** Native permission-scope generation from Lane I; null disallows face totals. */
    currentPermissionEpoch: String? = null,
    /** Lane M full source identity/revision, mapped by authorized URI. */
    currentMediaRevisionsByUri: Map<String, String> = emptyMap(),
    /** Lane M/I must inject the exact provider volume; null denies typed facets. */
    mediaVolumeOf: (MediaRecord) -> String? = { null },
    cancellationCheckpoint: () -> Unit = {},
    /**
     * #554: authorized photo URIs per picked native person, resolved by the
     * caller under the current grant. A picked key without an entry fails
     * closed (no results) rather than ignoring that person.
     */
    nativePersonUris: Map<String, Set<String>> = emptyMap(),
    /** Calendar zone for year/month words ("2019", "june"). */
    timeZone: TimeZone = TimeZone.getDefault()
): List<MediaRecord> {
    val pickedPersonUris = filters.nativePersonKeys.map {
        nativePersonUris[it] ?: return emptyList()
    }.sortedBy { it.size }
    val personsById = people.associateBy { it.id }
    // Confirmed People IDs are identity keys, never a last-write-wins map.
    // A corrupt/imported duplicate ID must not silently redefine ownership.
    // Keep ordinary filename/album search available even with a bad roster.
    val reviewedRosterUnambiguous = personsById.size == people.size
    if (!reviewedRosterUnambiguous && filters.confirmedPersonIds.isNotEmpty())
        return emptyList()
    if (filters.confirmedPersonIds.any { it !in personsById }) return emptyList()
    if (filters.onlyTheseIndexedFaces && filters.confirmedPersonIds.isEmpty()) return emptyList()
    val selectedPaths = filters.confirmedPersonIds
        .mapNotNull { personsById[it]?.imagePaths }
        .sortedBy { it.size }
    // Bounded input matters at 150k records: one path bitset supports every
    // person-name token instead of allocating a 150k-entry set PER token.
    // If a programmatic query exceeds the cap, reject it; NEVER silently
    // discard words and broaden the results.
    // Same lexical admission as suggestions and session recents: rejecting an
    // overlong, >12-term, or combining-mark-only query must not silently
    // broaden results by dropping the unusable restriction.
    val words = admittedSearchQueryWordsOrNull(filters.text) ?: return emptyList()
    // A year or full month name also matches by the photo's date, so
    // "Susanne 2019" means her photos taken in 2019. Text matches still count.
    val dateTokens = words.map(::searchDateToken)
    val dateMatcher = if (dateTokens.any { it != null }) SearchDateMatcher(timeZone) else null
    val personNameMaskByPath = HashMap<String, Int>()
    words.forEachIndexed { wordIndex, word ->
        val bit = 1 shl wordIndex
        if (!reviewedRosterUnambiguous) return@forEachIndexed
        people.forEachIndexed { personIndex, person ->
            if ((personIndex and 31) == 0) cancellationCheckpoint()
            if (plainSearchText(person.name).contains(word)) {
                person.imagePaths.forEachIndexed { pathIndex, path ->
                    if ((pathIndex and 511) == 0) cancellationCheckpoint()
                    personNameMaskByPath[path] =
                        (personNameMaskByPath[path] ?: 0) or bit
                }
            }
        }
    }
    // Confirmed CSV paths may be ambiguous after multi-volume enumeration.
    // Never treat a path shared by two different native media URIs as proof
    // that either is the reviewed photo.
    val relevantPaths = HashSet<String>()
    selectedPaths.forEach { relevantPaths.addAll(it) }
    relevantPaths.addAll(personNameMaskByPath.keys)
    val firstUriByPath = HashMap<String, String>()
    val ambiguousPaths = HashSet<String>()
    if (relevantPaths.isNotEmpty()) media.forEachIndexed { index, item ->
        if ((index and 511) == 0) cancellationCheckpoint()
        val path = item.absolutePath
        // An ineligible video, removable volume, or mismatched-collection
        // URI cannot claim a reviewed V4 image path and must not poison its
        // ambiguity proof. Use EXACTLY the same admission as final matching
        // and Lane I's reviewed People album. Two valid distinct image URIs
        // sharing the same primary path remain ambiguous and fail closed.
        if (path in relevantPaths && mayAttributeReviewedV4Person(
                item.uri, item.isVideo, mediaVolumeOf(item), path
            )) {
            val earlier = firstUriByPath.putIfAbsent(path, item.uri)
            if (earlier != null && earlier != item.uri) ambiguousPaths.add(path)
        }
    }
    // Pre-index once: constructing an AlbumLocation for every one of 150k
    // MediaRecord rows adds avoidable allocations during fast filtering.
    val selectedVolumesByAlbum = filters.albumLocations
        .groupBy { it.relativePath }
        .mapValues { (_, locations) -> locations.map { it.volumeName }.toHashSet() }
    val output = ArrayList<MediaRecord>()
    media.forEachIndexed { index, item ->
        if ((index and 511) == 0) cancellationCheckpoint()
        if (!matchesSearchDateFacet(item.takenAtMillis, filters.dateWindow)) return@forEachIndexed
        if (pickedPersonUris.any { item.uri !in it }) return@forEachIndexed
        if (filters.kind == SearchMediaKind.PHOTOS && item.isVideo) return@forEachIndexed
        if (filters.kind == SearchMediaKind.VIDEOS && !item.isVideo) return@forEachIndexed
        if (filters.albumPaths.isNotEmpty() || filters.albumLocations.isNotEmpty()) {
            val legacyAllVolumes = item.relativePath in filters.albumPaths
            val acceptedVolumes = selectedVolumesByAlbum[item.relativePath]
            val providerVolume = if (acceptedVolumes != null) mediaVolumeOf(item) else null
            val exactVolume = providerVolume != null &&
                acceptedVolumes?.contains(providerVolume) == true
            if (!legacyAllVolumes && !exactVolume) return@forEachIndexed
        }
        val path = item.absolutePath
        // Validate provenance only for actual candidate reviewed paths.
        // Standalone filename/album search remains available across all
        // authorized volumes, including videos; it makes no Person claims.
        val reviewedPrimaryImage =
            path in relevantPaths && mayAttributeReviewedV4Person(
                item.uri, item.isVideo, mediaVolumeOf(item), path
            )
        if (selectedPaths.isNotEmpty() && !reviewedPrimaryImage) return@forEachIndexed
        if (selectedPaths.any { path !in it } ||
            (selectedPaths.isNotEmpty() && path in ambiguousPaths)) return@forEachIndexed
        if (filters.onlyTheseIndexedFaces) {
            val known = verifiedFaceCountsByUri[item.uri]?.knownTotalFor(
                item.uri, currentPermissionEpoch, currentMediaRevisionsByUri[item.uri]
            )
            if (known != filters.confirmedPersonIds.size) return@forEachIndexed
        }
        if (words.isNotEmpty()) {
            val name = plainSearchText(item.name)
            val album = plainSearchText(item.album)
            if (words.indices.any { wordIndex ->
                    val word = words[wordIndex]
                    !name.contains(word) && !album.contains(word) &&
                        dateTokens[wordIndex].let { token ->
                            token == null || dateMatcher?.matches(token, item.takenAtMillis) != true
                        } &&
                        (path in ambiguousPaths || !reviewedPrimaryImage ||
                            ((personNameMaskByPath[path] ?: 0) and
                                (1 shl wordIndex)) == 0)
                }) return@forEachIndexed
        }
        output.add(item)
    }
    cancellationCheckpoint()
    return output
}

fun eligibleForIndexedFacesFilter(
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    selectedPersonIds: Set<Long>,
    verifiedFaceCountsByUri: Map<String, IndexedFaceCount>,
    currentPermissionEpoch: String? = null,
    currentMediaRevisionsByUri: Map<String, String> = emptyMap(),
    /** Same authoritative volume callback as searchWithFacets. */
    mediaVolumeOf: (MediaRecord) -> String? = { null },
    cancellationCheckpoint: () -> Unit = {}
): Boolean {
    if (selectedPersonIds.isEmpty()) return false
    val matched = searchWithFacets(
        media, people, LocalSearchFacets(confirmedPersonIds = selectedPersonIds),
        mediaVolumeOf = mediaVolumeOf,
        cancellationCheckpoint = cancellationCheckpoint
    )
    return matched.any { item ->
        verifiedFaceCountsByUri[item.uri]?.knownTotalFor(
            item.uri, currentPermissionEpoch, currentMediaRevisionsByUri[item.uri]
        ) == selectedPersonIds.size
    }
}

/**
 * Ephemeral recents: no SharedPreferences, analytics, cloud backup, logging,
 * or automatic retention of potentially sensitive People queries.
 */
data class EphemeralSearchHistory(
    val terms: List<String> = emptyList(),
    /** Null means unscoped legacy state: never render it as a trusted grant history. */
    val permissionEpoch: String? = null
) {
    /** Old names must not remain visible after photo access changes or is revoked. */
    fun forGrant(currentPermissionEpoch: String?): EphemeralSearchHistory {
        if (currentPermissionEpoch.isNullOrBlank()) return EphemeralSearchHistory()
        return if (permissionEpoch == currentPermissionEpoch) this
            else EphemeralSearchHistory(permissionEpoch = currentPermissionEpoch)
    }

    /** Worker/UI owner must call forGrant BEFORE remembering a new query. */
    fun rememberForGrant(
        text: String,
        currentPermissionEpoch: String?,
        capacity: Int = 10
    ): EphemeralSearchHistory =
        forGrant(currentPermissionEpoch).let { current ->
            if (current.permissionEpoch == null) current
            else current.remember(text, capacity)
        }

    /** Samsung keeps ten recent searches (PPL:20). */
    fun remember(text: String, capacity: Int = 10): EphemeralSearchHistory {
        require(capacity in 1..20)
        val clean = text.trim()
        if (clean.isEmpty() ||
            admittedSearchQueryWordsOrNull(text) == null) return this
        // Do not truncate a submitted query: the lost tail can be a
        // restrictive keyword, and replaying this recent would broaden
        // results compared with the original search.
        return EphemeralSearchHistory(
            (listOf(clean) + terms.filterNot { it.equals(clean, ignoreCase = true) })
                .take(capacity),
            permissionEpoch = permissionEpoch
        )
    }
    fun clear(): EphemeralSearchHistory =
        EphemeralSearchHistory(permissionEpoch = permissionEpoch)
}
