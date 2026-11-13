package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional boolean grants only; no emulator permissions or user media. */
class GalleryMediaGrantTest {
    private val none = GalleryMediaGrant(false, false, false)
    private val full = GalleryMediaGrant(true, true, true)
    private val selectedOnly = GalleryMediaGrant(false, false, true)
    private val imagesAndSelectedVideos = GalleryMediaGrant(true, false, true)

    @Test fun selectedPhotoScopeAlwaysRequiresNewInventoryEvenIfFlagsAreUnchanged() {
        assertTrue(selectedOnly.hasAny)
        assertTrue(selectedOnly.selectionScoped)
        assertTrue(requiresGalleryRescanOnResume(selectedOnly, selectedOnly))
        assertTrue(requiresGalleryRescanOnResume(imagesAndSelectedVideos, imagesAndSelectedVideos))
    }

    @Test fun completelyGrantedMediaDoesNotRequeryOnlyBecauseSelectedFlagExists() {
        assertTrue(full.hasAny)
        assertFalse(full.selectionScoped)
        assertFalse(requiresGalleryRescanOnResume(full, full))
        assertFalse(requiresGalleryRescanOnResume(
            GalleryMediaGrant(true, true, false),
            GalleryMediaGrant(true, true, false)))
    }

    @Test fun anyPermissionScopeTransitionWithAccessForcesRescan() {
        assertTrue(requiresGalleryRescanOnResume(none, selectedOnly))
        assertTrue(requiresGalleryRescanOnResume(full, selectedOnly))
        assertTrue(requiresGalleryRescanOnResume(selectedOnly, full))
        assertTrue(requiresGalleryRescanOnResume(full, GalleryMediaGrant(true, false, false)))
    }

    @Test fun noRemainingAccessIsNotAnInventoryRefreshRequest() {
        assertFalse(none.hasAny)
        assertFalse(none.selectionScoped)
        assertFalse(requiresGalleryRescanOnResume(full, none))
        assertFalse(requiresGalleryRescanOnResume(none, none))
    }

    @Test fun mediaTypeSpecificFullAccessRemainsValidWithoutSelectedScope() {
        val imagesOnly = GalleryMediaGrant(true, false, false)
        val videosOnly = GalleryMediaGrant(false, true, false)
        assertTrue(imagesOnly.hasAny)
        assertTrue(videosOnly.hasAny)
        assertFalse(imagesOnly.selectionScoped)
        assertFalse(videosOnly.selectionScoped)
        assertFalse(requiresGalleryRescanOnResume(imagesOnly, imagesOnly))
        assertFalse(requiresGalleryRescanOnResume(videosOnly, videosOnly))
    }
    @Test fun onlyFullAccessLossClosesTheOpenViewer() {
        // #505: re-picked or narrowed grants keep the user where they were.
        assertFalse(invalidatesGallerySnapshotOnResume(selectedOnly, selectedOnly))
        assertFalse(invalidatesGallerySnapshotOnResume(imagesAndSelectedVideos, imagesAndSelectedVideos))
        assertFalse(invalidatesGallerySnapshotOnResume(full, selectedOnly))
        assertFalse(invalidatesGallerySnapshotOnResume(full, full))
        assertFalse(invalidatesGallerySnapshotOnResume(
            GalleryMediaGrant(true, false, false),
            GalleryMediaGrant(false, true, false)))
        assertTrue(invalidatesGallerySnapshotOnResume(full, none))
        assertTrue(invalidatesGallerySnapshotOnResume(selectedOnly, none))
    }


    @Test fun ActivityRecreationRestrictsOldSelectedAndMediaTypeOnlySnapshots() {
        assertTrue(initialRestrictedMediaRefreshTicket(selectedOnly) == Long.MAX_VALUE)
        assertTrue(initialRestrictedMediaRefreshTicket(imagesAndSelectedVideos) == Long.MAX_VALUE)
        assertTrue(initialRestrictedMediaRefreshTicket(
            GalleryMediaGrant(true, false, false)) == Long.MAX_VALUE)
        assertTrue(initialRestrictedMediaRefreshTicket(
            GalleryMediaGrant(false, true, false)) == Long.MAX_VALUE)
    }

    @Test fun FullOrDeniedPermissionsDoNotNeedInitialScopedSentinel() {
        assertTrue(initialRestrictedMediaRefreshTicket(full) == 0L)
        assertTrue(initialRestrictedMediaRefreshTicket(
            GalleryMediaGrant(true, true, false)) == 0L)
        assertTrue(initialRestrictedMediaRefreshTicket(none) == 0L)
    }


    @Test fun recreatedActivityWithFullGrantsMustRestoreRetainedRevokedQueue() {
        // Within the new Activity these permission bits are unchanged, so
        // old previous-vs-current checks alone would never restart the scan.
        val previous = full
        val current = full
        assertFalse(requiresGalleryRescanOnResume(previous, current))
        assertTrue(needsRetainedGalleryRegrantRecovery(current, true))
        assertFalse(needsRetainedGalleryRegrantRecovery(current, false))
    }

    @Test fun recreatedActivitySelectedOrMediaTypeOnlyCanRestoreRevokedQueue() {
        for (grant in listOf(
            selectedOnly,
            GalleryMediaGrant(true, false, false),
            GalleryMediaGrant(false, true, false),
            imagesAndSelectedVideos
        )) {
            assertTrue(needsRetainedGalleryRegrantRecovery(grant, true))
            assertFalse(needsRetainedGalleryRegrantRecovery(grant, false))
        }
    }

    @Test fun deniedAccessCanNeverReopenRetainedRevokedQueue() {
        assertFalse(needsRetainedGalleryRegrantRecovery(none, true))
        assertFalse(needsRetainedGalleryRegrantRecovery(none, false))
    }

    @Test fun unchangedFullGrantRetainsFastPathWhenNotPreviouslyRevoked() {
        assertFalse(requiresGalleryRescanOnResume(full, full))
        assertFalse(needsRetainedGalleryRegrantRecovery(full, false))
        assertFalse(invalidatesGallerySnapshotOnResume(full, full))
    }

    @Test fun pendingSelectedQueryCannotStandInForRestoredFullScope() {
        // Activity recreated after request for selected-only A, but Android
        // Settings now reports full B. Pending ticket is nonzero yet stale.
        assertTrue(needsNewGrantBoundScan(full, selectedOnly, selectedOnly))
        assertTrue(needsNewGrantBoundScan(full, null, selectedOnly))
    }

    @Test fun repeatedResumeDoesNotCancelOwnMatchingPendingFullQuery() {
        // A second lifecycle callback should not restart the same scan.
        assertFalse(needsNewGrantBoundScan(full, selectedOnly, full))
        assertFalse(needsNewGrantBoundScan(full, null, full))
    }

    @Test fun scopeRoundTripMustReplaceEvenMatchingOldCatalogWhenPendingDiffers() {
        // Catalog is from earlier full access, but a selected-only scan is
        // still running. Accepting the catalog without replacing pending
        // selected query would let its late completion poison full access.
        assertTrue(needsNewGrantBoundScan(full, full, selectedOnly))
    }

    @Test fun initialMissingOrUnboundCatalogRequestsCurrentGrantExactlyOnce() {
        assertTrue(needsNewGrantBoundScan(full, null, null))
        assertTrue(needsNewGrantBoundScan(selectedOnly, null, null))
        assertFalse(needsNewGrantBoundScan(full, full, null))
        assertFalse(needsNewGrantBoundScan(selectedOnly, selectedOnly, selectedOnly))
    }

    @Test fun fullRevocationCannotIssueAnyProviderQueryDespiteStalePending() {
        assertFalse(needsNewGrantBoundScan(none, selectedOnly, full))
        assertFalse(needsNewGrantBoundScan(none, full, null))
        assertFalse(needsNewGrantBoundScan(none, null, selectedOnly))
    }

    @Test fun videosOnlyVsImagesOnlyRegrantDetectsObsoletePendingJob() {
        val photos = GalleryMediaGrant(true, false, false)
        val videos = GalleryMediaGrant(false, true, false)
        assertTrue(needsNewGrantBoundScan(videos, photos, photos))
        assertTrue(needsNewGrantBoundScan(photos, photos, videos))
        assertFalse(needsNewGrantBoundScan(videos, photos, videos))
    }

    @Test fun exhaustiveSyntheticScopeTransitionsRespectPendingGrant() {
        val scopes = (0..7).map { bits ->
            GalleryMediaGrant(
                (bits and 1) != 0, (bits and 2) != 0, (bits and 4) != 0
            )
        }
        for (current in scopes) {
            for (published in listOf<GalleryMediaGrant?>(null) + scopes) {
                for (pending in listOf<GalleryMediaGrant?>(null) + scopes) {
                    val expected = current.hasAny &&
                        (pending != null && pending != current ||
                            pending == null && published != current)
                    assertTrue(
                        "current=$current published=$published pending=$pending",
                        needsNewGrantBoundScan(current, published, pending) == expected
                    )
                }
            }
        }
    }

}
