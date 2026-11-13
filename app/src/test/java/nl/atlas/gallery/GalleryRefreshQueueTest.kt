package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRefreshQueueTest {
    @Test fun noWorkUntilFirstRequest() {
        assertNull(GalleryRefreshQueue().begin())
    }

    @Test fun initialRequestStartsAndFinishesWithoutRedundantQuery() {
        val q = GalleryRefreshQueue()
        assertEquals(1L, q.request())
        assertEquals(1L, q.begin())
        assertNull("Only one query may run concurrently", q.begin())
        assertFalse(q.finish(1L))
    }

    @Test fun idleBeginAfterCompletionDoesNotStartDuplicateScan() {
        val q = GalleryRefreshQueue()
        q.request()
        assertEquals(1L, q.begin())
        assertFalse(q.finish(1L))
        assertNull(q.begin())
        assertEquals(2L, q.request())
        assertEquals(2L, q.begin())
        assertFalse(q.finish(2L))
        assertNull(q.begin())
    }

    @Test fun failureCanRestartOnlyAfterAnExplicitRetryTicket() {
        val q = GalleryRefreshQueue()
        q.request()
        val failed = q.begin()!!
        assertFalse(q.finish(failed))
        assertNull(q.begin())
        val retry = q.request()
        assertTrue(retry > failed)
        assertEquals(retry, q.begin())
        assertFalse(q.finish(retry))
    }

    @Test fun overlappingRequestsCoalesceIntoLatestFollowup() {
        val q = GalleryRefreshQueue()
        assertEquals(1L, q.request())
        assertEquals(1L, q.begin())
        assertEquals(2L, q.request())
        assertEquals(3L, q.request())
        assertEquals(4L, q.request())
        assertNull(q.begin())
        assertTrue(q.finish(1L))
        assertEquals(4L, q.begin())
        assertFalse(q.finish(4L))
    }

    @Test fun staleCompletionCannotUnlockNewGrantGeneration() {
        val q = GalleryRefreshQueue()
        val old = q.request()
        assertEquals(old, q.begin())
        val scopeChanged = q.request()
        assertTrue(old < scopeChanged)
        assertTrue(q.finish(old))
        assertEquals(scopeChanged, q.begin())
        assertFalse(q.finish(scopeChanged))
    }

    @Test fun errorsDoNotSilentlyConsumeLaterRequests() {
        val q = GalleryRefreshQueue()
        q.request()
        val running = q.begin()!!
        // begin/finish are agnostic to success. ViewModel advances its
        // successful-completion epoch ONLY after a successful media read.
        val userRetry = q.request()
        assertTrue(q.finish(running))
        assertEquals(userRetry, q.begin())
        assertFalse(q.finish(userRetry))
    }

    @Test(expected = IllegalStateException::class)
    fun completingWrongScanFailsClosed() {
        val q = GalleryRefreshQueue()
        q.request()
        q.begin()
        q.finish(999L)
    }

    @Test fun completedScanCannotBeOvertakenByCoalescedRequest() {
        val q = GalleryRefreshQueue()
        var lastSuccessful = 0L
        val first = q.request()
        assertEquals(first, q.begin())
        val grantGeneration = q.request()
        lastSuccessful = first
        assertTrue(lastSuccessful < grantGeneration)
        assertTrue(q.finish(first))
        val second = q.begin()!!
        assertEquals(grantGeneration, second)
        lastSuccessful = second
        assertTrue(lastSuccessful >= grantGeneration)
        assertFalse(q.finish(second))
    }
    @Test fun onlyNewestActiveRequestMayPublishMetadataOrError() {
        val q = GalleryRefreshQueue()
        val first = q.request()
        assertFalse(q.mayPublish(first)) // not active yet
        assertEquals(first, q.begin())
        assertTrue(q.mayPublish(first))
        val changedScope = q.request()
        assertFalse(q.mayPublish(first))
        assertFalse(q.mayPublish(changedScope)) // queued, not started
        assertTrue(q.finish(first))
        assertFalse(q.mayPublish(first))
        assertEquals(changedScope, q.begin())
        assertTrue(q.mayPublish(changedScope))
        assertFalse(q.finish(changedScope))
        assertFalse(q.mayPublish(changedScope)) // no active query
    }

    @Test fun threeGrantChangesOnlyAllowCoalescedLatestTicket() {
        val q = GalleryRefreshQueue()
        val old = q.request()
        assertEquals(old, q.begin())
        val intermediate = q.request()
        val last = q.request()
        assertFalse(q.mayPublish(old))
        assertFalse(q.mayPublish(intermediate))
        assertFalse(q.mayPublish(last))
        assertTrue(q.finish(old))
        assertEquals(last, q.begin())
        assertTrue(q.mayPublish(last))
        assertFalse(q.mayPublish(intermediate))
        assertFalse(q.finish(last))
    }

    @Test fun repeatedManualRefreshAfterACompletedScanProducesNewValidEpoch() {
        val q = GalleryRefreshQueue()
        val one = q.request()
        assertEquals(one, q.begin())
        assertTrue(q.mayPublish(one))
        assertFalse(q.finish(one))
        val two = q.request()
        assertFalse(q.mayPublish(one))
        assertEquals(two, q.begin())
        assertTrue(q.mayPublish(two))
        assertFalse(q.finish(two))
    }

    @Test fun staleOrFutureTicketCannotMasqueradeAsActive() {
        val q = GalleryRefreshQueue()
        assertFalse(q.mayPublish(0))
        assertFalse(q.mayPublish(-1))
        q.request()
        q.begin()
        assertFalse(q.mayPublish(0))
        assertFalse(q.mayPublish(999))
        assertTrue(q.mayPublish(1))
    }

    @Test fun burstOfSyntheticRequestsNeverMakesStaleGenerationPublishable() {
        val q = GalleryRefreshQueue()
        var running = q.request()
        assertEquals(running, q.begin())
        for (i in 1..512) {
            val requested = q.request()
            assertFalse(q.mayPublish(running))
            assertFalse(q.mayPublish(requested))
            if (i % 17 == 0) {
                assertTrue(q.finish(running))
                running = q.begin()!!
                assertEquals(requested, running)
                assertTrue(q.mayPublish(running))
            }
        }
        assertTrue(q.finish(running))
        val final = q.begin()!!
        assertTrue(q.mayPublish(final))
        assertFalse(q.finish(final))
    }

    @Test fun fullRevocationStopsOldPublicationAndSuppressesFollowup() {
        val q = GalleryRefreshQueue()
        val old = q.request()
        assertEquals(old, q.begin())
        assertTrue(q.mayPublish(old))
        val denialEpoch = q.pauseForAccessLoss()
        assertTrue(denialEpoch > old)
        assertFalse(q.mayPublish(old))
        assertFalse(q.mayPublish(denialEpoch))
        assertNull(q.begin())
        assertTrue(q.finish(old))
        // There is a newer generation, but the grant is still denied.
        assertNull(q.begin())
    }

    @Test fun regrantRestartsExactlyLatestScopedScanAfterDenial() {
        val q = GalleryRefreshQueue()
        val first = q.request()
        assertEquals(first, q.begin())
        val denied = q.pauseForAccessLoss()
        assertFalse(q.mayPublish(first))
        val selected = q.request()
        assertTrue(selected > denied)
        assertNull(q.begin()) // an old canceled query has not cleaned up
        assertTrue(q.finish(first))
        assertEquals(selected, q.begin())
        assertTrue(q.mayPublish(selected))
        assertFalse(q.mayPublish(first))
        assertFalse(q.finish(selected))
    }

    @Test fun repeatedAccessLossWithoutInFlightScanRemainsIdleUntilExplicitRequest() {
        val q = GalleryRefreshQueue()
        val one = q.pauseForAccessLoss()
        val two = q.pauseForAccessLoss()
        assertTrue(two > one)
        assertNull(q.begin())
        assertFalse(q.mayPublish(one))
        assertFalse(q.mayPublish(two))
        val restored = q.request()
        assertEquals(restored, q.begin())
        assertTrue(q.mayPublish(restored))
        assertFalse(q.finish(restored))
    }

    @Test fun aDeniedScanNeverPublishesEvenIfItFinishesSuccessfully() {
        val q = GalleryRefreshQueue()
        val previous = q.request()
        q.begin()
        val denied = q.pauseForAccessLoss()
        assertTrue(denied > previous)
        assertFalse(q.mayPublish(previous))
        assertTrue(q.finish(previous))
        assertNull(q.begin())
        val restored = q.request()
        assertEquals(restored, q.begin())
        assertTrue(q.mayPublish(restored))
        assertFalse(q.finish(restored))
    }

}
