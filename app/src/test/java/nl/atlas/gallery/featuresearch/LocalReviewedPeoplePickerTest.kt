package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.PersonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReviewedPeoplePickerTest {
    private fun person(id: Long, name: String) = PersonEntry(id, name, emptySet())

    @Test fun accentedAndDecomposedReviewedNamesMatchExactlyByTextNotIdentityGuessing() {
        val people = listOf(
            person(9L, "Éloïse"), person(2L, "Eloise"),
            person(5L, "E\u0301loi\u0308se"), person(20L, "Eloiza")
        )
        assertEquals(listOf(2L, 5L, 9L),
            selectReviewedPeopleForPicker(people, emptySet(), "ELOISE")
                .map { it.id })
        assertEquals(listOf(20L), selectReviewedPeopleForPicker(
            people, emptySet(), "eloiza").map { it.id })
    }

    @Test fun selectedReviewedIdsAlwaysRemainVisibleEvenWithoutQueryMatch() {
        val people = listOf(person(5, "Ána"), person(6, "Béa"), person(7, "Zoë"))
        assertEquals(listOf(6L, 5L), selectReviewedPeopleForPicker(
            people, setOf(6L), "ana").map { it.id })
        assertEquals(listOf(6L), selectReviewedPeopleForPicker(
            people, setOf(6L), "no matching person").map { it.id })
    }

    @Test fun overlongOrUnsearchableQueryNeverBroadensUnselectedChips() {
        val people = listOf(person(5L, "Ana"), person(6L, "Banana"))
        val overlong = "ana" + " ".repeat(MAX_SEARCH_QUERY_CHARS - 2)
        assertEquals(MAX_SEARCH_QUERY_CHARS + 1, overlong.length)
        assertEquals(listOf(5L), selectReviewedPeopleForPicker(
            people, setOf(5L), overlong).map { it.id })
        assertTrue(selectReviewedPeopleForPicker(
            people, emptySet(), "ana " + 0x0301.toChar()).isEmpty())
        assertTrue(selectReviewedPeopleForPicker(
            people, emptySet(), List(13) { "ana" }.joinToString(" ")).isEmpty())
        assertEquals(2, selectReviewedPeopleForPicker(
            people, emptySet(), "ana").size)
    }

    @Test fun duplicateStablePersonIdsFailClosedRatherThanCrashLazyRow() {
        val people = listOf(person(4L, "Fictional A"), person(4L, "Fictional B"))
        assertTrue(selectReviewedPeopleForPicker(people, setOf(4L), "").isEmpty())
        assertTrue(selectReviewedPeopleForPicker(people, emptySet(), "a").isEmpty())
        assertEquals(listOf(4L, 5L), selectReviewedPeopleForPicker(
            listOf(person(4L, "A"), person(5L, "B")), emptySet(), "")
            .map { it.id })
    }

    @Test fun boundedTopKIsDeterministicAcrossHugeFictionalPeopleCatalog() {
        val people = List(150_000) { person(it.toLong(), "Fictional Person $it") }
        val chosen = selectReviewedPeopleForPicker(people, emptySet(), "fictional", 24)
        val reversed = selectReviewedPeopleForPicker(people.reversed(), emptySet(), "fictional", 24)
        assertEquals(24, chosen.size)
        assertEquals(chosen.map { it.id }, reversed.map { it.id })
        assertEquals(chosen.map { plainSearchText(it.name) },
            chosen.map { plainSearchText(it.name) }.sorted())
        assertEquals(24, chosen.map { it.id }.toSet().size)
    }

    @Test fun selectedPeopleAreNeverDiscardedByUnselectedTopKCap() {
        val people = List(80) { person(it.toLong(), "Fictional $it") }
        val selected = (0L until 40L).toSet()
        val chosen = selectReviewedPeopleForPicker(people, selected, "", 4)
        assertEquals(44, chosen.size)
        assertTrue(selected.all { id -> chosen.any { it.id == id } })
        assertEquals(4, chosen.count { it.id !in selected })
    }

    @Test fun invalidPickerCapacityRejectedBeforeAnyPrivateNameIsRead() {
        assertThrows(IllegalArgumentException::class.java) {
            selectReviewedPeopleForPicker(emptyList(), emptySet(), "private", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectReviewedPeopleForPicker(emptyList(), emptySet(), "private", 65)
        }
    }
}
