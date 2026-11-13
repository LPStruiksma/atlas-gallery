package nl.atlas.gallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual Kotlin coroutine scheduling contracts, on a disposable host with
 * Dispatchers.Unconfined standing in for synchronous Main.immediate paths.
 * Synthetic tickets only. Does not exercise Android ContentResolver.
 */
class GalleryScanCoroutineRegistrationTest {
    @Test fun lazyCoroutineCompletesSynchronouslyAfterRegistration() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = GalleryScanSupersession()
        val queue = GalleryRefreshQueue()
        val ticket = queue.request()
        assertEquals(ticket, queue.begin())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            owner.finish(ticket)
            assertFalse(queue.finish(ticket))
        }
        var aborted = 0
        owner.registerThenStart(ticket, abort = { aborted++; job.cancel() }) {
            assertTrue(job.start())
        }
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        owner.abortAll()
        assertEquals(0, aborted)
        assertNull(queue.begin())
        scope.coroutineContext[Job]?.cancel()
    }

    @Test fun immediateCancellationInCoroutineFinallyReleasesOldAndAllowsNewTicket() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = GalleryScanSupersession()
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        assertEquals(first, queue.begin())
        val cleanup = mutableListOf<Long>()
        val old = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitCancellation()
            } finally {
                cleanup += first
                owner.finish(first)
                val pending = queue.finish(first)
                assertTrue(pending)
                assertTrue(scope.isActive)
            }
        }
        owner.registerThenStart(first, abort = { old.cancel() }) { old.start() }
        val newest = queue.request()
        owner.abortOlderThan(newest)
        assertTrue(old.isCancelled)
        assertTrue(old.isCompleted)
        assertEquals(listOf(first), cleanup)
        assertEquals(newest, queue.begin())
        val current = scope.launch(start = CoroutineStart.LAZY) {
            owner.finish(newest)
            assertFalse(queue.finish(newest))
        }
        owner.registerThenStart(newest, abort = { current.cancel() }) { current.start() }
        assertTrue(current.isCompleted)
        assertFalse(current.isCancelled)
        scope.coroutineContext[Job]?.cancel()
    }

    @Test fun repeatedAbortDuringCanceledCoroutineDoesNotCancelNextQuery() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = GalleryScanSupersession()
        val queue = GalleryRefreshQueue()
        val first = queue.request()
        queue.begin()
        var aborted = 0
        val old = scope.launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() }
            finally { owner.finish(first); queue.finish(first) }
        }
        owner.registerThenStart(first, abort = { aborted++; old.cancel() }) { old.start() }
        val newest = queue.request()
        owner.abortOlderThan(newest)
        owner.abortOlderThan(newest)
        assertEquals(1, aborted)
        assertEquals(newest, queue.begin())
        var newestCanceled = 0
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            owner.finish(newest)
            queue.finish(newest)
        }
        owner.registerThenStart(newest,
            abort = { newestCanceled++; newJob.cancel() }) { newJob.start() }
        assertTrue(newJob.isCompleted)
        assertEquals(0, newestCanceled)
        scope.coroutineContext[Job]?.cancel()
    }
}
