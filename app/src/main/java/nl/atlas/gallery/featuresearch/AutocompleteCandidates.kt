package nl.atlas.gallery.featuresearch

import java.util.Collections
import java.util.Locale

/** Navigation identities, not inferred matches or concatenated storage keys. */
sealed interface AutocompleteIdentity {
    data class History(
        val scope: SearchHistoryScope,
        val textKey: String,
        val personKeys: List<String>
    ) : AutocompleteIdentity
    data class Person(val key: String) : AutocompleteIdentity
    data class Album(val volume: String, val relativePath: String) : AutocompleteIdentity
    data class Date(val year: Int, val month: Int?) : AutocompleteIdentity
    data class FilePrefix(val prefix: String) : AutocompleteIdentity
}

data class AutocompleteCandidate(
    val identity: AutocompleteIdentity,
    val display: String,
    /** Original admitted query and chip order, for replay rather than parsing the label. */
    val historyEntry: SearchHistoryEntry? = null
)

/** Caller supplies only currently visible, confirmed people and their existing rank. */
data class AutocompletePerson(val person: SearchPerson, val rank: Int)
data class AutocompleteAlbum(val volume: String, val relativePath: String, val name: String)

/** A caller-computed bucket of media in this exact authorized scope, not the library. */
data class AutocompleteDate(val year: Int, val month: Int?, val mediaCount: Int)

private val autocompleteWhitespace = Regex("\\s+")
private val autocompleteBoundary = Regex("[\\-_.,'’]+")
private val autocompleteMonths = listOf("January", "February", "March", "April", "May",
    "June", "July", "August", "September", "October", "November", "December")

private data class AutocompleteRanked(
    val row: AutocompleteCandidate,
    val rank: Int = 0
) {
    val key = plainSearchText(row.display)
}

/**
 * Immutable prefix index for ONE caller-authorized screen/library snapshot.
 * Build off the main thread; discard/rebuild on any scope, grant, People,
 * metadata or history change. The caller must not pass a wider catalog or
 * keep using an old snapshot. This object cannot discover or authorize media.
 *
 * History is taken only from [historyScope], newest first, if enabled, and
 * its person keys must resolve in the current confirmed roster. Albums and
 * file prefixes are already scoped summaries; date buckets must represent
 * actual media in that scope. No IO, persistence, logging or inference.
 *
 * All admitted query words must match a word start (including internal word
 * starts after the same separators as People). Rank is supplied for People;
 * history is recency-only. Base quotas 2/2/2/1/1 transfer unused slots only
 * forward, including duplicates removed, subject always to five total.
 * Earlier groups win normalized display duplicates. Within a group, sort
 * before deduplicating so the best-ranked stable identity wins.
 */
class AutocompleteCandidateIndex(
    historyScope: SearchHistoryScope,
    history: SearchHistoryState = SearchHistoryState(),
    people: List<AutocompletePerson> = emptyList(),
    albums: List<AutocompleteAlbum> = emptyList(),
    dates: List<AutocompleteDate> = emptyList(),
    filePrefixes: List<String> = emptyList()
) {
    private val groups: List<PrefixGroup>

    init {
        val peopleRanked = people.sortedWith(compareBy<AutocompletePerson> { it.rank }
            .thenBy { plainSearchText(it.person.name) }.thenBy { it.person.key }
            .thenBy { it.person.name }).distinctBy { it.person.key }
            .filter { plainSearchText(it.person.name).isNotBlank() }
        val visible = peopleRanked.associate { it.person.key to it.person.name }
        val historyRows = if (!history.enabled) emptyList() else
            history.entries(historyScope).mapIndexedNotNull { rank, entry ->
                if (!entry.admissible || entry.personKeys.any { it !in visible }) null
                else {
                    val keys = Collections.unmodifiableList(entry.personKeys.toList())
                    val copy = SearchHistoryEntry(entry.text, keys)
                    val display = (keys.map { visible.getValue(it) } + entry.text.trim())
                        .filter { it.isNotBlank() }.joinToString(" ")
                    AutocompleteRanked(AutocompleteCandidate(
                        AutocompleteIdentity.History(historyScope,
                            entry.text.trim().lowercase(Locale.ROOT),
                            Collections.unmodifiableList(keys.sorted())),
                        display, copy), rank)
                }
            }
        val personRows = peopleRanked.map {
            AutocompleteRanked(AutocompleteCandidate(
                AutocompleteIdentity.Person(it.person.key), it.person.name), it.rank)
        }
        val albumRows = albums.filter { it.name.isNotBlank() }.map {
            AutocompleteRanked(AutocompleteCandidate(
                AutocompleteIdentity.Album(it.volume, it.relativePath), it.name))
        }
        val dateIds = HashSet<AutocompleteIdentity.Date>()
        dates.filter { it.mediaCount > 0 && it.year in 1900..2099 &&
            (it.month == null || it.month in 1..12) }.forEach {
            dateIds += AutocompleteIdentity.Date(it.year, null)
            if (it.month != null) dateIds += AutocompleteIdentity.Date(it.year, it.month)
        }
        val dateRows = dateIds.map { id ->
            val display = if (id.month == null) id.year.toString()
                else autocompleteMonths[id.month - 1] + " " + id.year
            AutocompleteRanked(AutocompleteCandidate(id, display),
                -(id.year * 12 + (id.month ?: 0)))
        }
        val fileRows = filePrefixes.filter { it.isNotBlank() }.map {
            AutocompleteRanked(AutocompleteCandidate(AutocompleteIdentity.FilePrefix(it), it))
        }
        val byRank = compareBy<AutocompleteRanked> { it.rank }.thenBy { it.key }
        val albumOrder = compareBy<AutocompleteRanked> { it.key.length }.thenBy { it.key }
            .thenBy { (it.row.identity as AutocompleteIdentity.Album).volume }
            .thenBy { (it.row.identity as AutocompleteIdentity.Album).relativePath }
            .thenBy { it.row.display }
        groups = listOf(
            PrefixGroup(historyRows.sortedWith(byRank)),
            PrefixGroup(personRows.sortedWith(byRank
                .thenBy { (it.row.identity as AutocompleteIdentity.Person).key })),
            PrefixGroup(albumRows.sortedWith(albumOrder)),
            PrefixGroup(dateRows.sortedWith(byRank)),
            PrefixGroup(fileRows.sortedWith(compareBy<AutocompleteRanked> { it.key }
                .thenBy { (it.row.identity as AutocompleteIdentity.FilePrefix).prefix }))
        )
    }

    fun search(typed: String): List<AutocompleteCandidate> {
        val words = admittedSearchQueryWordsOrNull(typed) ?: return emptyList()
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<AutocompleteCandidate>(5)
        val seen = HashSet<String>()
        val quotas = intArrayOf(2, 2, 2, 1, 1)
        var carry = 0
        for ((i, group) in groups.withIndex()) {
            val allowance = minOf(5 - out.size, quotas[i] + carry)
            if (allowance == 0) break
            val before = out.size
            // Count typed normalized non-whitespace characters, not padding.
            if (i != 4 || words.sumOf { it.codePointCount(0, it.length) } >= 3) {
                group.append(words, allowance, seen, out)
            }
            carry = quotas[i] + carry - (out.size - before)
        }
        return out
    }

    /**
     * Sorted rank postings for every word prefix, built once. A lookup
     * intersects the smallest matching posting with the others by binary
     * search and stops after its quota. No per-query catalog sort/scan,
     * including single-character common prefixes. Multi-word work depends
     * on posting sizes, not a walk through the source catalog.
     */
    private class PrefixGroup(ranked: List<AutocompleteRanked>) {
        private val entries = ranked.filter { it.key.isNotBlank() }
            .distinctBy { it.row.identity }.distinctBy { it.key }
        private val postings: Map<String, IntArray>

        init {
            val builders = HashMap<String, PostingBuilder>()
            entries.forEachIndexed { index, candidate ->
                val prefixes = HashSet<String>()
                candidate.key.split(autocompleteWhitespace).filter { it.isNotEmpty() }.forEach { word ->
                    // Preserve punctuation in a typed token (IMG_2019, O'Neil),
                    // and also index starts after punctuation, as People does.
                    val starts = listOf(0) + autocompleteBoundary.findAll(word)
                        .map { it.range.last + 1 }.toList()
                    for (start in starts) {
                        for (end in start + 1..word.length) prefixes += word.substring(start, end)
                    }
                }
                prefixes.forEach { prefix ->
                    builders.getOrPut(prefix) { PostingBuilder() }.add(index)
                }
            }
            postings = builders.mapValues { (_, builder) -> builder.finish() }
        }

        fun append(words: List<String>, limit: Int, seen: MutableSet<String>,
                   out: MutableList<AutocompleteCandidate>) {
            val lists = words.distinct().map { postings[it] ?: return }.sortedBy { it.size }
            val smallest = lists.first()
            val others = lists.drop(1)
            var remaining = limit
            for (id in smallest) {
                if (others.any { it.binarySearch(id) < 0 }) continue
                val entry = entries[id]
                if (!seen.add(entry.key)) continue
                out += entry.row
                if (--remaining == 0) return
            }
        }
    }

    /** Primitive postings avoid boxing every rank in a large snapshot. */
    private class PostingBuilder {
        private var values = IntArray(1)
        private var size = 0
        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }
        fun finish(): IntArray = values.copyOf(size)
    }
}
