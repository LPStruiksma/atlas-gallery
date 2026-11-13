package nl.atlas.gallery.featurealbums

import java.text.Normalizer
import java.util.Locale

/**
 * An ephemeral, source-bound search result for the Albums destination.
 * Only visible title/location captions are indexed; hidden parent paths,
 * filenames, person metadata and MediaStore contents are not searched.
 */
class AlbumQuickFindResult(
    val source: List<AlbumSummary>,
    val query: String,
    val matches: List<AlbumSummary>
) {
    fun isCurrent(source: List<AlbumSummary>, query: String): Boolean =
        this.source === source && this.query == query
}

/**
 * Find authorized album cards without changing their order or route identity.
 * This is intended for Dispatchers.Default: it must not scan 150k album titles
 * on the Compose main thread. Cancellation is checked during large scans.
 */
fun findAlbumsByVisibleText(
    albums: List<AlbumSummary>,
    query: String,
    checkActive: (() -> Unit)? = null
): AlbumQuickFindResult {
    val tokens = searchFold(query).split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return AlbumQuickFindResult(albums, query, albums)
    val matches = ArrayList<AlbumSummary>()
    albums.forEachIndexed { index, album ->
        if ((index and 255) == 0) checkActive?.invoke()
        val visibleCaption = buildString {
            append(album.title)
            album.locationLabel?.let { append(' '); append(it) }
        }
        val haystack = searchFold(visibleCaption)
        if (tokens.all { it in haystack }) matches.add(album)
    }
    checkActive?.invoke()
    return AlbumQuickFindResult(albums, query, matches)
}

/** Accent-insensitive and locale-independent, including decomposed accents. */
private fun searchFold(text: String): String = buildString {
    for (char in Normalizer.normalize(text, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT)) {
        when (Character.getType(char)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> Unit
            else -> append(char)
        }
    }
}
