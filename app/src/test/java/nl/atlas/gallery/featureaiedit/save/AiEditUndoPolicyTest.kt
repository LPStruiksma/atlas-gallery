package nl.atlas.gallery.featureaiedit.save

import nl.atlas.gallery.featureaiedit.save.AiEditUndoPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class AiEditUndoPolicyTest {
    private val saved = "content://media/external_primary/images/media/42"
    private val original = "content://media/external_primary/images/media/7"
    private val ok = AiEditUndoPolicy.Facts(
        savedUri = saved, originalUri = original, canonicalUri = saved, rowExists = true,
        ownerPackage = "nl.atlas.gallery", appPackage = "nl.atlas.gallery", pending = false,
        relativePath = "DCIM/Camera/", expectedRelativePath = "DCIM/Camera/", lineageOriginalUri = original)

    @Test fun deletesOnlyTheProvenOwnPublishedCopy() {
        assertEquals(Verdict.DELETE, AiEditUndoPolicy.decide(ok))
    }

    @Test fun neverTheOriginal() {
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(savedUri = original, canonicalUri = original)))
    }

    @Test fun refusesAnotherAppsRowOrPendingRowOrOtherFolder() {
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(ownerPackage = "com.other")))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(ownerPackage = null)))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(pending = true)))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(relativePath = "Pictures/")))
    }

    @Test fun refusesWithoutMatchingLineageOrCanonicalUri() {
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(lineageOriginalUri = null)))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(lineageOriginalUri = "content://media/external_primary/images/media/8")))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(canonicalUri = null)))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(canonicalUri = "content://media/external/images/media/42")))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(savedUri = "", canonicalUri = "")))
    }

    @Test fun aRowAlreadyGoneIsReportedAsGoneNotDeleted() {
        assertEquals(Verdict.ALREADY_GONE, AiEditUndoPolicy.decide(ok.copy(rowExists = false, ownerPackage = null)))
    }

    @Test fun originalFolderUnknownStillNeedsOwnershipAndLineage() {
        assertEquals(Verdict.DELETE, AiEditUndoPolicy.decide(ok.copy(expectedRelativePath = null)))
        assertEquals(Verdict.REFUSE, AiEditUndoPolicy.decide(ok.copy(expectedRelativePath = null, ownerPackage = "x")))
    }
}
