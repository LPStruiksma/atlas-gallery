package nl.atlas.gallery.featureface.work

import nl.atlas.gallery.featureface.FaceBatchBudget
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceScanBatchPolicy
import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanStep
import nl.atlas.gallery.featureface.FaceScanStepResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic policy tests; never read media, create real faces or enroll a grant. */
class PeopleScanWorkTest {
    private fun allowed() = FaceScanConditions(
        optedIn = true, photosPermissionGranted = true, permissionSnapshotCurrent = true,
        batteryNotLow = true, storageNotLow = true, charging = true,
        pausedByUser = false, backgroundMaintenance = true
    )

    @Test fun boundedByEightPhotos() {
        var attempts = 0
        val result = FaceScanBatchPolicy({ 0L }) {
            attempts++
            FaceScanStepResult(FaceScanStep.INDEXED)
        }.run(FaceBatchBudget(8, 2_000))
        assertEquals(8, attempts)
        assertEquals(8, result.photosIndexed)
        assertEquals(FaceBatchStop.PHOTO_LIMIT, result.stop)
    }

    @Test fun twoSecondBudgetStopsBetweenImages() {
        var elapsed = 0L
        val result = FaceScanBatchPolicy({ elapsed }) {
            elapsed += 2_000
            FaceScanStepResult(FaceScanStep.INDEXED)
        }.run(FaceBatchBudget(8, 2_000))
        assertEquals(1, result.stepsAttempted)
        assertEquals(FaceBatchStop.TIME_LIMIT, result.stop)
    }

    @Test fun deferredDoesNotSpendAnImageRetry() {
        val result = FaceScanBatchPolicy({ 0L }) {
            FaceScanStepResult(FaceScanStep.DEFERRED)
        }.run()
        assertEquals(FaceBatchStop.DETECTOR_PENDING, result.stop)
        assertEquals(0, result.photosIndexed)
        assertEquals(1, result.stepsAttempted)
    }

    @Test fun allStopsAreSingleStepAndNeverLoop() {
        for ((step, expected) in listOf(
            FaceScanStep.HELD to FaceBatchStop.HELD,
            FaceScanStep.RELEASED_STALE to FaceBatchStop.PERMISSION_STALE,
            FaceScanStep.MODEL_UNAVAILABLE to FaceBatchStop.DETECTOR_STALLED,
            FaceScanStep.NO_PENDING to FaceBatchStop.NO_PENDING
        )) {
            var calls = 0
            val result = FaceScanBatchPolicy({ 0L }) {
                calls++
                FaceScanStepResult(step)
            }.run()
            assertEquals(expected, result.stop)
            assertEquals(1, calls)
        }
    }

    @Test fun revocationAndRestartRequireNewLiveScope() {
        val conditions = allowed()
        assertTrue(conditions.canRun())
        assertFalse(conditions.copy(optedIn = false).canRun())
        assertFalse(conditions.copy(photosPermissionGranted = false).canRun())
        assertFalse(conditions.copy(permissionSnapshotCurrent = false).canRun())
        assertFalse(conditions.copy(pausedByUser = true).canRun())
        assertFalse(conditions.copy(charging = false).canRun())
    }
}
