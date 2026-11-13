package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class GalleryBatchSelectionTest {
    private fun media(n: Int) = (0 until n).map {
        MediaRecord(it.toLong(), "content://media/external/images/media/$it", "IMG_$it.jpg",
            "DCIM/Camera/", 1L, it.toLong(), false)
    }

    @Test fun longPressStartsWithExactlyOneItemAndLastToggleEndsSelection() {
        val items = media(5)
        val started = GalleryBatchSelection.start(items, 3L, items[2])!!
        assertEquals(1, started.count)
        assertTrue(started.contains(items[2]))
        val two = started.toggled(items[4])!!
        assertEquals(2, two.count)
        assertNull(two.toggled(items[2])!!.toggled(items[4]))
    }

    @Test fun cannotStartOnEmptySourceOrUnpublishedEpoch() {
        val items = media(2)
        assertNull(GalleryBatchSelection.start(emptyList(), 1L, items[0]))
        assertNull(GalleryBatchSelection.start(items, -1L, items[0]))
    }

    @Test fun newListEpochOrRevokedAccessMakesTheBarFailClosed() {
        val items = media(4)
        val selection = GalleryBatchSelection.start(items, 7L, items[1])!!.toggled(items[3])!!
        val live = planBatchSelection(selection.uiState(items, 7L, true, false),
            BatchSelectionAction.entries.toSet())
        assertEquals("2 selected", live.caption)
        assertTrue(BatchSelectionAction.TRASH in live.actions)
        // Equal content, different publication reference: must select again.
        for (state in listOf(
            selection.uiState(items.toList(), 7L, true, false),
            selection.uiState(items, 8L, true, false),
            selection.uiState(items, 7L, false, false))) {
            val plan = planBatchSelection(state, BatchSelectionAction.entries.toSet())
            assertTrue(plan.actions.isEmpty())
            assertEquals("Library changed · select again", plan.caption)
        }
    }

    @Test fun resolveReturnsGalleryOrderAndFailsClosedOnAnyMissingItem() {
        val items = media(2_000)
        val selection = GalleryBatchSelection.start(items, 1L, items[1500])!!
            .toggled(items[3])!!.toggled(items[999])!!
        val resolved = resolveBatchSelection(selection, items)!!
        assertEquals(listOf(3L, 999L, 1500L), resolved.map { it.id })
        assertNull(resolveBatchSelection(selection, items.toList()))
        val other = GalleryBatchSelection.start(items.take(10), 1L, items[3])!!
            .toggled(items[1500])!!
        assertNull(resolveBatchSelection(other, items))
    }

    @Test fun resolveHonoursCancellationCheckpoints() {
        val items = media(1_100)
        val selection = GalleryBatchSelection.start(items, 1L, items[0])!!
        var checks = 0
        resolveBatchSelection(selection, items) { checks++ }
        assertEquals(3, checks)
    }
}
