package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android graphics, photos, MediaStore, coroutines or device. */
class ViewerScrollOwnershipTest {
    @Test fun aSingleScrollAcquiresAndReleasesOwnership() {
        val running = acquireViewerScrollLease(0)
        assertEquals(1, running)
        assertTrue(isViewerScrollOwned(running))
        assertEquals(0, releaseViewerScrollLease(running))
        assertFalse(isViewerScrollOwned(0))
    }

    @Test fun completingOneOfThreeConcurrentJobsPreservesTheOtherTwo() {
        var depth = 0
        depth = acquireViewerScrollLease(depth) // thumbnail jump
        depth = acquireViewerScrollLease(depth) // pager reconciliation
        depth = acquireViewerScrollLease(depth) // immersive chrome restore
        assertEquals(3, depth)
        depth = releaseViewerScrollLease(depth)
        assertEquals(2, depth)
        assertTrue(isViewerScrollOwned(depth))
        depth = releaseViewerScrollLease(depth)
        assertEquals(1, depth)
        assertTrue(isViewerScrollOwned(depth))
        depth = releaseViewerScrollLease(depth)
        assertFalse(isViewerScrollOwned(depth))
    }

    @Test fun canceledOlderOwnerCannotReleaseNewerGuard() {
        var depth = acquireViewerScrollLease(0)
        depth = acquireViewerScrollLease(depth)
        // A canceled older collectLatest job runs its finally.
        depth = releaseViewerScrollLease(depth)
        assertTrue(isViewerScrollOwned(depth))
        // Only now does the newer scroll finish.
        depth = releaseViewerScrollLease(depth)
        assertFalse(isViewerScrollOwned(depth))
    }

    @Test fun everyCompletionOrderingOfThreeOwnersIsSafe() {
        for (first in 0..2) for (second in 0..2) {
            if (first == second) continue
            var depth = 0
            repeat(3) { depth = acquireViewerScrollLease(depth) }
            depth = releaseViewerScrollLease(depth)
            assertTrue(isViewerScrollOwned(depth))
            depth = releaseViewerScrollLease(depth)
            assertTrue(isViewerScrollOwned(depth))
            depth = releaseViewerScrollLease(depth)
            assertFalse(isViewerScrollOwned(depth))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun doubleReleaseFailsClosed() {
        releaseViewerScrollLease(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDepthCannotBeAcquired() {
        acquireViewerScrollLease(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overflowingDepthFailsClosed() {
        acquireViewerScrollLease(Int.MAX_VALUE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDepthCannotMasqueradeAsIdle() {
        isViewerScrollOwned(-1)
    }
}
