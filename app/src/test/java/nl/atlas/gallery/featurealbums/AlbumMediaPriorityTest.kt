package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class AlbumMediaPriorityTest {
    private fun media(
        id: Long, taken: Long, uri: String = "content://atlas/image/$id"
    ) = MediaRecord(
        id = id, uri = uri, name = "Synthetic $id",
        relativePath = "Pictures/", sizeBytes = 100L,
        takenAtMillis = taken, isVideo = false
    )

    @Test fun equalUriDateSourceVersionsHaveOneStableWinner() {
        val earliest = media(1, 10).copy(
            generationModified = 4, modifiedAtSeconds = 500,
            providerVersion = "a"
        )
        val newerGeneration = earliest.copy(
            generationModified = 5, modifiedAtSeconds = 400
        )
        val newestRevision = newerGeneration.copy(
            modifiedAtSeconds = 450
        )
        val records = listOf(newerGeneration, newestRevision, earliest)
        for (iteration in listOf(records, records.reversed())) {
            assertSame(newestRevision, iteration.maxWith(::compareAlbumMediaPriority))
        }
        assertTrue(compareAlbumMediaPriority(earliest, newerGeneration) < 0)
        assertTrue(compareAlbumMediaPriority(newerGeneration, newestRevision) < 0)
        assertEquals(0, compareAlbumMediaPriority(earliest, earliest))
    }

    @Test fun captureDateAndUriStillOutrankRevisionTieBreak() {
        val newerPhoto = media(1, 11)
        val olderWithHugeRevision = media(2, 10).copy(
            generationModified = Long.MAX_VALUE,
            modifiedAtSeconds = Long.MAX_VALUE
        )
        assertTrue(compareAlbumMediaPriority(newerPhoto, olderWithHugeRevision) > 0)
        val left = media(3, 10, "content://atlas/image/20")
        val right = media(4, 10, "content://atlas/image/21")
        assertTrue(compareAlbumMediaPriority(right, left) > 0)
    }

    @Test fun revisionComparatorIsAntisymmetricAndTransitiveUnderFuzz() {
        val random = java.util.Random(20261024L)
        val records = (0 until 350).map { n ->
            media(n.toLong(), (random.nextInt(5) - 2).toLong(),
                "content://atlas/image/" + random.nextInt(12)).copy(
                generationModified = random.nextInt(5).toLong(),
                modifiedAtSeconds = random.nextInt(9).toLong(),
                providerVersion = listOf(null, "v1", "v2")[random.nextInt(3)],
                sizeBytes = random.nextInt(200).toLong(),
                widthPixels = random.nextInt(300),
                orientationDegrees = random.nextInt(4) * 90,
                isFavorite = random.nextBoolean()
            )
        }
        for (a in records) for (b in records) {
            assertEquals(
                compareAlbumMediaPriority(a, b).sign(),
                -compareAlbumMediaPriority(b, a).sign()
            )
        }
        repeat(25_000) {
            val a = records[random.nextInt(records.size)]
            val b = records[random.nextInt(records.size)]
            val c = records[random.nextInt(records.size)]
            val ab = compareAlbumMediaPriority(a, b)
            val bc = compareAlbumMediaPriority(b, c)
            if (ab <= 0 && bc <= 0) {
                assertTrue(compareAlbumMediaPriority(a, c) <= 0)
            }
        }
    }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
