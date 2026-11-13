package nl.atlas.gallery.featuresearch

import java.util.PriorityQueue
import nl.atlas.gallery.PersonEntry

/**
 * Small in-memory, reviewed-identity picker; never a face-recognition result.
 * Called with the CURRENT authorized People publication, not a media inventory.
 * An ambiguous roster cannot safely generate two LazyRow chips with one ID.
 *
 * Selected people are ALWAYS returned, even when the picker query is invalid;
 * invalid input must not silently broaden unselected suggestions. The rest
 * are a deterministic top-K, without sorting every matching person on the
 * Compose thread. The function never persists entered names or paths.
 */
fun selectReviewedPeopleForPicker(
    people: List<PersonEntry>,
    selectedIds: Set<Long>,
    query: String,
    maxUnselected: Int = 24
): List<PersonEntry> {
    require(maxUnselected in 1..64)
    if (people.size != people.mapTo(HashSet<Long>(people.size)) { it.id }.size) {
        // Corrupt duplicate IDs must not create duplicate Compose item keys
        // or turn an arbitrary conflicting name into a selected identity.
        return emptyList()
    }
    data class Ranked(val item: PersonEntry, val folded: String)
    val ascending = compareBy<Ranked> { it.folded }.thenBy { it.item.id }
    val selected = ArrayList<Ranked>()
    val closest = PriorityQueue<Ranked>(maxUnselected, ascending.reversed())
    val words = admittedSearchQueryWordsOrNull(query)
    val canOfferNew = words != null
    for (item in people) {
        if (item.id in selectedIds) {
            selected.add(Ranked(item, plainSearchText(item.name)))
        } else if (canOfferNew) {
            val folded = plainSearchText(item.name)
            if (words!!.all { folded.contains(it) }) {
                val candidate = Ranked(item, folded)
                if (closest.size < maxUnselected) closest.add(candidate)
                else if (ascending.compare(candidate, closest.peek()) < 0) {
                    closest.remove()
                    closest.add(candidate)
                }
            }
        }
    }
    selected.sortWith(ascending)
    val unselected = closest.toList().sortedWith(ascending)
    return (selected + unselected).map { it.item }
}
