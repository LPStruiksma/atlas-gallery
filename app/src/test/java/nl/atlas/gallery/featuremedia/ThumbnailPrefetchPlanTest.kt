package nl.atlas.gallery.featuremedia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #555: prefetch window, velocity and memory budget (pure JVM). */
class ThumbnailPrefetchPlanTest {
    private val cols = 4

    @Test fun idleWindowWarmsTwoRowsEachSideNearestFirst() {
        val slots = ThumbnailPrefetchPlan.slots(firstVisible = 40, lastVisible = 75,
            slotCount = 1000, columns = cols, direction = 0, rowsPerSecond = 0f)
        assertEquals(16, slots.size)
        assertEquals(76, slots[0]); assertEquals(39, slots[1])
        assertEquals(77, slots[2]); assertEquals(38, slots[3])
        assertTrue(slots.none { it in 40..75 })
    }

    @Test fun flingingDownWarmsAheadByVelocityThenOneRowBehind() {
        // 20 rows/s * 0.35 s = 7 rows ahead.
        val slots = ThumbnailPrefetchPlan.slots(40, 75, 1000, cols, direction = 1, rowsPerSecond = 20f)
        assertEquals(7 * cols + 1 * cols, slots.size)
        assertArrayEquals((76 until 76 + 7 * cols).toList().toIntArray(), slots.copyOfRange(0, 7 * cols))
        assertArrayEquals(intArrayOf(39, 38, 37, 36), slots.copyOfRange(7 * cols, slots.size))
    }

    @Test fun flingingUpWarmsAboveFirst() {
        val slots = ThumbnailPrefetchPlan.slots(400, 435, 1000, cols, direction = -1, rowsPerSecond = -20f)
        assertEquals(399, slots[0])
        assertEquals(400 - 7 * cols, slots[7 * cols - 1])
        assertEquals(436, slots[7 * cols])
    }

    @Test fun lookaheadIsCappedAndNeverBelowIdle() {
        assertEquals(ThumbnailPrefetchPlan.IDLE_ROWS_AHEAD, ThumbnailPrefetchPlan.rowsAhead(0f))
        assertEquals(ThumbnailPrefetchPlan.IDLE_ROWS_AHEAD, ThumbnailPrefetchPlan.rowsAhead(Float.NaN))
        assertEquals(ThumbnailPrefetchPlan.MAX_ROWS_AHEAD, ThumbnailPrefetchPlan.rowsAhead(1_000f))
        assertEquals(ThumbnailPrefetchPlan.IDLE_ROWS_AHEAD,
            ThumbnailPrefetchPlan.rowsAhead(Float.NEGATIVE_INFINITY))
        assertEquals(4, ThumbnailPrefetchPlan.rowsAhead(-10f))
    }

    @Test fun windowIsClippedAtBothEnds() {
        val top = ThumbnailPrefetchPlan.slots(0, 35, 50, cols, direction = -1, rowsPerSecond = -30f)
        // Nothing above slot 0; one row below (behind when scrolling up).
        assertArrayEquals(intArrayOf(36, 37, 38, 39), top)
        val end = ThumbnailPrefetchPlan.slots(10, 49, 50, cols, direction = 1, rowsPerSecond = 30f)
        assertArrayEquals(intArrayOf(9, 8, 7, 6), end)
        assertEquals(0, ThumbnailPrefetchPlan.slots(0, 0, 0, cols, 0, 0f).size)
        assertEquals(0, ThumbnailPrefetchPlan.slots(5, 4, 100, cols, 0, 0f).size)
        assertEquals(0, ThumbnailPrefetchPlan.slots(0, 4, 100, 0, 0, 0f).size)
    }

    @Test fun velocityTrackerFollowsScrollAndDecaysToRest() {
        val t = ScrollVelocityTracker(smoothing = 0f)
        t.sample(0f, 0L)
        t.sample(8f, 100_000_000L) // 8 slots in 0.1 s
        assertEquals(80f, t.slotsPerSecond, 0.01f)
        assertEquals(1, t.direction)
        t.sample(4f, 200_000_000L)
        assertEquals(-40f, t.slotsPerSecond, 0.01f)
        assertEquals(-1, t.direction)
        // A gap without frames is rest, not a slow drift.
        t.sample(4.5f, 900_000_000L)
        assertEquals(0f, t.slotsPerSecond, 0f)
        assertEquals(0, t.direction)
        // Out-of-order frames are ignored.
        t.sample(100f, 800_000_000L)
        assertEquals(0f, t.slotsPerSecond, 0f)
    }

    @Test fun memoryBudgetFollowsDeviceClass() {
        val mb = 1024L * 1024L
        // 12 GB phone, 256 MB heap class: RAM/48 capped at 256 MB.
        assertEquals(256 * mb, thumbnailMemoryCacheBytes(12L * 1024 * mb, 256, lowRam = false))
        // 6 GB phone: 128 MB, above Coil's 20%-of-heap default (≈51 MB).
        assertEquals(6L * 1024 * mb / 48, thumbnailMemoryCacheBytes(6L * 1024 * mb, 256, lowRam = false))
        // Low-RAM devices keep Coil's default.
        assertEquals((128 * mb * 0.2).toLong(), thumbnailMemoryCacheBytes(2L * 1024 * mb, 128, lowRam = true))
        // Unknown RAM falls back to the default, never zero.
        assertEquals((192 * mb * 0.2).toLong(), thumbnailMemoryCacheBytes(0L, 192, lowRam = false))
    }
}
