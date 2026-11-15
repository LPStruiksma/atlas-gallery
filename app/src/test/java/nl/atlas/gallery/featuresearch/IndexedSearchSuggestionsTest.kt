package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The indexed path must return the same ordered suggestions as the original scan. */
class IndexedSearchSuggestionsTest {
    private fun person(id: Long, name: String) = SearchSuggestionSource(
        SearchSuggestionKind.CONFIRMED_PERSON, name, personId = id
    )
    private fun album(name: String, path: String, volume: String = "external") =
        SearchSuggestionSource(SearchSuggestionKind.ALBUM, name,
            albumRelativePath = path, albumVolume = volume)

    @Test fun identicalOrderedResultsForMultipleWordsUnicodeAndDuplicateIdentities() {
        val sources = listOf(
            person(9, "Éloïse Summer"), person(2, "Eloise Summer"),
            person(9, "Not the first title"), person(11, "Summer Eloise"),
            album("Eloise Summer", "DCIM/Eloise/", "external"),
            album("Eloise Summer", "DCIM/Eloise/", "sdcard"),
            album("Summer", "DCIM/Summer/"), person(7, "Fictional Ada")
        )
        val index = IndexedSearchSuggestions(sources)
        for (query in listOf("", "elo", "ELOISE", "summer elo", "éloïse", "ada",
                "winter", "notpresent", "eloise summer", "summer eloise",
                "eloise " + " ".repeat(MAX_SEARCH_QUERY_CHARS),
                "eloise " + 0x0301.toChar())) {
            for (limit in listOf(1, 3, 10, 40)) {
                assertEquals("query=$query limit=$limit",
                    planLocalSearchSuggestions(sources, query, limit),
                    index.search(query, limit))
            }
        }
    }

    @Test fun indexedSearchPreservesStableOrderingAndFailClosedAdmission() {
        val sources = (0 until 10_000).map { person(it.toLong(), "Fictional Person $it") } +
            (0 until 2000).map { album("Fictional Album $it", "Pictures/$it/") }
        val index = IndexedSearchSuggestions(sources)
        for (query in listOf("fictional", "person 17", "album 19", "Fictional Person 9999")) {
            assertEquals(planLocalSearchSuggestions(sources, query, 40), index.search(query, 40))
        }
        val invalid = "fictional " + (1..13).joinToString(" ") { "person" }
        assertTrue(index.search(invalid).isEmpty())
        assertTrue(index.search("fictional" + " ".repeat(MAX_SEARCH_QUERY_CHARS)).isEmpty())
    }

    @Test fun benchmark250kSyntheticRowsWithEqualResults() {
        val sources = (0 until 250_000).map { i ->
            if (i % 10 == 0) album("Fictional Album ${i % 1000}", "Synthetic/$i/")
            else person(i.toLong(), "Fictional Person ${i % 5000}")
        }
        val startBuild = System.nanoTime()
        val index = IndexedSearchSuggestions(sources)
        val buildMs = (System.nanoTime() - startBuild) / 1e6
        // Deliberately separate construction from query latency, and keep all
        // outputs synthetic. Timing is diagnostic, never a flaky CI assertion.
        for (query in listOf("fictional", "person 123", "album 9", "notpresent")) {
            val expected = planLocalSearchSuggestions(sources, query, 10)
            val scanStart = System.nanoTime()
            val scanned = planLocalSearchSuggestions(sources, query, 10)
            val scanMs = (System.nanoTime() - scanStart) / 1e6
            val indexedStart = System.nanoTime()
            val indexed = index.search(query, 10)
            val indexedMs = (System.nanoTime() - indexedStart) / 1e6
            assertEquals(expected, scanned)
            assertEquals(expected, indexed)
            println("PERF-12 suggestions: n=250000 query=$query build_ms=%.2f scan_ms=%.2f indexed_ms=%.2f count=%d"
                .format(buildMs, scanMs, indexedMs, indexed.size))
        }
    }
}
