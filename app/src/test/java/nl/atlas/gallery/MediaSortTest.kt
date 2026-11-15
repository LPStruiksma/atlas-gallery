package nl.atlas.gallery

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** SORT-0: five keys, two directions, deterministic random and ID tie-breaks. */
class MediaSortTest {
    private fun row(id: Long, name: String, created: Long, modified: Long, size: Long) =
        MediaRecord(id, "content://fiction/$id", name, "DCIM/Test/", size, created,
            false, modifiedAtSeconds = modified)

    private val rows = listOf(
        row(3, "photo10.jpg", 100, 50, 1000),
        row(2, "photo2.jpg", 100, 20, 100),
        row(1, "Photo1.jpg", 200, 80, 500)
    )

    @Test fun eachKeyHasBothDirections() {
        val keys = listOf(
            MediaSortKey.DATE_CREATED to listOf(2L, 3L, 1L),
            MediaSortKey.DATE_MODIFIED to listOf(2L, 3L, 1L),
            MediaSortKey.FILE_NAME to listOf(1L, 2L, 3L),
            MediaSortKey.SIZE to listOf(2L, 1L, 3L)
        )
        for ((key, expected) in keys) {
            assertEquals("$key ascending", expected,
                sortMedia(rows, MediaSort(key, SortDirection.ASCENDING), 5L, Locale.US).map { it.id })
            val descendingExpected = when (key) {
                MediaSortKey.DATE_CREATED -> listOf(1L, 2L, 3L) // stable ID tie-break
                else -> expected.reversed()
            }
            assertEquals("$key descending", descendingExpected,
                sortMedia(rows, MediaSort(key, SortDirection.DESCENDING), 5L, Locale.US).map { it.id })
        }
    }

    @Test fun tiedValuesUseIdRegardlessOfInputOrder() {
        val tied = listOf(row(9, "same", 7, 7, 7), row(2, "same", 7, 7, 7))
        for (key in MediaSortKey.entries.filter { it != MediaSortKey.RANDOM })
            for (direction in SortDirection.entries) {
            val mode = MediaSort(key, direction)
            val expected = sortMedia(tied, mode, 10L).map { it.id }
            assertEquals(listOf(2L, 9L), expected)
            assertEquals(expected, sortMedia(tied.reversed(), mode, 10L).map { it.id })
        }
    }

    @Test fun randomCollisionsStillUseIdTieBreak() {
        val tied = listOf(row(9, "same", 7, 7, 7), row(2, "same", 7, 7, 7))
        for (direction in SortDirection.entries) {
            val sort = MediaSort(MediaSortKey.RANDOM, direction)
            assertEquals(sortMedia(tied, sort, 10L).map { it.id },
                sortMedia(tied.reversed(), sort, 10L).map { it.id })
        }
    }

    @Test fun randomSeedAndDirectionAreRepeatable() {
        val many = (0L until 50L).map { row(it, "f$it", it, it, it) }
        val ascending = MediaSort(MediaSortKey.RANDOM, SortDirection.ASCENDING)
        val descending = ascending.copy(direction = SortDirection.DESCENDING)
        val original = sortMedia(many, ascending, 125L).map { it.id }
        assertEquals(original, sortMedia(many.reversed(), ascending, 125L).map { it.id })
        assertEquals(original.reversed(), sortMedia(many, descending, 125L).map { it.id })
        assertNotEquals(original, sortMedia(many, ascending, 126L).map { it.id })
    }

    @Test fun optionsAndExtrasDoNotLoseStandardKeys() {
        assertEquals(5, MediaSortKey.entries.size)
        assertEquals(5, MediaSort.standardOptions.size)
        assertEquals(MediaSortKey.DATE_CREATED, MediaSort.default.key)
    }
}
