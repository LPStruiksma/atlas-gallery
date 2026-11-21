package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** #783: synthetic catalogue republication between selection and Move/Copy target tap. */
class BatchTransferGateTest {
    private fun rec(id: Long) = MediaRecord(id, "content://atlas.synthetic/$id", "p$id.jpg",
        "Fiction/A/", 10L, id, false)

    private val before = (1L..3L).map(::rec)
    private val selection = GalleryBatchSelection.start(before, 1L, before[0])!!
        .toggled(before[1])!!

    private fun gate(s: GalleryBatchSelection?, operating: Boolean = false,
                     access: Boolean = true) =
        gateBatchTransfer(s, operating, access, sdkOk = true)

    @Test fun noReloadProceeds() =
        assertEquals(BatchTransferGate.PROCEED, gate(selection))

    @Test fun reloadBeforeOpenKeepingItemsStillProceeds() {
        val reloaded = before.map { rec(it.id) } // new list, same URIs
        val rebased = selection.rebasedOnto(reloaded, 2L)
        assertEquals(2, rebased.count)
        assertEquals(BatchTransferGate.PROCEED, gate(rebased))
    }

    @Test fun reloadWhilePickerOpenRemovingEveryItemIsVisibleNotSilent() {
        val rebased = selection.rebasedOnto(listOf(rec(9L)), 2L)
        assertEquals(0, rebased.count)
        assertEquals(BatchTransferGate.SELECTION_CHANGED, gate(rebased))
    }

    @Test fun deletedRowKeepsOnlySurvivingItems() {
        val rebased = selection.rebasedOnto(listOf(before[0], rec(3L)), 2L)
        assertNotNull(rebased)
        assertEquals(1, rebased.count)
        assertEquals(BatchTransferGate.PROCEED, gate(rebased))
    }

    @Test fun clearedSelectionIsVisibleNotSilent() =
        assertEquals(BatchTransferGate.SELECTION_CHANGED, gate(null))

    @Test fun revokedAccessNeverTransfers() =
        assertEquals(BatchTransferGate.ACCESS_CHANGED, gate(selection, access = false))

    @Test fun busyTapIsIgnoredSoNoDuplicateFlowStarts() =
        assertEquals(BatchTransferGate.IGNORE, gate(selection, operating = true))

    @Test fun oldApiIsIgnored() =
        assertEquals(BatchTransferGate.IGNORE,
            gateBatchTransfer(selection, false, true, sdkOk = false))
}
