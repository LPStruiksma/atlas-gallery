package nl.atlas.gallery.featuresearch

import java.text.Normalizer
import java.util.Locale

/** Suggestions are navigation/filter choices, NEVER face identity predictions. */
enum class SearchSuggestionKind { CONFIRMED_PERSON, ALBUM }

/** Only caller-supplied reviewed people and ALREADY-INDEXED album metadata. */
data class SearchSuggestionSource(
    val kind: SearchSuggestionKind,
    val title: String,
    val personId: Long? = null,
    val albumRelativePath: String? = null,
    val albumVolume: String? = null
) {
    init {
        require(title.isNotBlank())
        when (kind) {
            SearchSuggestionKind.CONFIRMED_PERSON ->
                require(personId != null && albumRelativePath == null)
            SearchSuggestionKind.ALBUM ->
                require(personId == null && albumRelativePath != null)
        }
    }
}

/** Exact path/volume or stable person ID, not a fuzzy lookup or inferred identity. */
data class SearchSuggestion(
    val source: SearchSuggestionSource,
    val subtitle: String
)

private val searchCombiningMarks = Regex("\\p{M}+")
private val searchQueryWhitespace = Regex("\\s+")

/**
 * Shared lexical normalization for Search results AND its suggestions.
 * Short ASCII names (the bulk of a 150k gallery) skip Unicode normalization
 * and regex allocation; accent-bearing or decomposed text uses NFKD.
 * This does not infer identities or combine reviewed People IDs.
 */
internal fun plainSearchText(value: String): String {
    val trimmed = value.trim()
    if (trimmed.all { it.code < 128 }) return trimmed.lowercase(Locale.ROOT)
    return Normalizer.normalize(trimmed, Normalizer.Form.NFKD)
        .replace(searchCombiningMarks, "").lowercase(Locale.ROOT)
}

/**
 * A single lexical admission shared by visible Search results, suggestions,
 * and session-only recent searches. Never drop restrictive terms to turn an
 * invalid search into a broader query; keep blank text valid for All photos.
 */
internal fun admittedSearchQueryWordsOrNull(typed: String): List<String>? {
    if (typed.length > MAX_SEARCH_QUERY_CHARS) return null
    val rawWords = typed.trim().split(searchQueryWhitespace)
        .filter { it.isNotBlank() }
    if (rawWords.size > MAX_SEARCH_QUERY_TERMS) return null
    return rawWords.map(::plainSearchText).takeIf { words ->
        words.none { it.isBlank() }
    }
}

/**
 * Input is an independently precomputed small catalog, never MediaRecord[150k].
 * Duplicate names remain separate reviewed person IDs, duplicate album names
 * remain separate per-volume paths. Empty query doesn't surface private names.
 */
fun planLocalSearchSuggestions(
    sources: List<SearchSuggestionSource>,
    typed: String,
    limit: Int = 10
): List<SearchSuggestion> {
    require(limit in 1..40)
    val words = admittedSearchQueryWordsOrNull(typed) ?: return emptyList()
    if (words.isEmpty()) return emptyList()
    val query = plainSearchText(typed)

    data class Candidate(val item: SearchSuggestionSource, val normalized: String)
    // Typed tuple keys prevent e.g. album volume "a:b" + path "c" from
    // colliding with volume "a" + path "b:c".
    data class Identity(
        val kind: SearchSuggestionKind,
        val personId: Long?,
        val volume: String?,
        val relativePath: String?
    )
    val distinct = LinkedHashMap<Identity, Candidate>()
    sources.forEach { item ->
        val identity = Identity(
            item.kind, item.personId, item.albumVolume, item.albumRelativePath
        )
        // Keep first source for repeated exact IDs; never collapse homonyms.
        distinct.putIfAbsent(identity, Candidate(item, plainSearchText(item.title)))
    }
    return distinct.values.asSequence()
        .filter { candidate ->
            words.all { token -> candidate.normalized.contains(token) }
        }
        .sortedWith(compareBy<Candidate> { !it.normalized.startsWith(query) }
            .thenBy { it.item.kind != SearchSuggestionKind.CONFIRMED_PERSON }
            .thenBy { it.normalized }
            .thenBy { it.item.personId ?: Long.MAX_VALUE }
            .thenBy { it.item.albumVolume.orEmpty() }
            .thenBy { it.item.albumRelativePath.orEmpty() })
        .take(limit)
        .map { candidate ->
            val item = candidate.item
            SearchSuggestion(item, if (item.kind == SearchSuggestionKind.CONFIRMED_PERSON)
                "Reviewed person" else
                item.albumRelativePath!!.trimEnd('/').ifEmpty { "Root album" } +
                    (item.albumVolume?.let { " · $it" } ?: ""))
        }.toList()
}
