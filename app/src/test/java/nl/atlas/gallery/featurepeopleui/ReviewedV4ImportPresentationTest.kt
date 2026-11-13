package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedV4ImportPresentationTest {
    @Test fun legacyExistingPeopleAlwaysWarnsAboutReplacement() {
        val copy = reviewedV4ImportCopy(ReviewedV4ImportMode.REPLACES, true)
        assertEquals("Review replacement", copy.actionLabel)
        assertTrue(copy.currentAssignmentsExplanation!!.contains("REPLACES"))
        assertTrue(copy.currentAssignmentsExplanation!!.contains("partial"))
        assertTrue(copy.linkedRefreshExplanation.contains("REPLACE"))
    }

    @Test fun additiveImportStillDisclosesDangerousLinkedRefresh() {
        val copy = reviewedV4ImportCopy(
            ReviewedV4ImportMode.ADDITIVE_CONFLICT_REJECTING, true
        )
        assertEquals("Review additive import", copy.actionLabel)
        assertTrue(copy.currentAssignmentsExplanation!!.contains("Conflicting"))
        assertTrue(copy.currentAssignmentsExplanation!!.contains("abort"))
        assertFalse(copy.currentAssignmentsExplanation!!.contains("REPLACES"))
        assertTrue(copy.linkedRefreshExplanation.contains("REPLACE"))
        assertTrue(copy.linkedRefreshExplanation.contains("verified backup"))
    }

    @Test fun emptyPeopleDoesNotClaimExistingAssignmentsWillBeLost() {
        for (mode in ReviewedV4ImportMode.entries) {
            val copy = reviewedV4ImportCopy(mode, false)
            assertEquals("Choose V4 export", copy.actionLabel)
            assertNull(copy.currentAssignmentsExplanation)
            assertTrue(copy.linkedRefreshExplanation.contains("REPLACE"))
        }
    }

    @Test fun everyModeWarnsAboutLinkedRefreshIndependently() {
        for (hasPeople in listOf(false, true)) {
            val copies = ReviewedV4ImportMode.entries.map {
                reviewedV4ImportCopy(it, hasPeople)
            }
            assertEquals(copies[0].linkedRefreshExplanation, copies[1].linkedRefreshExplanation)
            assertTrue(copies[0].linkedRefreshExplanation.contains("backup"))
        }
    }
}
