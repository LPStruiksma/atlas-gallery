package nl.atlas.gallery.featurepeopleui

import java.util.Locale

/** Sort preference contains no names or media identifiers; a stable seed is used for random. */
enum class PeopleSortKey { MEDIA_COUNT, NAME, MOST_RECENT, FIRST_APPEARANCE, RANDOM }
data class PeopleSortChoice(val key: PeopleSortKey = PeopleSortKey.MEDIA_COUNT,
                            val descending: Boolean = true)
data class PeopleSortStats(val mediaCount: Int, val firstMs: Long?, val recentMs: Long?) {
    init { require(mediaCount >= 0) }
}
data class PeopleSortEntry(val id: Int, val name: String, val stats: PeopleSortStats)

/**
 * No media reads here. Indexed database / reviewed V4 projection supply the statistics.
 * A blank name always trails named people, in either direction. Undated entries
 * likewise trail dated entries. Person IDs are never persisted or logged.
 */
fun orderPeopleEntries(
    entries: List<PeopleSortEntry>, choice: PeopleSortChoice, seed: Int = 0x5174
): List<PeopleSortEntry> {
    fun keyOf(entry: PeopleSortEntry): Long? = when (choice.key) {
        PeopleSortKey.MEDIA_COUNT -> entry.stats.mediaCount.toLong()
        PeopleSortKey.MOST_RECENT -> entry.stats.recentMs
        PeopleSortKey.FIRST_APPEARANCE -> entry.stats.firstMs
        else -> null
    }
    val randomOrder = if (choice.key == PeopleSortKey.RANDOM) {
        entries.indices.shuffled(kotlin.random.Random(seed)).withIndex()
            .associate { entries[it.value].id to it.index }
    } else emptyMap()
    return entries.sortedWith(Comparator { a, b ->
        val named = a.name.isNotBlank().compareTo(b.name.isNotBlank())
        if (named != 0) return@Comparator -named
        if (choice.key == PeopleSortKey.RANDOM) {
            val order = (randomOrder[a.id] ?: 0).compareTo(randomOrder[b.id] ?: 0)
            return@Comparator if (choice.descending) -order else order
        }
        if (choice.key == PeopleSortKey.NAME) {
            val order = a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
            if (order != 0) return@Comparator if (choice.descending) -order else order
        } else {
            val left = keyOf(a)
            val right = keyOf(b)
            if (left == null && right != null) return@Comparator 1
            if (right == null && left != null) return@Comparator -1
            val order = if (left != null && right != null) left.compareTo(right) else 0
            if (order != 0) return@Comparator if (choice.descending) -order else order
        }
        val nameOrder = a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
        if (nameOrder != 0) nameOrder else a.id.compareTo(b.id)
    })
}
