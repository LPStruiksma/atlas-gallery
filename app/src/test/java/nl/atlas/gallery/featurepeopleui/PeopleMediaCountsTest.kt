package nl.atlas.gallery.featurepeopleui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Invented counts only; JVM test does not access MediaStore or personal media. */
class PeopleMediaCountsTest {
    private val nl = Locale.forLanguageTag("nl-NL")
    private val us = Locale.forLanguageTag("en-US")

    @Test fun zeroSingularAndPluralCounts() {
        assertEquals("0 photos", peopleMediaCountLabel(0, 0, us))
        assertEquals("1 photo", peopleMediaCountLabel(1, 0, us))
        assertEquals("1 video", peopleMediaCountLabel(0, 1, us))
        assertEquals("2 photos", peopleMediaCountLabel(2, 0, us))
        assertEquals("2 videos", peopleMediaCountLabel(0, 2, us))
        assertEquals("1 photo · 1 video", peopleMediaCountLabel(1, 1, us))
        assertEquals("2 photos · 3 videos", peopleMediaCountLabel(2, 3, us))
    }

    @Test fun localeGroupingAndSpokenClauses() {
        assertEquals("12.305 photos · 45 videos", peopleMediaCountLabel(12305, 45, nl))
        assertEquals("12,305 photos · 45 videos", peopleMediaCountLabel(12305, 45, us))
        assertEquals("Anna, 12,305 photos, 45 videos",
            peopleMediaCountContentDescription("Anna", 12305, 45, us))
        assertEquals("Anna, 12.305 photos, 45 videos",
            peopleMediaCountContentDescription("Anna", 12305, 45, nl))
    }

    @Test fun splitExactlyMatchesDistinctSortStatistic() {
        for ((photos, videos, mediaCount) in listOf(
            Triple(0, 0, 0), Triple(1, 0, 1), Triple(0, 1, 1),
            Triple(3, 1, 4), Triple(12305, 45, 12350)
        )) {
            assertEquals(mediaCount, PeopleMediaCounts(photos, videos).mediaCount)
        }
    }

    @Test fun negativeCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PeopleMediaCounts(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { peopleMediaCountLabel(0, -1, us) }
    }
}
