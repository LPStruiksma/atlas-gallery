package nl.atlas.gallery.featureidentity.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IDS-9 (#475) review finding: a low-memory callback (main thread) or close() must never
 * wait for a running candidate. The release happens at once when idle, else right after.
 */
class WarmReleaseGateTest {
    @Test fun idleReleaseRunsImmediately() {
        val released = AtomicInteger()
        val gate = WarmReleaseGate { released.incrementAndGet() }
        assertTrue(gate.requestRelease())
        assertEquals(1, released.get())
        assertEquals(7, gate.exclusive { 7 })
        assertEquals("no stray second release", 1, released.get())
    }

    @Test fun releaseDuringACandidateDoesNotWaitAndRunsAfterIt() {
        val released = AtomicInteger()
        val gate = WarmReleaseGate { released.incrementAndGet() }
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Thread {
            gate.exclusive { inside.countDown(); finish.await(5, TimeUnit.SECONDS) }
        }.apply { start() }
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertFalse("must not run while the candidate holds the models", gate.requestRelease())
        assertFalse(gate.requestRelease())
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("requestRelease returned in ${waitedMs} ms", waitedMs < 100)
        assertEquals(0, released.get())

        finish.countDown()
        worker.join(5_000)
        assertEquals("released once, right after the candidate", 1, released.get())
    }

    @Test fun releaseRunsEvenWhenTheCandidateThrows() {
        val released = AtomicInteger()
        val gate = WarmReleaseGate { released.incrementAndGet() }
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Thread {
            runCatching { gate.exclusive<Unit> { inside.countDown(); finish.await(5, TimeUnit.SECONDS); error("candidate failed") } }
        }.apply { start() }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertFalse(gate.requestRelease())
        finish.countDown()
        worker.join(5_000)
        assertEquals(1, released.get())
    }

    @Test fun requestRacingWithFinalReleaseDoesNotGetStranded() {
        val released = AtomicInteger()
        val firstReleaseEntered = CountDownLatch(1)
        val allowFirstRelease = CountDownLatch(1)
        val gate = WarmReleaseGate {
            if (released.incrementAndGet() == 1) {
                firstReleaseEntered.countDown()
                check(allowFirstRelease.await(5, TimeUnit.SECONDS))
            }
        }
        val candidateEntered = CountDownLatch(1)
        val finishCandidate = CountDownLatch(1)
        val worker = Thread {
            gate.exclusive {
                candidateEntered.countDown()
                check(finishCandidate.await(5, TimeUnit.SECONDS))
            }
        }.apply { start() }
        try {
            assertTrue(candidateEntered.await(5, TimeUnit.SECONDS))
            assertFalse(gate.requestRelease())
            finishCandidate.countDown()
            assertTrue(firstReleaseEntered.await(5, TimeUnit.SECONDS))
            // The candidate has already consumed pending, but still holds the lock.
            // Previously this second request was lost after the final pending check.
            assertFalse(gate.requestRelease())
        } finally {
            allowFirstRelease.countDown()
            finishCandidate.countDown()
            worker.join(5_000)
        }
        assertFalse("candidate must finish", worker.isAlive)
        assertEquals("a release request during finalization must not be stranded", 2, released.get())
    }
}
