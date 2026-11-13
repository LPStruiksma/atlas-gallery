package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Fictional permission flags and provider revisions, no Android or media. */
class GalleryScanPublicationAdmissionTest {
    private val images = GalleryMediaGrant(true, false, false)
    private val videos = GalleryMediaGrant(false, true, false)
    private val selected = GalleryMediaGrant(false, false, true)
    private val none = GalleryMediaGrant(false, false, false)

    private fun accepted(
        latest: Boolean = true,
        revoked: Boolean = false,
        start: GalleryMediaGrant = images,
        end: GalleryMediaGrant = images,
        observerStart: Long = 12L,
        observerEnd: Long = 12L
    ) = mayPublishCompletedGalleryScan(
        latest, revoked, start, end, observerStart, observerEnd)

    @Test fun stableLatestAuthorizedScanMayPublish() {
        assertTrue(accepted())
        assertTrue(accepted(start = selected, end = selected))
        assertTrue(accepted(start = videos, end = videos))
        assertTrue(accepted(observerStart = 0L, observerEnd = 0L))
    }

    @Test fun providerNotificationBetweenLoadAndPublicationRejectsOldInventory() {
        assertFalse(accepted(observerEnd = 13L))
        assertFalse(accepted(observerStart = Long.MAX_VALUE, observerEnd = 0L))
        assertFalse(accepted(observerStart = 0L, observerEnd = Long.MAX_VALUE))
        assertFalse(accepted(latest = false))
        assertFalse(accepted(revoked = true))
        assertTrue(shouldRetryCompletedGalleryScan(
            true, false, images, images, 12L, 13L, true))
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, images, images, 12L, 13L, false))
    }

    @Test fun changedOrRevokedGrantMustNeverPublishOrRetryAutomatically() {
        for (changed in listOf(none, videos, selected,
                GalleryMediaGrant(true, true, false))) {
            assertFalse(accepted(end = changed))
            assertFalse(shouldRetryCompletedGalleryScan(
                true, false, images, changed, 12L, 13L, true))
        }
        assertFalse(accepted(start = none, end = none))
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, none, none, 12L, 13L, true))
        assertFalse(shouldRetryCompletedGalleryScan(
            true, true, images, images, 12L, 13L, true))
        assertFalse(shouldRetryCompletedGalleryScan(
            false, false, images, images, 12L, 13L, true))
    }

    @Test fun autoFollowUpIsQueuedOnceAndNeverPublishesItsStalePredecessor() {
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        assertEquals(first, queue.begin())
        assertTrue(queue.mayPublish(first))
        val endGrant = images
        // MediaStore changed AFTER the first query was read. The ViewModel
        // queues exactly one follow-up while the first scan still owns it.
        assertFalse(mayPublishCompletedGalleryScan(
            queue.mayPublish(first), false, images, endGrant, 90L, 91L))
        assertTrue(shouldRetryCompletedGalleryScan(
            queue.mayPublish(first), false, images, endGrant, 90L, 91L, true))
        val automaticTicket = queue.request()
        assertFalse(queue.mayPublish(first))
        assertTrue(queue.finish(first))
        assertEquals(automaticTicket, queue.begin())
        // Provider churn AGAIN cannot create a third automatic scan.
        assertFalse(shouldRetryCompletedGalleryScan(
            queue.mayPublish(automaticTicket), false, images, endGrant,
            91L, 92L, false))
        assertFalse(queue.finish(automaticTicket))
    }

    @Test fun stableOrRepeatedProviderChurnDoesNotCreateAnAutomaticRetryLoop() {
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, images, images, 12L, 12L, true))
        // The calling ViewModel marks the automatically retried ticket and
        // passes false thereafter; continuous provider churn won't spin.
        assertFalse(shouldRetryCompletedGalleryScan(
            true, false, images, images, 13L, 14L, false))
        assertTrue(shouldRetryCompletedGalleryScan(
            true, false, selected, selected, 13L, 14L, true))
    }
}
