package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Invented metadata and permission flags; no Android provider or private media. */
class GalleryAccessRevocationTest {
    private val allowImages = GalleryMediaGrant(true, false, false)
    private val denyAll = GalleryMediaGrant(false, false, false)

    private fun invented(id: Long) = MediaRecord(
        id, "content://invented/media/$id", "Invented $id",
        "DCIM/ATLAS-SYNTHETIC/", 128L, id, false
    )

    @Test fun permissionLossQuarantinesPreviousCatalogAndStopsProvider() {
        val queue = GalleryRefreshQueue()
        val scans = GalleryScanSupersession()
        val old = queue.request()
        assertEquals(old, queue.begin())
        var providerAborts = 0
        scans.register(old) { providerAborts++ }
        val previous = GalleryPublishedInventory(old, listOf(invented(101)), allowImages)
        assertEquals(101L, previous.authorizedItems(allowImages, old).single().id)

        val revoked = queue.pauseForAccessLoss()
        scans.abortOlderThan(revoked)
        val emptyPublication = GalleryPublishedInventory(revoked, emptyList())
        assertEquals(1, providerAborts)
        assertTrue(previous.authorizedItems(denyAll, old).isEmpty())
        assertTrue(previous.authorizedItems(allowImages, revoked).isEmpty())
        assertTrue(emptyPublication.authorizedItems(denyAll, revoked).isEmpty())
        assertFalse(queue.mayPublish(old))
        scans.finish(old)
        assertTrue(queue.finish(old))
        assertNull("No query while media permission is denied", queue.begin())
    }

    @Test fun newAccessScopesRunOnlyAfterCanceledOldQueryReleasesOwner() {
        val queue = GalleryRefreshQueue()
        val scans = GalleryScanSupersession()
        val old = queue.request()
        queue.begin()
        var aborted = 0
        scans.register(old) { aborted++ }
        val deniedEpoch = queue.pauseForAccessLoss()
        scans.abortOlderThan(deniedEpoch)
        assertEquals(1, aborted)

        val newGrant = queue.request()
        assertTrue(newGrant > deniedEpoch)
        assertNull(queue.begin()) // old cancel cleanup not complete
        scans.finish(old)
        assertTrue(queue.finish(old))
        assertEquals(newGrant, queue.begin())
        assertTrue(queue.mayPublish(newGrant))
        val photoB = GalleryPublishedInventory(newGrant, listOf(invented(202)), allowImages)
        assertEquals(202L, photoB.authorizedItems(allowImages, newGrant).single().id)
        assertFalse(GalleryPublishedInventory(old, listOf(invented(101)))
            .satisfies(newGrant))
        assertFalse(queue.finish(newGrant))
    }

    @Test fun revokingAThreeTimesReenteredSelectedScopeCoalescesCorrectly() {
        val queue = GalleryRefreshQueue()
        val scan = GalleryScanSupersession()
        val start = queue.request()
        queue.begin()
        var aborts = 0
        scan.register(start) { aborts++ }
        val denied = queue.pauseForAccessLoss()
        scan.abortOlderThan(denied)
        val grantOne = queue.request()
        val grantTwo = queue.request()
        val grantThree = queue.request()
        assertEquals(1, aborts)
        assertTrue(grantThree > grantTwo && grantTwo > grantOne)
        scan.finish(start)
        assertTrue(queue.finish(start))
        assertEquals(grantThree, queue.begin())
        assertTrue(queue.mayPublish(grantThree))
        assertFalse(queue.mayPublish(grantTwo))
        assertFalse(queue.finish(grantThree))
    }

    @Test fun revocationGateDoesNotEnumerateHistorical150kList() {
        val inaccessible = object : AbstractList<MediaRecord>() {
            override val size: Int = 150_000
            override fun get(index: Int): MediaRecord =
                error("Full revocation must never read stale photo index $index")
        }
        val q = GalleryRefreshQueue()
        val old = q.request()
        q.begin()
        // The published epoch must come from this exact queue. A fabricated
        // epoch of 42 with a brand-new queue at 1 is impossible in production.
        val inventory = GalleryPublishedInventory(old, inaccessible)
        val revoked = q.pauseForAccessLoss()
        assertTrue(revoked > old)
        assertTrue(inventory.authorizedItems(denyAll, revoked).isEmpty())
        assertTrue(inventory.authorizedItems(allowImages, revoked).isEmpty())
        assertFalse(q.mayPublish(old))
        assertTrue(q.finish(old))
        assertNull(q.begin())
    }

    @Test fun revokedAccessCannotRetainViewerRecordsByLatestPublication() {
        val q = GalleryRefreshQueue()
        val old = q.request()
        q.begin()
        val oldRecords = listOf(invented(1), invented(2))
        val before = GalleryPublishedInventory(old, oldRecords)
        val loss = q.pauseForAccessLoss()
        val after = GalleryPublishedInventory(loss, emptyList())
        assertEquals(2, before.items.size)
        assertTrue(after.items.isEmpty())
        assertFalse(q.mayPublish(old))
        assertFalse(before.satisfies(loss))
        assertTrue(after.satisfies(loss))
        assertTrue(after.authorizedItems(denyAll, loss).isEmpty())
    }
    @Test fun retainedViewModelRecoversFullGrantAfterActivityWasRecreated() {
        val q = GalleryRefreshQueue()
        val original = q.request()
        assertEquals(original, q.begin())
        val revokedEpoch = q.pauseForAccessLoss()
        assertTrue(q.finish(original))
        assertNull(q.begin())
        val alreadyFullWhenRecreated = GalleryMediaGrant(true, true, false)
        assertFalse(requiresGalleryRescanOnResume(alreadyFullWhenRecreated, alreadyFullWhenRecreated))
        assertTrue(needsRetainedGalleryRegrantRecovery(alreadyFullWhenRecreated, true))
        val recovered = q.request()
        assertTrue(recovered > revokedEpoch)
        assertEquals(recovered, q.begin())
        val priorEmpty = GalleryPublishedInventory(revokedEpoch, emptyList())
        assertFalse(priorEmpty.satisfies(recovered))
        val restored = GalleryPublishedInventory(recovered, listOf(invented(33)), alreadyFullWhenRecreated)
        assertEquals(33L, restored.authorizedItems(alreadyFullWhenRecreated, recovered).single().id)
        assertFalse(q.finish(recovered))
    }

    @Test fun retainedViewModelRemainsQuiescentIfNewActivityIsStillDenied() {
        val q = GalleryRefreshQueue()
        q.pauseForAccessLoss()
        assertFalse(needsRetainedGalleryRegrantRecovery(denyAll, true))
        assertNull(q.begin())
        assertTrue(GalleryPublishedInventory(1L, emptyList())
            .authorizedItems(denyAll, 1L).isEmpty())
    }

    @Test fun doubleLifecycleCatchUpDoesNotIssueDuplicateRefreshWhenRecovered() {
        val q = GalleryRefreshQueue()
        q.pauseForAccessLoss()
        val grant = GalleryMediaGrant(true, true, false)
        var retainedRevoked = true
        var regrantCount = 0
        fun observedResume() {
            if (needsRetainedGalleryRegrantRecovery(grant, retainedRevoked)) {
                retainedRevoked = false
                q.request()
                regrantCount++
            }
        }
        observedResume() // ON_RESUME
        observedResume() // LaunchedEffect after already resumed
        assertEquals(1, regrantCount)
        val ticket = q.begin()!!
        assertEquals(2L, ticket)
        assertFalse(q.finish(ticket))
    }

}
