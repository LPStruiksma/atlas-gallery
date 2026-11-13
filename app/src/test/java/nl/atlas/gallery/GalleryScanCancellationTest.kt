package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Invented row indices only: no photo/permission/device operations. */
class GalleryScanCancellationTest {
    @Test fun checkpointsAreExactlyEvery128RowsIncludingZero() {
        val seen = ArrayList<Int>()
        for (row in 0 until 150_000) {
            checkGalleryScanCancellation(row) { seen.add(row) }
        }
        assertEquals((150_000 + 127) / 128, seen.size)
        assertEquals(0, seen.first())
        assertEquals(128, seen[1])
        assertEquals(149_888, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b - a == 128 })
    }

    @Test fun canceledCursorStopsBeforeAnother128RecordsAreAppended() {
        var permitted = true
        var appended = 0
        var checkpoints = 0
        try {
            for (row in 0 until 10_000) {
                // Simulate ViewModel disposal just after an earlier checkpoint.
                if (row == 130) permitted = false
                checkGalleryScanCancellation(row) {
                    checkpoints++
                    if (!permitted) throw CancellationException("synthetic dispose")
                }
                appended++
            }
            fail("Canceled scan incorrectly consumed all rows")
        } catch (_: CancellationException) {
            assertEquals(256, appended)
            assertEquals(3, checkpoints)
            assertTrue(appended - 130 < 128)
        }
    }

    @Test fun cancellationBeforeFirstRowPreventsAnyRead() {
        var rows = 0
        try {
            checkGalleryScanCancellation(0) {
                throw CancellationException("synthetic cancellation")
            }
            rows++
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, rows)
        }
    }

    @Test fun nonCheckpointRowsDoNotInvokeExpensiveValidation() {
        var checks = 0
        for (row in 1 until 128) {
            checkGalleryScanCancellation(row) { checks++ }
        }
        assertEquals(0, checks)
        checkGalleryScanCancellation(128) { checks++ }
        assertEquals(1, checks)
    }

    @Test fun unrelatedExceptionIsNotSilentlySwallowed() {
        try {
            checkGalleryScanCancellation(128) {
                throw IllegalStateException("synthetic malformed cursor")
            }
            fail("Unexpected successful checkpoint")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic malformed cursor", expected.message)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRowIndexIsRejected() {
        checkGalleryScanCancellation(-1) { }
    }
}
