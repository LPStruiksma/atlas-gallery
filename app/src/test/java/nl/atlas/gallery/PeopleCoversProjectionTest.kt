package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/**
 * Uses invented media/People memberships. Proves cover selection, exact-path
 * counts, cancellation and generation safety without accessing private data.
 */
class PeopleCoversProjectionTest {
    private fun asset(i: Int, folder: String = "DCIM/Fictional/") = MediaRecord(
        i.toLong(), "content://atlas.synthetic/media/$i", "synthetic-$i.jpg",
        folder, 512, i.toLong(), false
    )

    @Test fun onlyConfirmedPathsAreIndexedAt150kAndMissingEntriesAreExplicit() {
        val media = (0 until 150_000).map(::asset)
        val first = media[17].absolutePath
        val later = media[149_987].absolutePath
        val missing = "/storage/emulated/0/DCIM/Fictional/not-installed.jpg"
        val people = listOf(
            PersonEntry(7L, "Alex Example", setOf(first, later, missing)),
            PersonEntry(8L, "Alex Example", setOf(later)),
            PersonEntry(9L, "Another", emptySet())
        )
        var checkpoints = 0
        val projection = buildPeopleCovers(media, people) { checkpoints++ }
        assertTrue(checkpoints > 293)
        assertEquals(setOf(first, later), projection.coversByPath.keys)
        assertEquals(media[17], projection.coversByPath[first])
        assertEquals(media[149_987], projection.coversByPath[later])
        assertEquals(mapOf(7L to 2, 8L to 1, 9L to 0), projection.accessibleCounts)
        assertEquals(2, projection.coversByPersonId.size)
        assertEquals(people[0].imagePaths.firstNotNullOfOrNull {
            media.associateBy { row -> row.absolutePath }[it]
        }, projection.coversByPersonId[7L])
        assertEquals(media[149_987], projection.coversByPersonId[8L])
        assertFalse(projection.coversByPersonId.containsKey(9L))
        assertSame(projection, currentPeopleCoversOrNull(projection, media, people))
        assertNull(currentPeopleCoversOrNull(projection, media.toList(), people))
        assertNull(currentPeopleCoversOrNull(projection, media, people.toList()))
        assertNull(currentPeopleCoversOrNull(null, media, people))
    }

    @Test fun lastMediaRowWinsForDuplicatedAbsolutePathJustAsPriorAssociateBy() {
        val original = asset(1)
        val sameAbsolutePath = original.copy(id = 42L,
            uri = "content://atlas.synthetic/media/42", sizeBytes = 999L)
        val others = (2..100).map(::asset)
        val all = listOf(original) + others + sameAbsolutePath
        val named = listOf(PersonEntry(99L, "Fictional", setOf(original.absolutePath)))
        val snapshot = buildPeopleCovers(all, named)
        assertEquals(1, snapshot.coversByPath.size)
        assertEquals(sameAbsolutePath, snapshot.coversByPath[original.absolutePath])
        assertEquals(1, snapshot.accessibleCounts[99L])
        assertEquals(sameAbsolutePath, snapshot.coversByPersonId[99L])
        assertEquals(all.associateBy { it.absolutePath }[original.absolutePath],
            snapshot.coversByPath[original.absolutePath])
    }

    @Test fun canceledSnapshotDoesNotPublishPartialCounts() {
        val media = (0 until 150_000).map(::asset)
        val people = listOf(PersonEntry(1L, "Case",
            setOf(media[149_999].absolutePath)))
        var calls = 0
        try {
            buildPeopleCovers(media, people) {
                if (++calls == 3) throw IllegalStateException("superseded")
            }
            fail("Should cancel")
        } catch (ex: IllegalStateException) {
            assertEquals("superseded", ex.message)
        }
        assertEquals(3, calls)
    }

    @Test fun sourceKeysNeverCallPotentiallyHugeListEquals() {
        class NoStructuralEquality(
            private val records: List<MediaRecord>
        ) : List<MediaRecord> by records {
            override fun equals(other: Any?): Boolean =
                throw AssertionError("Structural list equality on UI thread")
            override fun hashCode(): Int =
                throw AssertionError("Structural list hashing on UI thread")
        }
        val records = listOf(asset(1), asset(2))
        val sameObject = NoStructuralEquality(records)
        val otherGeneration = NoStructuralEquality(records)
        assertEquals(CatalogSourceKey(sameObject), CatalogSourceKey(sameObject))
        assertNotEquals(CatalogSourceKey(sameObject), CatalogSourceKey(otherGeneration))
        assertEquals(System.identityHashCode(sameObject), CatalogSourceKey(sameObject).hashCode())
    }
}
