package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Pure hypothetical ticket IDs and cancellation callbacks only. */
class GalleryScanSupersessionTest {
    @Test fun newerPermissionTicketAbortsExactlyOnce() {
        val scan = GalleryScanSupersession()
        var calls = 0
        scan.register(11L) { calls++ }
        scan.abortOlderThan(11L)
        scan.abortOlderThan(10L)
        assertEquals(0, calls)
        scan.abortOlderThan(12L)
        scan.abortOlderThan(13L)
        scan.abortAll()
        assertEquals(1, calls)
        scan.finish(11L)
    }

    @Test fun permissionReentryCancelsOldButNotReplacement() {
        val q = GalleryRefreshQueue()
        val s = GalleryScanSupersession()
        var oldCanceled = 0
        var latestCanceled = 0
        val old = q.request()
        assertEquals(old, q.begin())
        s.register(old) { oldCanceled++ }
        val currentScope = q.request()
        s.abortOlderThan(currentScope)
        assertEquals(1, oldCanceled)
        s.finish(old)
        assertTrue(q.finish(old))
        assertEquals(currentScope, q.begin())
        s.register(currentScope) { latestCanceled++ }
        assertEquals(0, latestCanceled)
        assertTrue(q.mayPublish(currentScope))
        s.finish(currentScope)
        assertFalse(q.finish(currentScope))
    }

    @Test fun threeRapidReselectionsStillCoalesceToNewestAfterAbort() {
        val q = GalleryRefreshQueue()
        val s = GalleryScanSupersession()
        var aborted = 0
        val first = q.request()
        assertEquals(first, q.begin())
        s.register(first) { aborted++ }
        q.request().also(s::abortOlderThan)
        val latest = q.request().also(s::abortOlderThan)
        assertEquals(1, aborted)
        s.finish(first)
        assertTrue(q.finish(first))
        assertEquals(latest, q.begin())
        assertTrue(q.mayPublish(latest))
        assertFalse(q.mayPublish(first))
    }

    @Test fun teardownAbortsProviderOnlyOnceAcrossRepeatedDisposal() {
        val s = GalleryScanSupersession()
        var calls = 0
        s.register(4L) { calls++ }
        s.abortAll()
        s.abortAll()
        s.abortOlderThan(5L)
        assertEquals(1, calls)
        s.finish(4L)
    }

    @Test fun idleTeardownDoesNotAbortSubsequentIndependentScan() {
        val s = GalleryScanSupersession()
        var calls = 0
        s.abortAll()
        s.register(1L) { calls++ }
        s.finish(1L)
        s.abortAll()
        assertEquals(0, calls)
        s.register(2L) { calls++ }
        s.abortOlderThan(3L)
        assertEquals(1, calls)
        s.finish(2L)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesConcurrentRegistration() {
        val s = GalleryScanSupersession()
        s.register(1L) { }
        s.register(2L) { }
    }

    @Test(expected = IllegalStateException::class)
    fun refusesWrongScanCleanup() {
        val s = GalleryScanSupersession()
        s.register(1L) { }
        s.finish(2L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesZeroTicketRegistration() {
        GalleryScanSupersession().register(0L) { }
    }

    @Test fun abortCallbackFailureRemainsIdempotent() {
        val s = GalleryScanSupersession()
        var calls = 0
        s.register(9L) {
            calls++
            throw IllegalStateException("synthetic provider cancel failure")
        }
        // The provider's failed cancel must never throw out of an Android
        // permission callback and strand the newer requested grant ticket.
        s.abortOlderThan(10L)
        s.abortOlderThan(11L)
        s.abortAll()
        assertEquals(1, calls)
        s.finish(9L)
    }
    @Test fun synchronousEmptyCursorCompletionSeesRegisteredOwner() {
        val s = GalleryScanSupersession()
        var aborted = 0
        var observedStart = false
        s.registerThenStart(12L, abort = { aborted++ }) {
            observedStart = true
            // This is what a Main.immediate coroutine with an empty cursor
            // can do BEFORE launch() returns to its caller.
            s.finish(12L)
        }
        assertTrue(observedStart)
        s.abortAll()
        assertEquals(0, aborted)
        // No orphan remains; another request may start immediately.
        s.registerThenStart(13L, abort = { aborted++ }) { s.finish(13L) }
        assertEquals(0, aborted)
    }

    @Test fun synchronousStartFailureClearsOwnershipBeforeRethrow() {
        val s = GalleryScanSupersession()
        var aborts = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            s.registerThenStart(3L, abort = { aborts++ }) {
                throw IllegalStateException("fabricated startup error")
            }
        }
        assertEquals("fabricated startup error", thrown.message)
        s.abortAll()
        assertEquals(0, aborts)
        s.registerThenStart(4L, abort = { aborts++ }) { s.finish(4L) }
        assertEquals(0, aborts)
    }

    @Test fun newestGrantMayAbortDuringSynchronousRegisteredStart() {
        val q = GalleryRefreshQueue()
        val s = GalleryScanSupersession()
        val first = q.request()
        q.begin()
        var interrupted = 0
        s.registerThenStart(first, abort = { interrupted++ }) {
            val newScope = q.request()
            s.abortOlderThan(newScope)
            assertEquals(1, interrupted)
            assertFalse(q.mayPublish(first))
            s.finish(first)
            assertTrue(q.finish(first))
            assertEquals(newScope, q.begin())
        }
        assertEquals(1, interrupted)
    }

    @Test fun failedStartAfterSynchronousFinishCannotClearNewerOwner() {
        val s = GalleryScanSupersession()
        var nextAborted = 0
        assertThrows(IllegalStateException::class.java) {
            s.registerThenStart(1L, abort = {}) {
                s.finish(1L)
                s.register(2L) { nextAborted++ }
                throw IllegalStateException("fictional late startup error")
            }
        }
        s.abortOlderThan(3L)
        assertEquals(1, nextAborted)
        s.finish(2L)
    }

    @Test fun failedProviderAbortStillAllowsNextQueuedPermissionScan() {
        val queue = GalleryRefreshQueue()
        val owner = GalleryScanSupersession()
        val first = queue.request()
        assertEquals(first, queue.begin())
        var providerCalls = 0
        var coroutineCanceled = 0
        owner.register(first) {
            try {
                providerCalls++
                throw IllegalStateException("synthetic cancel IPC failure")
            } finally {
                coroutineCanceled++
            }
        }
        val newGrant = queue.request()
        owner.abortOlderThan(newGrant) // never throws
        assertEquals(1, providerCalls)
        assertEquals(1, coroutineCanceled)
        assertFalse(queue.mayPublish(first))
        owner.finish(first)
        assertTrue(queue.finish(first))
        assertEquals(newGrant, queue.begin())
        assertTrue(queue.mayPublish(newGrant))
        owner.registerThenStart(newGrant, abort = {}) {
            owner.finish(newGrant)
            assertFalse(queue.finish(newGrant))
        }
    }

    @Test fun failedProviderAbortDoesNotInterruptViewModelTeardown() {
        val owner = GalleryScanSupersession()
        var interrupted = 0
        owner.register(52L) {
            interrupted++
            throw IllegalStateException("synthetic teardown provider error")
        }
        owner.abortAll() // cleanup must never be prevented
        owner.abortAll() // repeat remains idempotent
        assertEquals(1, interrupted)
        owner.finish(52L)
    }

}
