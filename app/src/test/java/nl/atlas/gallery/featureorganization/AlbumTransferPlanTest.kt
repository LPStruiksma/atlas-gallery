package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurealbums.AlbumBucketKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #568: Samsung Copy/Move to album planning, names and the result toast. */
class AlbumTransferPlanTest {
    private fun record(id: Long, name: String, path: String, volume: String = "external_primary") =
        MediaRecord(id, "content://media/$volume/images/media/$id", name, path, 100L, 0L, false,
            volumeName = volume)

    private val trip = AlbumTransferTarget("external_primary", "Pictures/Trip/", "Trip")

    @Test fun freeNameNumbersLikeSamsung() {
        assertEquals("IMG.jpg", freeDisplayName("IMG.jpg", setOf("other.jpg")))
        assertEquals("IMG(1).jpg", freeDisplayName("IMG.jpg", setOf("IMG.jpg")))
        assertEquals("IMG(2).jpg", freeDisplayName("IMG.jpg", setOf("img.JPG", "IMG(1).jpg")))
        assertEquals("IMG(3).jpg", freeDisplayName("IMG(2).jpg", setOf("IMG(2).jpg")))
        assertEquals("IMG(2).jpg", freeDisplayName("IMG (1).jpg", setOf("IMG (1).jpg")))
        assertEquals("README(1)", freeDisplayName("README", setOf("README")))
        assertEquals(".nomedia(1)", freeDisplayName(".nomedia", setOf(".nomedia")))
    }

    @Test fun copyNumbersClashesAcrossTheSelectionAndKeepsEveryItem() {
        val a = record(1, "IMG.jpg", "DCIM/Camera/")
        val b = record(2, "IMG.jpg", "Pictures/Other/")
        val c = record(3, "IMG.jpg", "Pictures/Trip/")
        val plan = planAlbumTransfer(AlbumTransferMode.COPY, trip, listOf(a, b, c), setOf("IMG.jpg"))
        assertEquals(listOf("IMG(1).jpg", "IMG(2).jpg", "IMG(3).jpg"), plan.steps.map { it.targetName })
        assertEquals(listOf(a, b, c), plan.steps.map { it.source })
        assertTrue(plan.alreadyThere.isEmpty())
    }

    @Test fun moveSkipsItemsAlreadyThereAndRefusesOtherVolumes() {
        val here = record(1, "A.jpg", "Pictures/Trip/")
        val camera = record(2, "B.jpg", "DCIM/Camera/", volume = "external")
        val sd = record(3, "C.jpg", "DCIM/Camera/", volume = "1234-abcd")
        val plan = planAlbumTransfer(AlbumTransferMode.MOVE, trip, listOf(here, camera, sd), emptySet())
        assertEquals(listOf(camera), plan.steps.map { it.source })
        assertEquals("B.jpg", plan.steps.single().targetName)
        assertEquals(listOf(here), plan.alreadyThere)
        assertEquals(listOf(sd), plan.otherVolume)
    }

    @Test fun newAlbumNamesAreCheckedBeforeAnyFolderExists() {
        val existing = listOf(AlbumBucketKey("external_primary", "Pictures/Trip/"))
        assertNull(albumNameProblem("  Summer  ", existing, "external_primary"))
        assertEquals("Pictures/Summer/", newAlbumRelativePath("  Summer  "))
        assertEquals(AlbumNameProblem.BLANK, albumNameProblem("   ", existing, "external_primary"))
        assertEquals(AlbumNameProblem.BAD_CHARACTER, albumNameProblem("a/b", existing, "external_primary"))
        assertEquals(AlbumNameProblem.BAD_CHARACTER, albumNameProblem("a\u0000b", existing, "external_primary"))
        assertEquals(AlbumNameProblem.DOT_NAME, albumNameProblem(".hidden", existing, "external_primary"))
        assertEquals(AlbumNameProblem.TOO_LONG, albumNameProblem("x".repeat(101), existing, "external_primary"))
        assertEquals(AlbumNameProblem.EXISTS, albumNameProblem("trip", existing, "external"))
        assertNull(albumNameProblem("Trip", existing, "1234-abcd"))
    }

    @Test fun resultToastSaysWhatHappened() {
        assertEquals("Copied 3 items to Trip",
            albumTransferMessage(AlbumTransferMode.COPY, "Trip", AlbumTransferResult(3, 0, 0)))
        assertEquals("Moved 1 item to Trip · 2 couldn't be moved · 1 already in Trip",
            albumTransferMessage(AlbumTransferMode.MOVE, "Trip", AlbumTransferResult(1, 2, 1)))
        assertEquals("Nothing was moved · 2 already in Trip",
            albumTransferMessage(AlbumTransferMode.MOVE, "Trip", AlbumTransferResult(0, 0, 2)))
    }
}
