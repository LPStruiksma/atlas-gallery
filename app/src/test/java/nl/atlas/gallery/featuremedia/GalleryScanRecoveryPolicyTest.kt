package nl.atlas.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exact source-level permission/ticket fakes; no Android, media or wall-clock sleeps. */
class GalleryScanRecoveryPolicyTest {
    private val images = GalleryMediaGrant(true, false, false)
    private val selected = GalleryMediaGrant(false, false, true)
    private val none = GalleryMediaGrant(false, false, false)
    private val mismatch = GalleryProviderSnapshotChanged()

    @Test fun onlyPreciselyTypedSnapshotFailureMayRetry() {
        assertTrue(shouldRetryFailedGalleryScan(mismatch, true, false, images, images, true))
        for (failure in listOf(
            GalleryProviderVersionUnavailable(), SecurityException("secret"),
            IllegalStateException("Media provider changed during catalogue scan"),
            IllegalArgumentException("bad row"), java.io.IOException("provider busy"),
            RuntimeException("private path"))) {
            assertFalse(shouldRetryFailedGalleryScan(
                failure, true, false, images, images, true))
        }
    }

    @Test fun preDelayRequiresSameLiveGrantLatestOwnershipAndAvailableBudget() {
        for ((latest, revoked, end, budget) in listOf(
            listOf(false, false, images, true),
            listOf(true, true, images, true),
            listOf(true, false, selected, true),
            listOf(true, false, none, true),
            listOf(true, false, images, false)
        )) {
            assertFalse(shouldRetryFailedGalleryScan(
                mismatch, latest as Boolean, revoked as Boolean,
                images, end as GalleryMediaGrant, budget as Boolean))
        }
        assertFalse(shouldRetryFailedGalleryScan(
            mismatch, true, false, images, null, true))
        assertTrue(shouldRetryFailedGalleryScan(
            mismatch, true, false, selected, selected, true))
    }

    @Test fun deterministicFailOnceWaitThenActualAuthorizedPublication() = runBlocking {
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        assertEquals(first, queue.begin())
        assertTrue(shouldRetryFailedGalleryScan(
            mismatch, queue.mayPublish(first), false, images, images, true))
        assertFalse(queue.finish(first))
        var waited = 0L
        var latestGrant = images
        val ready = awaitGallerySnapshotRetrySettle(wait = { waited = it }) {
            queue.mayRetryAfterFinished(first) && latestGrant == images
        }
        assertTrue(ready)
        assertEquals(2_500L, waited)
        val second = queue.request()
        assertEquals(second, queue.begin())
        assertFalse(queue.mayPublish(first))
        assertTrue(queue.mayPublish(second))
        // One automatic retry marker is common to catch AND completed paths.
        val budget = GalleryAutomaticRetryBudget()
        budget.recordFollowup(second)
        assertFalse(shouldRetryFailedGalleryScan(
            mismatch, queue.mayPublish(second), false, images, images,
            oneRetryStillAvailable = budget.availableFor(second)))
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, images, images, 2L, 3L,
            oneRetryStillAvailable = budget.availableFor(second)))
        assertTrue(mayPublishCompletedGalleryScan(
            true, false, images, images, 3L, 3L))
        assertFalse(queue.finish(second))
    }

    @Test fun revocationWhileWaitingCannotStartAnyOldScopeScan() = runBlocking {
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        queue.begin()
        assertFalse(queue.finish(first))
        val denied = queue.pauseForAccessLoss()
        val ready = awaitGallerySnapshotRetrySettle(wait = {}) {
            queue.mayRetryAfterFinished(first)
        }
        assertFalse(ready)
        assertFalse(queue.mayRetryAfterFinished(denied))
        assertNull(queue.begin())
    }

    @Test fun scopeSupersessionWhileWaitingAbortsOldRetry() = runBlocking {
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        queue.begin()
        assertFalse(queue.finish(first))
        val newer = queue.request()
        val ready = awaitGallerySnapshotRetrySettle(wait = {}) {
            queue.mayRetryAfterFinished(first)
        }
        assertFalse(ready)
        assertEquals(newer, queue.begin())
        assertFalse(queue.mayPublish(first))
        assertFalse(queue.finish(newer))
    }

    @Test fun unknownOrChangedPostDelayGrantFailsClosed() = runBlocking {
        val queue = GalleryRefreshQueue()
        val ticket = queue.request()
        queue.begin()
        queue.finish(ticket)
        var actual: GalleryMediaGrant? = images
        assertFalse(awaitGallerySnapshotRetrySettle(wait = { actual = selected }) {
            queue.mayRetryAfterFinished(ticket) && actual == images
        })
        assertFalse(awaitGallerySnapshotRetrySettle(wait = { actual = null }) {
            queue.mayRetryAfterFinished(ticket) && actual == images
        })
    }

    @Test fun cancellationInterruptsPendingDelayWithoutRetry() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val worker = launch {
            awaitGallerySnapshotRetrySettle(
                wait = { waiting.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            ) { fail("Eligibility must never run after cancellation"); true }
        }
        waiting.await()
        worker.cancel()
        worker.join()
        assertTrue(worker.isCancelled)
    }

    @Test fun secondFailureAndCompletedPathShareOneAutomaticBudget() {
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        queue.begin()
        queue.finish(first)
        val automatic = queue.request()
        assertEquals(automatic, queue.begin())
        val budget = GalleryAutomaticRetryBudget()
        budget.recordFollowup(automatic)
        assertFalse(shouldRetryFailedGalleryScan(
            mismatch, queue.mayPublish(automatic), false, images, images,
            budget.availableFor(automatic)))
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, images, images, 7L, 8L, budget.availableFor(automatic)))
        assertFalse(queue.finish(automatic))
        assertNull(queue.begin()) // no unbounded third scan
    }

    @Test fun completedScanRetryUsesSameBudgetBeforeFailurePath() {
        val queue = GalleryRefreshQueue()
        val original = queue.request()
        queue.begin()
        assertTrue(shouldRetryCompletedGalleryScan(
            true, false, images, images, 8L, 9L, true))
        val followup = queue.request()
        assertFalse(queue.mayPublish(original))
        assertTrue(queue.finish(original))
        assertEquals(followup, queue.begin())
        val budget = GalleryAutomaticRetryBudget()
        budget.recordFollowup(followup)
        assertFalse(shouldRetryFailedGalleryScan(
            mismatch, queue.mayPublish(followup), false, images, images,
            oneRetryStillAvailable = budget.availableFor(followup)))
        assertFalse(queue.finish(followup))
    }

    @Test fun sharedBudgetIsBoundToActualAutomaticFollowupNotAnyManualRequest() {
        val budget = GalleryAutomaticRetryBudget()
        assertTrue(budget.availableFor(1))
        budget.recordFollowup(2)
        assertFalse(budget.availableFor(2))
        assertTrue(budget.availableFor(3)) // explicit fresh request
        budget.recordFollowup(4)
        assertFalse(budget.availableFor(4))
        assertTrue(budget.availableFor(5))
        assertThrows(IllegalArgumentException::class.java) {
            budget.recordFollowup(0L)
        }
    }

    @Test fun retryEventHasFixedSchemaAndNoSensitiveInputs() {
        assertEquals("event=retry_settling ticket=3 delay_ms=2500",
            galleryScanRetryLogLine("settling", 3))
        assertEquals("event=retry_started ticket=3 followup=4 delay_ms=2500",
            galleryScanRetryLogLine("started", 3, 4))
        assertThrows(IllegalArgumentException::class.java) {
            galleryScanRetryLogLine("content://media/private", 1)
        }
    }
}
