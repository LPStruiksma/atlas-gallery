package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** No real photographs, person identities or biometric inference. */
class AsyncPersonAlbumLogicTest {
    private fun invented(index: Int) = MediaRecord(
        index.toLong(), "content://atlas.synthetic/$index", "fictional-$index.jpg",
        when (index % 4) {
            0 -> "DCIM/Black and White/"
            1 -> "DCIM/Black and White/sub/"
            2 -> "DCIM/Black and White-ish/"
            else -> "DCIM/Fictional/"
        },
        1024L, index.toLong(), isVideo = index % 17 == 0
    )

    @Test fun largeConfirmedPersonAlbumRetainsExactPathsSortAndBwOnlyContract() {
        val sorted = (0 until 150_000).map(::invented)
        val paths = sorted.filterIndexed { index, _ -> index % 7 == 0 }
            .map { it.absolutePath }.toSet()
        // Reviewed V4 People identities are image-only: a video sharing a
        // confirmed photo's path must never inherit that identity (6f833df).
        val expected = sorted.filter { !it.isVideo && it.absolutePath in paths }
        var checks = 0
        val all = selectPersonPhotos(sorted, paths,
            cancellationCheckpoint = { checks++ })
        assertEquals(expected, all)
        assertTrue(checks >= 293)
        val bw = selectPersonPhotos(sorted, paths, true)
        assertEquals(expected.filter {
            !it.isVideo && isInBlackAndWhiteFolder(it.relativePath)
        }, bw)
        assertTrue(bw.all { !it.isVideo })
        assertFalse(bw.any { it.relativePath == "DCIM/Black and White-ish/" })
    }

    @Test fun namedPersonIdAndSourceGenerationsMustMatchExactly() {
        val media = (0 until 2_000).map(::invented)
        val a = PersonEntry(41L, "Alex Example",
            setOf(media[0].absolutePath, media[3].absolutePath))
        val sameNameOtherId = PersonEntry(42L, "Alex Example",
            setOf(media[1].absolutePath))
        val people = listOf(a, sameNameOtherId)
        val selected = selectPersonPhotos(media, a.imagePaths)
        val snapshot = PersonAlbumSnapshot(media, people, a.id, false, selected)
        assertSame(selected, currentPersonAlbumOrNull(snapshot, media, people, 41L, false))
        assertNull(currentPersonAlbumOrNull(snapshot, media, people, 42L, false))
        assertNull(currentPersonAlbumOrNull(snapshot, media, people, 41L, true))
        assertNull(currentPersonAlbumOrNull(snapshot, media.toList(), people, 41L, false))
        assertNull(currentPersonAlbumOrNull(snapshot, media, people.toList(), 41L, false))
        assertNull(currentPersonAlbumOrNull(snapshot, media, people, null, false))
        assertEquals(listOf(media[1]),
            selectPersonPhotos(media, sameNameOtherId.imagePaths))
        assertEquals(emptyList<MediaRecord>(), selectPersonPhotos(media,
            setOf("/storage/emulated/0/DCIM/Fictional/nonexistent.jpg")))
    }

    @Test fun canceledOldPeopleFilterDoesNotProducePartialMembership() {
        val media = (0 until 150_000).map(::invented)
        val approved = setOf(media.last().absolutePath)
        var checkpoints = 0
        try {
            selectPersonPhotos(media, approved, cancellationCheckpoint = {
                if (++checkpoints == 3) throw IllegalStateException("Old People generation")
            })
            fail("Expected cancellation")
        } catch (error: IllegalStateException) {
            assertEquals("Old People generation", error.message)
        }
        assertEquals(3, checkpoints)
    }
}
