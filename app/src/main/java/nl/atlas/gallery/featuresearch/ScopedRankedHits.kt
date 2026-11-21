package nl.atlas.gallery.featuresearch

/** Preserve the existing engine's ranked order unless the user requests the overview's sort. */
enum class ScopedRankOrder { BEST_MATCHES, SCREEN_SORT }

/**
 * FIND-2: intersect an already ranked face/visual hit stream with the currently
 * authorized overview. The source URI list must be supplied by the caller's
 * current permission/catalogue snapshot, never by a picker or a global search.
 *
 * Filtering does not recompute scores, confidence tiers or video moments.
 * A reference photo from outside [screenUris] never expands the result set.
 * Work on Dispatchers.Default when either list is large.
 */
fun <T> scopeRankedHits(
    ranked: List<T>,
    screenUris: List<String>,
    uriOf: (T) -> String,
    order: ScopedRankOrder = ScopedRankOrder.BEST_MATCHES
): List<T> {
    if (ranked.isEmpty() || screenUris.isEmpty()) return emptyList()
    val positions = HashMap<String, Int>(screenUris.size)
    screenUris.forEachIndexed { index, uri -> positions.putIfAbsent(uri, index) }
    val visible = ranked.filter { uriOf(it) in positions }
    return if (order == ScopedRankOrder.SCREEN_SORT)
        visible.sortedWith(compareBy { positions.getValue(uriOf(it)) })
    else visible
}
