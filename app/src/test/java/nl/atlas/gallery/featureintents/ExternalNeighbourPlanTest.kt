package nl.atlas.gallery.featureintents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalNeighbourPlanTest {
    private fun row(id: Long, taken: Long, video: Boolean = false) =
        NeighbourRow(id, video, taken, "IMG_$id.jpg", "DCIM/Camera/", 1L, 0L)

    @Test fun folderIsNewestFirstWithIdBreakingTies() {
        val rows = listOf(row(1, 100), row(2, 300), row(3, 200), row(4, 300))
        val window = ExternalNeighbourPlan.window(rows, currentId = 3, currentIsVideo = false)!!
        assertEquals(listOf(4L, 2L, 3L, 1L), window.map { it.id })
    }

    @Test fun anItemAtlasCannotReadItselfGetsNoNeighbours() {
        // The caller's row is missing from ATLAS's own folder query: single item.
        assertNull(ExternalNeighbourPlan.window(listOf(row(1, 1), row(2, 2)), currentId = 9, currentIsVideo = false))
    }

    @Test fun aFolderOfOneIsNotANeighbourList() {
        assertNull(ExternalNeighbourPlan.window(listOf(row(1, 1)), currentId = 1, currentIsVideo = false))
    }

    @Test fun aPhotoAndAVideoWithTheSameIdAreDifferentItems() {
        val rows = listOf(row(7, 100), row(7, 200, video = true), row(8, 50))
        val window = ExternalNeighbourPlan.window(rows, currentId = 7, currentIsVideo = true)!!
        assertEquals(listOf(true, false, false), window.map { it.isVideo })
        assertEquals(0, window.indexOfFirst { it.id == 7L && it.isVideo })
    }

    @Test fun aLargeFolderIsCutToTheWindowAroundTheOpenedItem() {
        val rows = (1L..2_000L).map { row(it, it) }
        val window = ExternalNeighbourPlan.window(rows, currentId = 1_000, currentIsVideo = false, window = 3)!!
        assertEquals(listOf(1_003L, 1_002L, 1_001L, 1_000L, 999L, 998L, 997L), window.map { it.id })
        val newest = ExternalNeighbourPlan.window(rows, currentId = 2_000, currentIsVideo = false, window = 3)!!
        assertEquals(listOf(2_000L, 1_999L, 1_998L, 1_997L), newest.map { it.id })
    }

    @Test fun duplicateRowsFromAProviderAppearOnce() {
        val rows = listOf(row(1, 10), row(1, 10), row(2, 20))
        assertEquals(listOf(2L, 1L), ExternalNeighbourPlan.window(rows, 1, false)!!.map { it.id })
    }
}
