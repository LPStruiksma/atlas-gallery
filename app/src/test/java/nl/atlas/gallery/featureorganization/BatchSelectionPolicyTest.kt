package nl.atlas.gallery.featureorganization

import org.junit.Assert.*
import org.junit.Test

class BatchSelectionPolicyTest {
    private val all = BatchSelectionAction.entries.toSet()

    @Test fun staleOrBusySourceNeverAdvertisesMutation() {
        for (current in listOf(false, true)) for (busy in listOf(false, true)) {
            if (current && !busy) continue
            val result = planBatchSelection(BatchSelectionUiState(
                selectedCount = 2, eligibleCount = 3,
                sourceCurrent = current, isOperating = busy), all)
            assertTrue(result.actions.isEmpty())
            assertFalse(result.canSelectAll)
        }
    }

    @Test fun invalidOrEmptySelectionCanNeverOperate() {
        for (selected in listOf(-1, 0, 11)) {
            val result = planBatchSelection(BatchSelectionUiState(
                selectedCount = selected, eligibleCount = 10, sourceCurrent = true), all)
            assertTrue(result.actions.isEmpty())
            if (selected == 0) assertTrue(result.canSelectAll)
            else assertFalse(result.canSelectAll)
        }
    }

    @Test fun negativeEligibleCountCannotExposeAnyAction() {
        val result = planBatchSelection(BatchSelectionUiState(
            0, -1, sourceCurrent = true), all)
        assertEquals("Selection unavailable", result.caption)
        assertTrue(result.actions.isEmpty())
        assertFalse(result.canSelectAll)
    }

    @Test fun onlyBoundRealLibraryCallbacksAreExposedInPredictableOrder() {
        val state = BatchSelectionUiState(2, 20, sourceCurrent = true)
        val result = planBatchSelection(state, setOf(
            BatchSelectionAction.TRASH, BatchSelectionAction.SHARE,
            BatchSelectionAction.RESTORE, BatchSelectionAction.MOVE))
        assertEquals("2 selected", result.caption)
        assertTrue(result.canSelectAll)
        assertEquals(listOf(BatchSelectionAction.SHARE,
            BatchSelectionAction.MOVE, BatchSelectionAction.TRASH), result.actions)
    }

    @Test fun trashModeNeverOffersPermanentRemovalOrOrdinaryAlbumMutation() {
        val result = planBatchSelection(BatchSelectionUiState(
            1, 2, sourceCurrent = true, mode = BatchSelectionMode.TRASH), all)
        assertEquals(listOf(BatchSelectionAction.RESTORE), result.actions)
    }

    @Test fun staleAndBusySelectionsPresentOnlyDisabledActions() {
        for (state in listOf(
            BatchSelectionUiState(2, 4, sourceCurrent = false),
            BatchSelectionUiState(2, 4, sourceCurrent = true, isOperating = true)
        )) {
            val plan = planBatchSelection(state,
                setOf(BatchSelectionAction.SHARE, BatchSelectionAction.TRASH))
            assertTrue(plan.actions.isEmpty())
            assertEquals(listOf(BatchSelectionAction.SHARE, BatchSelectionAction.TRASH),
                plan.presentedActions)
            assertEquals(setOf(BatchSelectionAction.TRASH), plan.consentRequired)
            assertFalse(plan.canSelectAll)
        }
    }

    @Test fun largeBatchDoesNotEnableShareOrTrashBeyondHostLimits() {
        val overTrash = planBatchSelection(BatchSelectionUiState(
            MAX_BATCH_TRASH_ITEMS + 1, MAX_BATCH_SHARE_ITEMS + 10,
            sourceCurrent = true), all)
        assertFalse(BatchSelectionAction.TRASH in overTrash.actions)
        assertTrue(BatchSelectionAction.TRASH in overTrash.presentedActions)
        assertTrue(BatchSelectionAction.SHARE in overTrash.actions)

        val overShare = planBatchSelection(BatchSelectionUiState(
            MAX_BATCH_SHARE_ITEMS + 1, MAX_BATCH_SHARE_ITEMS + 10,
            sourceCurrent = true), all)
        assertFalse(BatchSelectionAction.SHARE in overShare.actions)
        assertFalse(BatchSelectionAction.TRASH in overShare.actions)
        assertTrue(BatchSelectionAction.MOVE in overShare.actions)
    }

    @Test fun trashOnlyHasConsentWarningInLibraryMode() {
        val library = planBatchSelection(BatchSelectionUiState(
            1, 2, sourceCurrent = true), all)
        assertEquals(setOf(BatchSelectionAction.TRASH), library.consentRequired)
        val trash = planBatchSelection(BatchSelectionUiState(
            1, 2, sourceCurrent = true, mode = BatchSelectionMode.TRASH), all)
        assertTrue(trash.consentRequired.isEmpty())
        assertEquals(listOf(BatchSelectionAction.RESTORE), trash.presentedActions)
    }

    @Test fun selectAllUnavailableWhenAllAlreadySelected() {
        val result = planBatchSelection(BatchSelectionUiState(
            10, 10, sourceCurrent = true), setOf(BatchSelectionAction.SHARE))
        assertFalse(result.canSelectAll)
        assertEquals(listOf(BatchSelectionAction.SHARE), result.actions)
    }
}
