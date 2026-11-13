package nl.atlas.gallery.featuresearch

import java.util.Calendar
import java.util.TimeZone
import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #554: name suggestions, person chips and person + date/word intersection. */
class PersonNameSearchTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val susanne = SearchPerson("p-sus", "Süsanne de Vries")
    private val sam = SearchPerson("p-sam", "Sam")
    private val anna = SearchPerson("p-anna", "Anna")
    private val june = SearchPerson("p-june", "June")
    private val people = listOf(anna, sam, susanne, june)

    private fun millis(year: Int, month: Int) = Calendar.getInstance(utc).apply {
        clear(); set(year, month - 1, 15, 12, 0, 0)
    }.timeInMillis

    private fun photo(id: Long, takenAt: Long, folder: String = "DCIM/Camera/",
                      name: String = "IMG_$id.jpg") =
        MediaRecord(id, "content://media/external/images/media/$id", name, folder, 100, takenAt, false)

    private fun search(media: List<MediaRecord>, filters: LocalSearchFacets,
                       uris: Map<String, Set<String>> = emptyMap()) =
        searchWithFacets(media, emptyList(), filters, mediaVolumeOf = { it.volumeName },
            nativePersonUris = uris, timeZone = utc).map { it.id }

    @Test fun prefixFindsPersonAccentAndCaseInsensitive() {
        assertEquals(listOf(sam, susanne), matchPeopleByName(people, "s").map { it.person })
        assertEquals(listOf(susanne), matchPeopleByName(people, "SUS").map { it.person })
        assertEquals(listOf(susanne), matchPeopleByName(people, "süs").map { it.person })
        // Any word of the name, by prefix.
        assertEquals(listOf(susanne), matchPeopleByName(people, "vri").map { it.person })
        // Not a substring in the middle of a word.
        assertTrue(matchPeopleByName(people, "anne").isEmpty())
    }

    @Test fun remainderKeepsTheOtherWordsInTheUsersSpelling() {
        val m = matchPeopleByName(people, "sus 2019 Beach").single()
        assertEquals(susanne, m.person)
        assertEquals("2019 Beach", m.remainder)
        assertEquals("", matchPeopleByName(people, "susanne vries").single().remainder)
    }

    @Test fun yearNeverPrefixesANameButAMonthNameCanBeAName() {
        assertTrue(matchPeopleByName(listOf(SearchPerson("x", "2019 crew")), "201").isNotEmpty())
        assertTrue(matchPeopleByName(people, "2019").isEmpty())
        val m = matchPeopleByName(people, "june 2019").single()
        assertEquals(june, m.person)
        assertEquals("2019", m.remainder)
    }

    @Test fun moreMatchedWordsRankFirstAndHomonymsStaySeparate() {
        val twin = SearchPerson("p-sus-2", "Susanne")
        val ranked = matchPeopleByName(people + twin, "susanne vries")
        assertEquals(listOf(susanne, twin), ranked.map { it.person })
        val both = matchPeopleByName(listOf(twin, SearchPerson("p-sus-3", "Susanne")), "susanne")
        assertEquals(2, both.size)
    }

    @Test fun exactPersonNeedsTheFullNameAndOneCandidate() {
        assertEquals(susanne, exactPersonInQuery(people, "Susanne de Vries 2019")?.person)
        assertEquals("2019", exactPersonInQuery(people, "Susanne de Vries 2019")?.remainder)
        assertNull(exactPersonInQuery(people, "Susanne 2019"))
        assertEquals(sam, exactPersonInQuery(people, "sam")?.person)
        assertNull(exactPersonInQuery(people, "sa"))
        assertNull(exactPersonInQuery(people + SearchPerson("p-sam-2", "Sam"), "sam"))
    }

    @Test fun exactPersonIsFoundBeyondTheSuggestionCap() {
        // 50 people who match both typed words, so they rank ahead of "Ann".
        val crowd = (1..50).map { SearchPerson("p-crowd-$it", "Ann 2019 Club $it") }
        val ann = SearchPerson("p-ann", "Ann")
        val roster = crowd + ann
        assertEquals(ann, exactPersonInQuery(roster, "ann 2019")?.person)
        assertEquals("2019", exactPersonInQuery(roster, "ann 2019")?.remainder)
        assertTrue(ann !in matchPeopleByName(roster, "ann 2019", limit = 40).map { it.person })
    }

    @Test fun invalidQueriesSuggestNobody() {
        assertTrue(matchPeopleByName(people, "").isEmpty())
        assertTrue(matchPeopleByName(people, "s".repeat(MAX_SEARCH_QUERY_CHARS + 1)).isEmpty())
    }

    @Test fun dateTokens() {
        assertEquals(SearchDateToken.Year(2019), searchDateToken("2019"))
        assertEquals(SearchDateToken.Month(5), searchDateToken("mei"))
        assertEquals(SearchDateToken.Month(3), searchDateToken("march"))
        assertNull(searchDateToken("jan"))
        assertNull(searchDateToken("1899"))
        assertNull(searchDateToken("20190"))
    }

    @Test fun personPlusYearIntersectsHerPhotosWithThatYear() {
        val a = photo(1, millis(2019, 6))
        val b = photo(2, millis(2020, 6))
        val c = photo(3, millis(2019, 8))
        val media = listOf(a, b, c)
        val hers = mapOf(susanne.key to setOf(a.uri, b.uri))
        val chip = LocalSearchFacets(nativePersonKeys = setOf(susanne.key))
        assertEquals(listOf(1L, 2L), search(media, chip, hers))
        assertEquals(listOf(1L), search(media, chip.copy(text = "2019"), hers))
        assertEquals(listOf(1L, 2L), search(media, chip.copy(text = "june"), hers))
        assertEquals(listOf(1L), search(media, chip.copy(text = "june 2019"), hers))
        assertEquals(listOf<Long>(), search(media, chip.copy(text = "2019 august"), hers))
        // Without a person, the year alone finds every 2019 photo.
        assertEquals(listOf(1L, 3L), search(media, LocalSearchFacets(text = "2019")))
    }

    @Test fun personPlusAlbumWordAndTwoPeopleMeanAnd() {
        val beach = photo(1, millis(2019, 6), folder = "Pictures/Beach/")
        val home = photo(2, millis(2019, 6), folder = "Pictures/Home/")
        val media = listOf(beach, home)
        val uris = mapOf(susanne.key to setOf(beach.uri, home.uri), sam.key to setOf(home.uri))
        assertEquals(listOf(1L), search(media,
            LocalSearchFacets(text = "beach", nativePersonKeys = setOf(susanne.key)), uris))
        assertEquals(listOf(2L), search(media,
            LocalSearchFacets(nativePersonKeys = setOf(susanne.key, sam.key)), uris))
    }

    @Test fun unresolvedPersonFailsClosed() {
        val a = photo(1, millis(2019, 6))
        assertEquals(listOf<Long>(), search(listOf(a),
            LocalSearchFacets(nativePersonKeys = setOf(susanne.key)), emptyMap()))
    }

    @Test fun unknownDateNeverMatchesAYearButTextStillDoes() {
        val undated = photo(1, 0L, name = "2019_trip.jpg")
        val plain = photo(2, 0L)
        assertEquals(listOf(1L), search(listOf(undated, plain), LocalSearchFacets(text = "2019")))
    }

    @Test fun recentLabelReadsLikeTheSearch() {
        val f = LocalSearchFacets(text = " 2019 ", nativePersonKeys = setOf(susanne.key))
        assertEquals("Süsanne de Vries 2019", personSearchLabel(people, f))
        assertEquals("Sam", personSearchLabel(people, LocalSearchFacets(nativePersonKeys = setOf(sam.key))))
    }

    @Test fun recentsKeepTenLikeSamsung() {
        var h = EphemeralSearchHistory()
        repeat(12) { h = h.remember("q$it") }
        assertEquals(10, h.terms.size)
        assertEquals("q11", h.terms.first())
    }
}
