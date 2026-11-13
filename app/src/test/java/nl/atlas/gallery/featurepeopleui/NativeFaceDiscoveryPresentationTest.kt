package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFaceDiscoveryPresentationTest {
    private fun observed(
        phase: String,
        installed: Boolean = true,
        canStart: Boolean = true
    ) = NativeFaceScanDisplayEvidence(
        phaseName = phase, observedPermissionEpoch = "fictional-grant-7",
        totalVisibleMedia = 2500, processed = 1000, unknownObservations = 17,
        nativeDetectorInstalled = installed, realStartActionAvailable = canStart
    )

    @Test fun onlyRealWiredDetectorMayAdvertiseDiscovery() {
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, presentNativeFaceScan(
            observed("NEVER_STARTED", installed = false), "fictional-grant-7").phase)
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, presentNativeFaceScan(
            observed("NEVER_STARTED", canStart = false), "fictional-grant-7").phase)
        assertEquals(FaceDiscoveryPhase.READY, presentNativeFaceScan(
            observed("NEVER_STARTED"), "fictional-grant-7").phase)
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, presentNativeFaceScan(
            observed("NOT_CONFIGURED"), "fictional-grant-7").phase)
    }

    @Test fun staleOrUncommittedPermissionEpochDiscardsCountersAndActions() {
        val old = observed("RUNNING")
        listOf(null, "", "fictional-grant-8").forEach { newEpoch ->
            val state = presentNativeFaceScan(old, newEpoch)
            assertEquals(FaceDiscoveryPhase.UNAVAILABLE, state.phase)
            assertEquals(0, state.processed)
            assertEquals(0, state.unknownObservations)
        }
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE,
            presentNativeFaceScan(null, "fictional-grant-7").phase)
    }

    @Test fun everyKnownPPhaseMapsToAnHonestUiStatus() {
        val mapping = mapOf(
            "NOT_CONFIGURED" to FaceDiscoveryPhase.UNAVAILABLE,
            "NEVER_STARTED" to FaceDiscoveryPhase.READY,
            "QUEUED" to FaceDiscoveryPhase.QUEUED,
            "RUNNING" to FaceDiscoveryPhase.SCANNING,
            "PAUSED" to FaceDiscoveryPhase.PAUSED,
            "PERMISSION_REQUIRED" to FaceDiscoveryPhase.PERMISSION_REQUIRED,
            "STORAGE_LOW" to FaceDiscoveryPhase.STORAGE_LOW,
            "BATTERY_LOW" to FaceDiscoveryPhase.BATTERY_LOW,
            "REVIEW_AVAILABLE" to FaceDiscoveryPhase.REVIEW_AVAILABLE,
            "COMPLETE" to FaceDiscoveryPhase.FINISHED,
            "ERROR" to FaceDiscoveryPhase.FAILED
        )
        mapping.forEach { (phase, target) ->
            assertEquals(phase, target, presentNativeFaceScan(
                observed(phase), "fictional-grant-7").phase)
        }
    }

    @Test fun unknownNewBackendPhaseNeverShowsOldObservations() {
        val state = presentNativeFaceScan(observed("RECOGNIZE_ALL_PEOPLE"),
            "fictional-grant-7")
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, state.phase)
        assertEquals(0, state.unknownObservations)
    }

    @Test fun installedProgressIsCorrectlyBoundedAndEpochScoped() {
        val state = presentNativeFaceScan(observed("RUNNING"), "fictional-grant-7")
        assertEquals(1000, state.processed)
        assertEquals(2500, state.eligible)
        assertEquals(17, state.unknownObservations)
        assertEquals(0.4f, state.progress ?: 0f, 0.001f)
        assertEquals("fictional-grant-7", state.observedPermissionEpoch)
        assertTrue(state.isBusy)
    }

    @Test fun invalidNumericsRejectedAtBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            observed("RUNNING").copy(processed = 2501)
        }
        assertThrows(IllegalArgumentException::class.java) {
            observed("RUNNING").copy(unknownObservations = -1)
        }
    }
}
