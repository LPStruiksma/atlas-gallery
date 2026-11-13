package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic approval-state tests: no MediaStore, DB, SAF or private files. */
class PeopleCsvReviewStateTest {
    @Test fun choosingDocumentDoesNotAuthorizeAnyReplacement() {
        val initial = PeopleCsvReviewState()
        assertNull(initial.decide(true).second)
        val selected = initial.selected("content://fictional-provider/export.csv", PeopleCsvMode.IMPORT_ONCE)
        assertEquals(PeopleCsvMode.IMPORT_ONCE, selected.pending?.mode)
        assertEquals("content://fictional-provider/export.csv", selected.pending?.documentUri)
        assertNull(initial.pending)
    }

    @Test fun cancelAndDismissCannotApproveAndClearAllTransitoryData() {
        val pending = PeopleCsvReviewState().selected(
            "content://fictional-provider/export.csv", PeopleCsvMode.LINK_LIVE)
        val (cleared, approval) = pending.decide(false)
        assertNull(approval)
        assertNull(cleared.pending)
        assertNull(cleared.decide(true).second)
    }

    @Test fun approvalPreservesExactModeAndUriAndMustConsumeState() {
        for (mode in PeopleCsvMode.entries) {
            val proposal = PeopleCsvReviewState().selected("content://provider/document/42", mode)
            val (cleared, approval) = proposal.decide(true)
            assertEquals(mode, approval?.mode)
            assertEquals("content://provider/document/42", approval?.documentUri)
            assertNull(cleared.pending)
            assertNull(cleared.decide(true).second)
        }
    }

    @Test fun noPathsFileUrisOrEmptyProviderAreAllowedAsSelection() {
        for (uri in listOf("", "/storage/emulated/0/photo.csv",
            "file:///tmp/unsafe.csv", "content://")) {
            assertThrows(IllegalArgumentException::class.java) {
                PeopleCsvReviewState().selected(uri, PeopleCsvMode.IMPORT_ONCE)
            }
        }
    }
}
