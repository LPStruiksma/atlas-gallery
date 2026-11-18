package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry

/** Scope is a source-list boundary, never an untrusted string filter. */
enum class AtlasSearchScope { THIS_SCREEN, LIBRARY }

/**
 * Filters only a caller-authorized, already scoped ordered list. The engine
 * preserves incoming sort; it cannot expand an album/person/results view by
 * consulting a larger catalog. A library override must explicitly pass the
 * current grant-authorized library list as [libraryMedia].
 */
fun scopedMediaMatches(
    screenMedia: List<MediaRecord>,
    libraryMedia: List<MediaRecord>,
    people: List<PersonEntry>,
    query: String,
    scope: AtlasSearchScope = AtlasSearchScope.THIS_SCREEN,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    val source = if (scope == AtlasSearchScope.LIBRARY) libraryMedia else screenMedia
    if (query.isBlank()) return source
    return searchWithFacets(
        media = source,
        people = people,
        filters = LocalSearchFacets(text = query),
        mediaVolumeOf = { it.volumeName },
        cancellationCheckpoint = cancellationCheckpoint
    )
}

/** Accent/case-insensitive substring matching also accepts name prefixes. */
fun scopedNameMatches(name: String, query: String): Boolean {
    val words = admittedSearchQueryWordsOrNull(query) ?: return false
    if (words.isEmpty()) return true
    val normalized = plainSearchText(name)
    return words.all(normalized::contains)
}

/** Filter a previously sorted list without reordering or merging homonyms. */
fun <T> scopedNamedMatches(
    source: List<T>,
    query: String,
    nameOf: (T) -> String
): List<T> {
    if (query.isBlank()) return source
    if (admittedSearchQueryWordsOrNull(query) == null) return emptyList()
    return source.filter { nameOf(it).isNotBlank() && scopedNameMatches(nameOf(it), query) }
}
