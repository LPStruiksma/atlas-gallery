package nl.atlas.gallery

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import nl.atlas.gallery.featurecuration.hashCurationBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #42: a throwing Photo DNA planning step ends as [PhotoDnaOutcome.Failed]
 * (shown as Retry by the host), never as an exception that reaches the main
 * looper. Synthetic records only.
 */
class PhotoDnaPlanningFailureTest {
    private fun photo(id: Long, size: Long = 64L) = MediaRecord(
        id = id,
        uri = "content://media/external/images/media/$id",
        name = "synthetic-$id.jpg",
        relativePath = "DCIM/Test/",
        sizeBytes = size,
        takenAtMillis = id * 1_000L,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L
    )

    private fun hash(record: MediaRecord) = hashCurationBytes(record.sizeBytes,
        openAuthorized = { ByteArrayInputStream(ByteArray(record.sizeBytes.toInt()) { 7 }) },
        stillAuthorized = { true })

    @Test fun aPlanInputThatTripsARequireBecomesAFailedOutcome() {
        val source = listOf(photo(1), photo(2))
        // A blank scope epoch trips CurationReviewPlan.build's first require.
        val bad = PhotoDnaTokens(" ")
        // Before #42 this exception reached the LaunchedEffect and quit the app.
        assertThrows(IllegalArgumentException::class.java) {
            planPhotoDna(source, bad, ::hash)
        }
        val outcome = photoDnaOutcome { planPhotoDna(source, bad, ::hash) }
        assertTrue(outcome is PhotoDnaOutcome.Failed)
        assertTrue((outcome as PhotoDnaOutcome.Failed).error is IllegalArgumentException)
    }

    @Test fun aThrowingHashAndOutOfMemoryBecomeFailedOutcomes() {
        val source = listOf(photo(1), photo(2))
        val tokens = PhotoDnaTokens("synthetic-epoch")
        val thrown = photoDnaOutcome<PhotoDnaResult> {
            planPhotoDna(source, tokens, { error("synthetic provider failure") })
        }
        assertTrue((thrown as PhotoDnaOutcome.Failed).error is IllegalStateException)
        val oom = OutOfMemoryError("synthetic")
        val outOfMemory = photoDnaOutcome<Unit> { throw oom }
        assertSame(oom, (outOfMemory as PhotoDnaOutcome.Failed).error)
    }

    @Test fun cancellationIsNeverSwallowed() {
        val cancel = CancellationException("closed")
        val thrown = assertThrows(CancellationException::class.java) {
            photoDnaOutcome<Unit> { throw cancel }
        }
        assertSame(cancel, thrown)
        val done = photoDnaOutcome { 42 }
        assertEquals(42, (done as PhotoDnaOutcome.Done).value)
    }

    @Test fun handledFailureIsMarkedInTheLastCrashReport() {
        val report = formatCrashReport(1_700_000_000_000L, "0.3.0", "background",
            IllegalArgumentException("content://private/uri"), handled = true)
        assertTrue(report.contains("Outcome: handled, app kept running"))
        assertFalse(report.contains("content://"))
        val fatal = formatCrashReport(1_700_000_000_000L, "0.3.0", "background",
            IllegalArgumentException("x"))
        assertFalse(fatal.contains("Outcome:"))
    }

    /**
     * #42 large fixture: a 250k-photo library in which every file has a
     * same-size twin. One pass hashes at most the budget's 2k files and
     * defers the rest, so planning memory does not grow with the library.
     */
    @Test fun aQuarterMillionPhotoLibraryPlansWithinTheBudget() {
        val source = (1L..250_000L).map { photo(it, size = 64L + it / 2) }
        var hashed = 0
        val result = planPhotoDna(source, PhotoDnaTokens("synthetic-epoch"), { record ->
            hashed++
            hash(record)
        })
        val budget = PhotoDnaBudget()
        assertTrue("hashed $hashed", hashed <= budget.maxFiles)
        assertTrue(result.checkedFiles <= budget.maxFiles)
        assertTrue(result.deferredFiles > 0)
        assertTrue(result.plan.exactGroups.isNotEmpty())
    }
}
