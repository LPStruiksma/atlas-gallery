package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry

/**
 * Immutable worker result. Never publish a result computed under a different
 * MediaStore access grant, catalog/People generation, query or face-index epoch.
 *
 * MediaStore/People source lists use reference identity, not List.equals:
 * comparing two 150k-element inventories on a Compose frame is unacceptable.
 */
class FacetedSearchSnapshot(
    val source: List<MediaRecord>,
    val people: List<PersonEntry>,
    val filters: LocalSearchFacets,
    val permissionEpoch: String,
    val faceIndexGeneration: String?,
    val results: List<MediaRecord>,
    /** #554: the exact per-person URI map the results were computed with. */
    val nativePersonUris: Map<String, Set<String>> = emptyMap()
) {
    init { require(permissionEpoch.isNotBlank()) }
}

fun currentFacetedSearchOrNull(
    result: FacetedSearchSnapshot?,
    source: List<MediaRecord>,
    people: List<PersonEntry>,
    filters: LocalSearchFacets,
    permissionEpoch: String?,
    faceIndexGeneration: String?
): List<MediaRecord>? = result?.takeIf {
    !permissionEpoch.isNullOrBlank() &&
        it.permissionEpoch == permissionEpoch &&
        it.source === source && it.people === people &&
        it.filters == filters && it.faceIndexGeneration == faceIndexGeneration
}?.results

/**
 * Worker wrapper: caller must supply the same freshly authorized source that
 * MainActivity uses to render the grid. The callback checks coroutine
 * cancellation; no read from V4 DB, private media or ContentResolver here.
 */
fun searchAuthorizedSnapshot(
    source: List<MediaRecord>,
    people: List<PersonEntry>,
    filters: LocalSearchFacets,
    permissionEpoch: String,
    faceIndexGeneration: String? = null,
    /** Exact volume binding supplied after Lane M is mounted. */
    mediaVolumeOf: (MediaRecord) -> String? = { null },
    cancellationCheckpoint: () -> Unit = {},
    nativePersonUris: Map<String, Set<String>> = emptyMap()
): FacetedSearchSnapshot {
    require(permissionEpoch.isNotBlank())
    val matches = searchWithFacets(
        media = source, people = people, filters = filters,
        // Face-count results require a separately bound current URI/revision
        // map. This plain search intentionally refuses that optional facet.
        currentPermissionEpoch = permissionEpoch,
        mediaVolumeOf = mediaVolumeOf,
        cancellationCheckpoint = cancellationCheckpoint,
        nativePersonUris = nativePersonUris
    )
    return FacetedSearchSnapshot(
        source, people, filters, permissionEpoch, faceIndexGeneration, matches,
        nativePersonUris
    )
}
