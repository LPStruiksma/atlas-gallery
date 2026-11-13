package nl.atlas.gallery

import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceScanPhase
import nl.atlas.gallery.featureface.FaceVisibleMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure phase and continuation rules of the #433 discovery host. */
class PeopleDiscoveryHostPhaseTest {
    @Test fun pauseWinsOverPendingWorkAndHolds() {
        assertEquals(FaceScanPhase.PAUSED,
            startedScanPhase(true, pending = 5, running = 1, awaitingReview = 3,
                hold = FaceScanPhase.BATTERY_LOW))
    }

    @Test fun lostPermissionShowsEvenWhenTheQueueDrained() {
        assertEquals(FaceScanPhase.PERMISSION_REQUIRED,
            startedScanPhase(false, 0, 0, 4, FaceScanPhase.PERMISSION_REQUIRED))
    }

    @Test fun pendingWorkShowsTheLiveHoldOrRunning() {
        assertEquals(FaceScanPhase.RUNNING, startedScanPhase(false, 3, 0, 0, null))
        assertEquals(FaceScanPhase.RUNNING, startedScanPhase(false, 0, 1, 0, null))
        assertEquals(FaceScanPhase.BATTERY_LOW,
            startedScanPhase(false, 3, 0, 0, FaceScanPhase.BATTERY_LOW))
        assertEquals(FaceScanPhase.STORAGE_LOW,
            startedScanPhase(false, 3, 0, 0, FaceScanPhase.STORAGE_LOW))
    }

    @Test fun drainedQueueOffersReviewUntilEveryDetectionIsDecided() {
        assertEquals(FaceScanPhase.REVIEW_AVAILABLE, startedScanPhase(false, 0, 0, 2, null))
        assertEquals(FaceScanPhase.COMPLETE, startedScanPhase(false, 0, 0, 0, null))
        // A charger-only hold must not hide a finished scan.
        assertEquals(FaceScanPhase.COMPLETE,
            startedScanPhase(false, 0, 0, 0, FaceScanPhase.BATTERY_LOW))
    }

    @Test fun budgetStopsContinueHoldsRetryLaterDrainOrStaleStops() {
        val now = FaceBatchStop.entries.filter { nextBatchAfter(it) == NextBatch.NOW }.toSet()
        val later = FaceBatchStop.entries.filter { nextBatchAfter(it) == NextBatch.LATER }.toSet()
        assertEquals(setOf(FaceBatchStop.PHOTO_LIMIT, FaceBatchStop.TIME_LIMIT,
            FaceBatchStop.PHOTO_FAILED), now)
        // A hold must never end the run silently while People says "scanning".
        assertEquals(setOf(FaceBatchStop.HELD, FaceBatchStop.DETECTOR_PENDING,
            FaceBatchStop.DETECTOR_STALLED), later)
        assertEquals(NextBatch.NONE, nextBatchAfter(FaceBatchStop.NO_PENDING))
        assertEquals(NextBatch.NONE, nextBatchAfter(FaceBatchStop.PERMISSION_STALE))
        assertTrue(FaceBatchStop.entries.size == 8)
    }

    /** #508: newest capture first, then newest MediaStore id; stable otherwise. */
    @Test fun catalogueOrderIsNewestFirst() {
        fun row(id: Long, taken: Long) = FaceVisibleMedia("content://media/external/images/media/$id",
            "external", false, id, "p", 1, 1, 1, 0, 1, 1, 0, takenAtMillis = taken)
        val rows = listOf(row(1, 100), row(2, 300), row(3, 200), row(4, 300), row(5, 0))
        assertEquals(listOf(4L, 2L, 3L, 1L, 5L), newestFirst(rows).map { it.id })
    }
}
