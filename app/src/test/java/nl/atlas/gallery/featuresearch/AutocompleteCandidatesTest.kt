package nl.atlas.gallery.featuresearch

import org.junit.Assert.*
import org.junit.Test

class AutocompleteCandidatesTest {
    private val scope = SearchHistoryScope.ALBUM
    private fun history(vararg entries: SearchHistoryEntry) =
        SearchHistoryState(scopes = mapOf(scope to entries.toList()))
    private fun person(key: String, name: String, rank: Int) =
        AutocompletePerson(SearchPerson(key, name), rank)
    private fun album(path: String, name: String, volume: String = "primary") =
        AutocompleteAlbum(volume, path, name)
    private fun displays(index: AutocompleteCandidateIndex, query: String) =
        index.search(query).map { it.display }

    @Test fun groupOrderCapsAndForwardSpill() {
        val people = listOf(person("2", "Marina", 2), person("1", "Marcel", 1),
            person("3", "Marco", 3), person("4", "Marta", 4))
        val albums = listOf(album("b", "Market"), album("a", "Mar"),
            album("c", "Marsh"), album("d", "Marathon"))
        val all = AutocompleteCandidateIndex(scope, history(SearchHistoryEntry("Mar holiday"),
            SearchHistoryEntry("Mar trip"), SearchHistoryEntry("Mar old")), people, albums,
            listOf(AutocompleteDate(2025, 3, 1)), listOf("mar_file"))
        assertEquals(listOf("Mar holiday", "Mar trip", "Marcel", "Marina", "Mar"),
            displays(all, "mar"))
        val withoutHistory = AutocompleteCandidateIndex(scope, people = people, albums = albums)
        assertEquals(listOf("Marcel", "Marina", "Marco", "Marta", "Mar"),
            displays(withoutHistory, "mar"))
        val onlyAlbums = AutocompleteCandidateIndex(scope, albums = albums)
        assertEquals(listOf("Mar", "Marsh", "Market", "Marathon"), displays(onlyAlbums, "mar"))
        val onlyFiles = AutocompleteCandidateIndex(scope,
            filePrefixes = (1..8).map { "mar_$it" })
        assertEquals((1..5).map { "mar_$it" }, displays(onlyFiles, "mar"))
    }

    @Test fun wordPrefixUnicodeAdmissionAndStableTies() {
        val index = AutocompleteCandidateIndex(scope, people = listOf(
            person("z", "Élodie de Vries", 0), person("a", "Elodie de Vries", 0)),
            albums = listOf(album("a", "Élodie de Vries"), album("b", "Preview")),
            filePrefixes = listOf("elodie_photo"))
        val rows = index.search("E\u0301LO vri")
        assertEquals(1, rows.size)
        assertEquals(AutocompleteIdentity.Person("a"), rows.single().identity)
        assertTrue(index.search("lod").isEmpty())
        assertTrue(index.search("elo missing").isEmpty())
        assertTrue(index.search(" ").isEmpty())
        assertTrue(index.search("e".repeat(MAX_SEARCH_QUERY_CHARS + 1)).isEmpty())
        assertTrue(index.search(List(MAX_SEARCH_QUERY_TERMS + 1) { "e" }.joinToString(" ")).isEmpty())
        assertTrue(index.search("\u0301").isEmpty())
        assertEquals(listOf("Elodie de Vries", "elodie_photo"), displays(index, "elo"))
        val albums = listOf(album("c", "Trip", "a:b"), album("b:c", "Trip", "a"))
        val one = AutocompleteCandidateIndex(scope, albums = albums).search("tri").single()
        val two = AutocompleteCandidateIndex(scope, albums = albums.reversed()).search("tri").single()
        assertEquals(one.identity, two.identity)
        assertEquals(AutocompleteIdentity.Album("a", "b:c"), one.identity)
        assertNotEquals(AutocompleteIdentity.Album("a:b", "c"), one.identity)
    }

    @Test fun datesAndFileThreshold() {
        val index = AutocompleteCandidateIndex(scope, dates = listOf(
            AutocompleteDate(2023, 3, 1), AutocompleteDate(2025, 3, 2),
            AutocompleteDate(2026, 3, 0), AutocompleteDate(2025, 1, 1)),
            filePrefixes = listOf("march_photo", "im_1"))
        assertEquals(listOf("March 2025", "March 2023", "march_photo"), displays(index, "mar"))
        assertEquals(listOf("January 2025", "March 2025", "2025").toSet(),
            displays(index, "2025").toSet())
        assertTrue(index.search("2026").isEmpty())
        assertTrue(index.search("im").isEmpty())
        assertEquals(listOf("im_1"), displays(index, "im 1"))
        assertEquals(listOf("im_1"), displays(index, "im_"))
    }

    @Test fun historyScopePrivacyAndSnapshotIsolation() {
        val h = history(SearchHistoryEntry("trip", listOf("visible")),
            SearchHistoryEntry("trip", listOf("stale")), SearchHistoryEntry("trip old"))
            .copy(scopes = mapOf(scope to listOf(SearchHistoryEntry("trip", listOf("visible")),
                SearchHistoryEntry("trip", listOf("stale")), SearchHistoryEntry("trip old")),
                SearchHistoryScope.GLOBAL to listOf(SearchHistoryEntry("trip global"))))
        val people = mutableListOf(person("visible", "Current Name", 0))
        val albums = mutableListOf(album("local", "Trip local"))
        val index = AutocompleteCandidateIndex(scope, h, people, albums)
        people.clear()
        albums.clear()
        assertEquals(listOf("Current Name trip", "trip old", "Trip local"), displays(index, "trip"))
        assertEquals(AutocompleteIdentity.History(scope, "trip", listOf("visible")),
            index.search("trip").first().identity)
        assertEquals(listOf("trip old"), displays(AutocompleteCandidateIndex(scope, h), "trip"))
        assertTrue(AutocompleteCandidateIndex(scope, h.copy(enabled = false)).search("trip").isEmpty())
        assertEquals(listOf("trip global"),
            displays(AutocompleteCandidateIndex(SearchHistoryScope.GLOBAL, h), "trip"))
    }

    @Test fun synthetic250kLookupDiagnostic() {
        val prefixes = List(250_000) { "img_" + it.toString().padStart(6, '0') }
        val index = AutocompleteCandidateIndex(scope, filePrefixes = prefixes)
        val queries = listOf("img", "img 000", "img_249999", "img 999999", "000", "249", "img missing")
        repeat(30) { queries.forEach { index.search(it) } }
        val times = LongArray(210) { i ->
            val start = System.nanoTime()
            val rows = index.search(queries[i % queries.size])
            val elapsed = System.nanoTime() - start
            assertTrue(rows.size <= 5)
            elapsed
        }.sorted()
        assertEquals(listOf("img_249999"), displays(index, "img 249999"))
        assertTrue(index.search("img 999999").isEmpty())
        val p95Ms = times[199] / 1_000_000.0
        println("Autocomplete 250k synthetic JVM lookup p95=$p95Ms ms; target <=50 ms; met=${p95Ms <= 50.0}")
    }
}
