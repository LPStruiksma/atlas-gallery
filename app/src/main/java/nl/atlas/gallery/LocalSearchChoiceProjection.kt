package nl.atlas.gallery

import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featurealbums.albumDisplayName
import nl.atlas.gallery.featuresearch.SearchSuggestionKind
import nl.atlas.gallery.featuresearch.SearchSuggestionSource
import nl.atlas.gallery.featuresearch.planLocalSearchSuggestions

/**
 * Small Search UI input, computed exclusively from already authorized Album
 * summaries and human-reviewed People, never from inferred face identities.
 * Returns at most 10 typed sources; preserves album (volume, path) and Person ID.
 *
 * Caller owns a permission-epoch scoped worker and must check cancellation.
 */
internal fun buildAuthorizedSearchChoiceSources(
    albums: List<AlbumSummary>, people: List<PersonEntry>, text: String,
    cancellationCheckpoint: () -> Unit = {}
): List<SearchSuggestionSource> {
    if (text.isBlank()) return emptyList()
    val candidates = ArrayList<SearchSuggestionSource>(people.size + albums.size)
    people.forEachIndexed { index, person ->
        if ((index and 63) == 0) cancellationCheckpoint()
        if (person.name.isNotBlank()) {
            candidates.add(SearchSuggestionSource(
                kind = SearchSuggestionKind.CONFIRMED_PERSON,
                title = person.name, personId = person.id))
        }
    }
    albums.forEachIndexed { index, album ->
        if ((index and 511) == 0) cancellationCheckpoint()
        if (album.volumeName.isNotBlank()) {
            candidates.add(SearchSuggestionSource(
                kind = SearchSuggestionKind.ALBUM,
                title = albumDisplayName(album.relativePath),
                albumRelativePath = album.relativePath,
                albumVolume = album.volumeName))
        }
    }
    cancellationCheckpoint()
    return planLocalSearchSuggestions(candidates, text, limit = 10)
        .map { it.source }.also { cancellationCheckpoint() }
}
