package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented people only; no files, faces or MediaStore access. */
class PeopleSortTest {
    private val rows = listOf(
        PeopleSortEntry(1, "Zoe", PeopleSortStats(2, 100L, 900L)),
        PeopleSortEntry(2, "Ada", PeopleSortStats(8, 200L, 600L)),
        PeopleSortEntry(3, "Ben", PeopleSortStats(8, 300L, 1000L)),
        PeopleSortEntry(4, "", PeopleSortStats(50, 400L, 1200L)),
        PeopleSortEntry(5, "Undated", PeopleSortStats(1, null, null))
    )
    private fun ids(key: PeopleSortKey, desc: Boolean) =
        orderPeopleEntries(rows, PeopleSortChoice(key, desc)).map { it.id }

    @Test fun countsUseDistinctMediaStatisticNotFaceCount() {
        assertEquals(listOf(2, 3, 1, 5, 4), ids(PeopleSortKey.MEDIA_COUNT, true))
        assertEquals(listOf(5, 1, 2, 3, 4), ids(PeopleSortKey.MEDIA_COUNT, false))
    }

    @Test fun nameBothDirectionsKeepUnnamedLast() {
        assertEquals(listOf(2, 3, 5, 1, 4), ids(PeopleSortKey.NAME, false))
        assertEquals(listOf(1, 5, 3, 2, 4), ids(PeopleSortKey.NAME, true))
    }

    @Test fun mostRecentDatesAndNulls() {
        assertEquals(listOf(3, 1, 2, 5, 4), ids(PeopleSortKey.MOST_RECENT, true))
        assertEquals(listOf(2, 1, 3, 5, 4), ids(PeopleSortKey.MOST_RECENT, false))
    }

    @Test fun firstAppearanceDatesAndNulls() {
        assertEquals(listOf(3, 2, 1, 5, 4), ids(PeopleSortKey.FIRST_APPEARANCE, true))
        assertEquals(listOf(1, 2, 3, 5, 4), ids(PeopleSortKey.FIRST_APPEARANCE, false))
    }

    @Test fun randomIsReproducibleAndReversibleForNamedEntries() {
        val choice = PeopleSortChoice(PeopleSortKey.RANDOM, false)
        val forward = orderPeopleEntries(rows, choice, seed = 7).map { it.id }
        assertEquals(forward, orderPeopleEntries(rows, choice, seed = 7).map { it.id })
        assertEquals(forward.take(4).reversed(),
            orderPeopleEntries(rows, choice.copy(descending = true), seed = 7)
                .map { it.id }.take(4))
        assertEquals(4, forward.last())
        assertTrue(forward.take(4).toSet() == setOf(1, 2, 3, 5))
    }
}
