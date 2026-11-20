package nl.atlas.gallery.featureaiedit.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AIE-5 (#406): a worker stop must reach a call that is still blocked, not wait for it to return. */
class WorkerStopWatcherTest {
    @Test fun stopFiresWhileTheWorkIsStillBlocked() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val stopSeenWhileBlocked = AtomicBoolean(false)
        runBlocking {
            val worker = launch(Dispatchers.IO) {
                withContext(Dispatchers.IO) {
                    val watcher = WorkerStopWatcher(this) { stopped.countDown() }
                    try {
                        started.countDown()
                        // Models a socket read that only returns once the stop disconnects it. With a
                        // completion hook this would wait the full timeout, because the job cannot complete.
                        stopSeenWhileBlocked.set(stopped.await(5, TimeUnit.SECONDS))
                    } finally { watcher.release() }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            worker.cancel()
            worker.join()
        }
        assertTrue(stopSeenWhileBlocked.get())
    }

    @Test fun normalCompletionNeverStops() {
        val stops = AtomicInteger()
        val result = runBlocking {
            withContext(Dispatchers.IO) {
                val watcher = WorkerStopWatcher(this) { stops.incrementAndGet() }
                try { "done" } finally { watcher.release() }
            }
        }
        assertEquals("done", result)
        assertEquals(0, stops.get())
    }
}
