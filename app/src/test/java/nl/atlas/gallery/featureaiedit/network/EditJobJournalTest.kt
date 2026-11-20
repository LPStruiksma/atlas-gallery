package nl.atlas.gallery.featureaiedit.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM regression for the atomic accepted-POST transition. */
class EditJobJournalTest {
    @Test fun acceptedPostCannotOverwriteLocalCancel() {
        // Models a local cancel committed while the POST response is in flight.
        assertEquals("cancelled", acceptedSubmissionState("cancelled"))
    }

    @Test fun acceptedPostNormallyTransitionsToPolling() {
        assertEquals("polling", acceptedSubmissionState("submitting"))
    }
}
