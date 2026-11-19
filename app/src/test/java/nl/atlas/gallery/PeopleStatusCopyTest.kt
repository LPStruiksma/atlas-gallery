package nl.atlas.gallery

import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #46: no visual status may imply work that the worker has not done. */
class PeopleStatusCopyTest {
    private fun state(phase: FaceDiscoveryPhase, processed: Int = 3, eligible: Int = 10) =
        FaceDiscoveryUiState(phase = phase, processed = processed, eligible = eligible,
            observedPermissionEpoch = "synthetic-grant")

    @Test fun chargerAndGenuinelyLowBatteryAreDifferentWorkerHolds() {
        val idle = FaceScanConditions(
            optedIn = true, photosPermissionGranted = true,
            permissionSnapshotCurrent = true, batteryNotLow = true,
            storageNotLow = true, charging = false,
            pausedByUser = false, backgroundMaintenance = true)
        assertEquals(FaceScanPhase.CHARGER_REQUIRED, idle.holdReason())
        assertEquals(FaceScanPhase.BATTERY_LOW, idle.copy(batteryNotLow = false).holdReason())
        assertEquals(null, idle.copy(charging = true).holdReason())
        assertEquals(null, idle.copy(backgroundMaintenance = false).holdReason())
        assertEquals(FaceScanPhase.PERMISSION_REQUIRED,
            idle.copy(photosPermissionGranted = false).holdReason())
        assertEquals(FaceScanPhase.PAUSED, idle.copy(pausedByUser = true).holdReason())
    }

    @Test fun allSixPeopleStatesHaveHonestVisibleCopy() {
        assertEquals("Waiting for charger · 3 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.WAITING_FOR_CHARGER)))
        assertEquals("Starting · 0 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.QUEUED, 0)))
        assertEquals("Finding people · 3 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.SCANNING)))
        assertEquals("Grouping people · 3 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.GROUPING)))
        assertEquals("Paused · 3 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.PAUSED)))
        assertEquals("Finished · 10 of 10",
            peopleStatusLine(state(FaceDiscoveryPhase.FINISHED, 10)))
        assertNull(peopleStatusLine(state(FaceDiscoveryPhase.READY, 0)))
        assertNull(peopleStatusLine(state(FaceDiscoveryPhase.UNAVAILABLE, 0)))
        assertEquals("Permission required",
            peopleStatusLine(state(FaceDiscoveryPhase.PERMISSION_REQUIRED)))
    }

    @Test fun restoredChoiceDoesNotBecomeUnavailableOrLeakCounts() {
        assertEquals("Restoring…",
            faceScanSettingsValue(null, loading = false, savedOptIn = true))
        assertEquals("Restoring…",
            faceScanSettingsValue(FaceDiscoveryPhase.UNAVAILABLE,
                loading = true, savedOptIn = true))
        assertEquals("Permission required",
            faceScanSettingsValue(null, loading = false, savedOptIn = true,
                photoAccess = false))
        assertEquals("Unavailable",
            faceScanSettingsValue(null, loading = false, savedOptIn = false))
        assertEquals("Waiting for charger",
            faceScanSettingsValue(FaceDiscoveryPhase.WAITING_FOR_CHARGER, false, true))
        assertEquals("Grouping people",
            faceScanSettingsValue(FaceDiscoveryPhase.GROUPING, false, true))
        assertEquals("Starting",
            faceScanSettingsValue(FaceDiscoveryPhase.QUEUED, false, true))
        assertEquals("Finding people · Pause",
            faceScanSettingsValue(FaceDiscoveryPhase.SCANNING, false, true))
        assertEquals("Paused · Resume",
            faceScanSettingsValue(FaceDiscoveryPhase.PAUSED, false, true))
        assertEquals("Finished",
            faceScanSettingsValue(FaceDiscoveryPhase.FINISHED, false, true))
    }

    @Test fun visualProgressRequiresItsOwnProgressAndRespectsHolds() {
        val start = VisualIndexStatus(enabled = true, mayEnable = true, done = 0, total = 10)
        val partial = start.copy(done = 4)
        assertEquals("Starting",
            visualIndexStatusText(true, start, FaceDiscoveryPhase.QUEUED, false))
        assertEquals("Starting",
            visualIndexStatusText(true, start, FaceDiscoveryPhase.SCANNING, false))
        assertEquals("Indexing photos",
            visualIndexStatusText(true, partial, FaceDiscoveryPhase.FINISHED, false))
        assertEquals("Finished",
            visualIndexStatusText(true, start.copy(done = 10),
                FaceDiscoveryPhase.FINISHED, false))
        assertEquals("Waiting for scan",
            visualIndexStatusText(true, partial, FaceDiscoveryPhase.GROUPING, false))
        assertEquals("Waiting for charger",
            visualIndexStatusText(true, partial, FaceDiscoveryPhase.WAITING_FOR_CHARGER, false))
        assertEquals("Paused",
            visualIndexStatusText(true, partial, FaceDiscoveryPhase.PAUSED, false))
        assertEquals("Permission required",
            visualIndexStatusText(true, partial, null, false, photoAccess = false))
        assertEquals("Restoring…", visualIndexStatusText(true, null, null, true))
        assertEquals("Off", visualIndexStatusText(false, start, FaceDiscoveryPhase.SCANNING, false))
        assertEquals("Needs attention",
            visualIndexStatusText(true, start.copy(failure = "test"),
                FaceDiscoveryPhase.SCANNING, false))
    }

    @Test fun groupingOnlyClaimsActivityWhenNotHeldOrPaused() {
        assertEquals(FaceScanPhase.GROUPING,
            startedScanPhase(false, 0, 0, 0, null, grouping = true))
        assertEquals(FaceScanPhase.PAUSED,
            startedScanPhase(true, 3, 0, 0, null, grouping = true))
        assertEquals(FaceScanPhase.CHARGER_REQUIRED,
            startedScanPhase(false, 3, 0, 0,
                FaceScanPhase.CHARGER_REQUIRED, grouping = true))
        assertEquals(FaceScanPhase.PERMISSION_REQUIRED,
            startedScanPhase(false, 3, 0, 0,
                FaceScanPhase.PERMISSION_REQUIRED, grouping = true))
    }
}
