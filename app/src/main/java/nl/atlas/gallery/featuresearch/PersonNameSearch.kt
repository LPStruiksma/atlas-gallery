package nl.atlas.gallery.featuresearch

import java.util.Calendar
import java.util.TimeZone

/**
 * #554: Samsung-style "type a name, pick the person" Search.
 *
 * A [SearchPerson] is a person the user named themselves (a native People
 * name or a reviewed V4 person). [key] is opaque here; the caller owns its
 * meaning. Nothing in this file infers an identity from a face or a score.
 */
data class SearchPerson(val key: String, val name: String) {
    init { require(key.isNotBlank() && name.isNotBlank()) }
}

/**
 * One suggested person for the typed text. [remainder] is the typed text
 * without the words that matched the name, in the user's own spelling, so
 * picking "Susanne" from "sus 2019" leaves "2019" in the field.
 */
data class PersonNameMatch(
    val person: SearchPerson,
    val remainder: String,
    /** Every word of the name was typed in full: "susanne" for "Susanne". */
    val exact: Boolean
)

private val nameWordSplit = Regex("[\\s\\-_.,'’]+")

private fun nameWords(name: String): List<String> =
    plainSearchText(name).split(nameWordSplit).filter { it.isNotEmpty() }

/**
 * People whose name matches the typed words by word prefix, accent- and
 * case-insensitive ("sus" and "süs" find "Süsanne de Vries"; so does "vri").
 * Each typed word may claim one name word; words that claim nothing (a year,
 * an album, "beach") become the [PersonNameMatch.remainder]. A date word
 * ("2019", "june") claims only an identical name word, so "June 2019" can
 * suggest a person called June while "2019" never prefixes a name. Best first: more matched words, a name that starts with the typed
 * text, then name and key for a stable order. Duplicate names stay separate.
 */
fun matchPeopleByName(
    people: List<SearchPerson>,
    typed: String,
    limit: Int = 5
): List<PersonNameMatch> {
    require(limit in 1..40)
    return rankPeopleByName(people, typed).take(limit)
}

/** Every person matching [typed], best first; no suggestion cap. */
private fun rankPeopleByName(people: List<SearchPerson>, typed: String): List<PersonNameMatch> {
    val words = admittedSearchQueryWordsOrNull(typed) ?: return emptyList()
    if (words.isEmpty()) return emptyList()
    val rawWords = typed.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val nameable = words.map { searchDateToken(it) == null }
    val query = plainSearchText(typed)

    data class Scored(val match: PersonNameMatch, val claimed: Int, val startsWith: Boolean,
                      val normalized: String)
    val seen = HashSet<String>()
    val scored = ArrayList<Scored>()
    for (person in people) {
        if (!seen.add(person.key)) continue
        val name = nameWords(person.name)
        if (name.isEmpty()) continue
        val used = BooleanArray(name.size)
        val claimedWord = BooleanArray(words.size)
        var full = 0
        words.forEachIndexed { i, word ->
            // Prefer an exact word, then the first unused prefix.
            var hit = name.indices.firstOrNull { !used[it] && name[it] == word }
            if (hit == null && nameable[i]) hit = name.indices.firstOrNull { !used[it] && name[it].startsWith(word) }
            if (hit != null) {
                used[hit] = true
                claimedWord[i] = true
                if (name[hit] == word) full++
            }
        }
        val claimed = claimedWord.count { it }
        if (claimed == 0) continue
        val remainder = rawWords.filterIndexed { i, _ -> !claimedWord[i] }.joinToString(" ")
        val normalized = plainSearchText(person.name)
        scored += Scored(
            PersonNameMatch(person, remainder, exact = full == name.size && claimed == name.size),
            claimed, normalized.startsWith(query), normalized)
    }
    return scored.sortedWith(compareByDescending<Scored> { it.claimed }
        .thenBy { !it.startsWith }
        .thenBy { it.normalized }
        .thenBy { it.match.person.key })
        .map { it.match }
}

/**
 * The one person a submitted or recalled query names in full, or null when
 * none or more than one does. Used to turn "Susanne 2019" (typed and sent, or
 * a recent search) back into the Susanne chip plus "2019".
 */
fun exactPersonInQuery(people: List<SearchPerson>, typed: String): PersonNameMatch? {
    // The whole roster, not the 40-row suggestion cap: many better-ranked
    // prefix matches must not hide the one person named in full.
    val exact = rankPeopleByName(people, typed).filter { it.exact }
    if (exact.isEmpty()) return null
    val best = exact.maxOf { nameWords(it.person.name).size }
    return exact.filter { nameWords(it.person.name).size == best }.singleOrNull()
}

/** A year (1900–2099) or a month, typed as a Search word. */
sealed interface SearchDateToken {
    data class Year(val year: Int) : SearchDateToken
    /** [month] is 1-based. */
    data class Month(val month: Int) : SearchDateToken
}

// Full month names only (English and Dutch). Short forms such as "jan" or
// "mar" are common names and words, so they stay ordinary text.
private val monthNames: Map<String, Int> = buildMap {
    listOf("january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december")
        .forEachIndexed { i, m -> put(m, i + 1) }
    listOf("januari", "februari", "maart", "april", "mei", "juni", "juli",
        "augustus", "september", "oktober", "november", "december")
        .forEachIndexed { i, m -> put(m, i + 1) }
}

/** [word] is already folded by [plainSearchText]. */
fun searchDateToken(word: String): SearchDateToken? {
    monthNames[word]?.let { return SearchDateToken.Month(it) }
    if (word.length == 4 && word.all { it in '0'..'9' }) {
        val year = word.toInt()
        if (year in 1900..2099) return SearchDateToken.Year(year)
    }
    return null
}

/**
 * Reused per search: one Calendar for all rows. Not thread-safe; create one
 * per worker call. Unknown dates (<= 0) never match a date word.
 */
internal class SearchDateMatcher(timeZone: TimeZone = TimeZone.getDefault()) {
    private val calendar = Calendar.getInstance(timeZone)
    private var lastMillis = Long.MIN_VALUE
    private var year = 0
    private var month = 0

    fun matches(token: SearchDateToken, takenAtMillis: Long): Boolean {
        if (takenAtMillis <= 0L) return false
        if (takenAtMillis != lastMillis) {
            calendar.timeInMillis = takenAtMillis
            year = calendar.get(Calendar.YEAR)
            month = calendar.get(Calendar.MONTH) + 1
            lastMillis = takenAtMillis
        }
        return when (token) {
            is SearchDateToken.Year -> year == token.year
            is SearchDateToken.Month -> month == token.month
        }
    }
}
