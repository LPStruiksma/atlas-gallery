package nl.atlas.gallery.featureaiedit.network

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake accepted POST: the remote ID arrives after the local cancellation journal write. */
class EditJobJournalTest {
    @Test fun acceptedPostCannotOverwriteLocalCancel() {
        val folder = createTempDir(prefix = "aie-cancel-")
        try {
            EditJobJournal.write(folder, JSONObject().apply {
                put("state", "submitting")
                put("instruction", "synthetic edit")
            })
            // Simulates EditJobRunner.cancel() racing the POST response.
            EditJobJournal.change(folder) { it.put("state", "cancelled") }
            val shouldDeleteRemote = recordAcceptedSubmission(folder, "a".repeat(32))
            val recorded = EditJobJournal.read(folder)!!
            assertTrue(shouldDeleteRemote)
            assertEquals("cancelled", recorded.getString("state"))
            assertEquals("a".repeat(32), recorded.getString("remoteId"))
            assertFalse(recorded.has("instruction"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun normalAcceptedPostTransitionsToPolling() {
        val folder = createTempDir(prefix = "aie-accepted-")
        try {
            EditJobJournal.write(folder, JSONObject().put("state", "submitting"))
            assertFalse(recordAcceptedSubmission(folder, "b".repeat(32)))
            assertEquals("polling", EditJobJournal.read(folder)!!.getString("state"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
