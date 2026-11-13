package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleSearchShortcutsTest {
    private fun person(id: Long, name: String) =
        PersonEntry(id, name, setOf("/storage/emulated/0/DCIM/test-$id.jpg"))

    @Test fun prefixFirstCaseInsensitiveAndNoSpeculativeIdentityMerges() {
        val source = listOf(
            person(71, "Marieke"),
            person(20, "Anne-Marie"),
            person(11, "MAR"),
            person(12, "MAR"), // Distinct identity despite identical visible name.
            person(95, "Mario"),
            person(80, "Oliver")
        )
        val matches = findPeopleForSearch(source, "mar")
        assertEquals(listOf(11L, 12L, 71L, 95L, 20L),
            matches.map { it.id })
        assertEquals(listOf(11L, 12L), matches.filter { it.name == "MAR" }.map { it.id })
        assertTrue(findPeopleForSearch(source, "person who looks like Mar").isEmpty())
    }

    @Test fun emptyOrNonmatchingQueryDoesNotFloodSearchOrInventFaces() {
        val source = (1L..500L).map { person(it, "Synthetic $it") }
        assertTrue(findPeopleForSearch(source, "  ").isEmpty())
        assertTrue(findPeopleForSearch(source, "Zanzibar").isEmpty())
        assertEquals(12, findPeopleForSearch(source, "synthetic").size)
        assertEquals(2, findPeopleForSearch(source, "synthetic", limit = 2).size)
        assertTrue(findPeopleForSearch(source, "synthetic", limit = 0).isEmpty())
        assertEquals(source, source) // No mutation, labels remain reference-only.
    }
}
