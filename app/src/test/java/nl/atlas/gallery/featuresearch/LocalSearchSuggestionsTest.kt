package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchSuggestionsTest {
    private fun person(id: Long, name: String) = SearchSuggestionSource(
        SearchSuggestionKind.CONFIRMED_PERSON, name, personId = id
    )
    private fun album(title: String, path: String, volume: String? = null) =
        SearchSuggestionSource(
            SearchSuggestionKind.ALBUM, title,
            albumRelativePath = path, albumVolume = volume
        )

    @Test fun blankDoesNotSurfacePrivateNames() {
        val sources = listOf(person(1, "Fictional Ana"))
        assertTrue(planLocalSearchSuggestions(sources, "").isEmpty())
        assertTrue(planLocalSearchSuggestions(sources, "   ").isEmpty())
    }

    @Test fun duplicateNamesRemainDistinctReviewedPeople() {
        val sources = listOf(person(9, "Ana"), person(2, "Ana"), person(2, "Ana"))
        assertEquals(listOf(2L, 9L), planLocalSearchSuggestions(sources, "ana")
            .map { it.source.personId })
    }

    @Test fun albumNamesRemainSeparateByExactVolumeAndPath() {
        val sources = listOf(
            album("Beach", "DCIM/Beach/", "external"),
            album("Beach", "DCIM/Beach/", "sdcard"),
            album("Beach", "Pictures/Beach/", "external")
        )
        assertEquals(3, planLocalSearchSuggestions(sources, "beach").size)
        assertEquals(3, planLocalSearchSuggestions(sources.reversed(), "beach").size)
    }

    @Test fun delimiterInAlbumPathCannotCollideWithVolume() {
        val sources = listOf(
            album("Collision", "c", "a:b"),
            album("Collision", "b:c", "a")
        )
        assertEquals(2, planLocalSearchSuggestions(sources, "collision").size)
    }

    @Test fun unicodeSearchIsCaseAndAccentInsensitiveWithoutIdentityFuzziness() {
        val sources = listOf(person(1, "Éloïse"), album("Éloïse Album", "Pictures/Eloise/"))
        assertEquals(2, planLocalSearchSuggestions(sources, "eLoIsE").size)
        assertTrue(planLocalSearchSuggestions(sources, "Eloiza").isEmpty())
    }

    @Test fun sharedLexicalNormalizationKeepsDecomposedAccentsConsistent() {
        val composed = person(9, "Éloïse")
        val decomposed = person(10, "E\u0301loi\u0308se")
        assertEquals("eloise", plainSearchText(composed.title))
        assertEquals("eloise", plainSearchText(decomposed.title))
        assertEquals(listOf(9L, 10L), planLocalSearchSuggestions(
            listOf(composed, decomposed), "ELOISE"
        ).mapNotNull { it.source.personId })
        assertEquals("plain", plainSearchText(" PLAIN "))
    }

    @Test fun suggestionsNeverExposeNamesForQueriesSearchWouldRefuse() {
        val sources = listOf(person(1, "Ana"), album("Ana", "Pictures/Ana/"))
        assertEquals(2, planLocalSearchSuggestions(sources, "ana").size)
        // A trailing whitespace overflow previously normalized to "ana",
        // returning private Person names for a rejected query.
        assertTrue(planLocalSearchSuggestions(sources,
            "ana" + " ".repeat(MAX_SEARCH_QUERY_CHARS - 2)).isEmpty())
        assertTrue(planLocalSearchSuggestions(sources,
            "ana " + 0x0301.toChar()).isEmpty())
        val thirteen = List(MAX_SEARCH_QUERY_TERMS + 1) { "ana" }.joinToString(" ")
        assertTrue(planLocalSearchSuggestions(sources, thirteen).isEmpty())
        assertEquals(2, planLocalSearchSuggestions(sources,
            "ana " + " ".repeat(MAX_SEARCH_QUERY_CHARS - 4)).size)
    }

    @Test fun stableBoundAndLargeFictionalMetadata() {
        val sources = (1..2000).map { person(it.toLong(), "Fictional Person $it") } +
            (1..800).map { album("Album $it", "DCIM/Fictional_$it/") }
        val found = planLocalSearchSuggestions(sources, "fictional person 19", 10)
        assertEquals(10, found.size)
        assertTrue(found.all { it.source.kind == SearchSuggestionKind.CONFIRMED_PERSON })
        assertEquals(found, planLocalSearchSuggestions(sources.reversed(), "fictional person 19", 10))
        assertFalse(found.any { it.source.personId == null })
    }

    @Test fun invalidLimitRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            planLocalSearchSuggestions(emptyList(), "x", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planLocalSearchSuggestions(emptyList(), "x", 41)
        }
    }

    @Test fun malformedSuggestionSourceFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchSuggestionSource(SearchSuggestionKind.CONFIRMED_PERSON, "Anon")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SearchSuggestionSource(SearchSuggestionKind.ALBUM, "Album")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SearchSuggestionSource(SearchSuggestionKind.ALBUM, " ", albumRelativePath = "")
        }
    }
}
