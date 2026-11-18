package nl.atlas.gallery.featuremedia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-21: synthetic scale and boundary proof; no Android media or device claims. */
class ThumbnailPrefetchPlanScaleTest {
    private fun legacy(
        first: Int, lastVisible: Int, count: Int, cols: Int, dir: Int, speed: Float
    ): IntArray {
        if (count <= 0 || cols <= 0 || first < 0 || lastVisible < first) return intArrayOf()
        val last = minOf(lastVisible, count - 1)
        val ahead = ThumbnailPrefetchPlan.rowsAhead(speed) * cols
        val behind = cols
        val forward = if (dir > 0) ahead else if (dir < 0) behind else 2 * cols
        val backward = if (dir < 0) ahead else if (dir > 0) behind else 2 * cols
        val after = minOf(forward, count - 1 - last).coerceAtLeast(0)
        val before = minOf(backward, first).coerceAtLeast(0)
        val out = IntArray(after + before)
        var n = 0
        if (dir == 0) {
            for (d in 1..maxOf(after, before)) {
                if (d <= after) out[n++] = last + d
                if (d <= before) out[n++] = first - d
            }
        } else if (dir > 0) {
            for (d in 1..after) out[n++] = last + d
            for (d in 1..before) out[n++] = first - d
        } else {
            for (d in 1..before) out[n++] = first - d
            for (d in 1..after) out[n++] = last + d
        }
        return out
    }

    @Test fun matchesLegacyOrdinaryGridsAndNearestFirstOrder() {
        for (count in intArrayOf(1, 100, 250_000)) {
            for (columns in intArrayOf(1, 3, 4, 7, 12, 28)) {
                for (dir in intArrayOf(-1, 0, 1)) {
                    for (speed in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, -0.5f, 0f, 1f, 20f, 10_000f, Float.POSITIVE_INFINITY)) {
                        for (first in intArrayOf(0, count / 2, count - 1)) {
                            val last = minOf(count - 1, first + 24)
                            val actual = ThumbnailPrefetchPlan.slots(first, last, count, columns, dir, speed)
                            assertArrayEquals("count=$count cols=$columns dir=$dir speed=$speed first=$first",
                                legacy(first, last, count, columns, dir, speed), actual)
                            assertEquals(actual.size, actual.toSet().size)
                            assertTrue(actual.all { it in 0 until count && it !in first..last })
                        }
                    }
                }
            }
        }
    }

    @Test fun invalidAndOutOfRangeSpansCannotAllocateOutsideCatalogue() {
        val counts = intArrayOf(0, 1, 250_000, Int.MAX_VALUE)
        val columns = intArrayOf(Int.MIN_VALUE, -1, 0, 1, 3, 4, 7, 12, 28, Int.MAX_VALUE)
        for (count in counts) for (cols in columns) for (dir in intArrayOf(-1, 0, 1)) {
            val spans = arrayOf(-1 to 0, 5 to 4, 0 to 0, 0 to Int.MAX_VALUE,
                (count / 2) to (count / 2), (count - 1).coerceAtLeast(0) to (count - 1).coerceAtLeast(0),
                Int.MAX_VALUE to Int.MAX_VALUE)
            for ((first, last) in spans) {
                val actual = ThumbnailPrefetchPlan.slots(first, last, count, cols, dir, Float.MAX_VALUE)
                assertTrue(actual.size <= 2 * ThumbnailPrefetchPlan.MAX_SLOTS_PER_SIDE)
                assertEquals(actual.size, actual.toSet().size)
                assertTrue(actual.all { it >= 0 && it < count && it !in first..last })
                // The caller-visible, already-clipped gap is a hard upper bound.
                if (count <= 0 || cols <= 0 || first < 0 || last < first || first >= count) {
                    assertTrue(actual.isEmpty())
                } else {
                    val available = first.toLong() + (count - 1L - minOf(last, count - 1))
                    assertTrue(actual.size.toLong() <= available)
                }
            }
        }
    }

    @Test fun extremeColumnsRemainProportionalToPhysicalGaps() {
        for (count in intArrayOf(1, 250_000, Int.MAX_VALUE)) {
            // The far side is empty (first is the last slot); the near side is the whole
            // catalogue, so only MAX_SLOTS_PER_SIDE keeps the allocation bounded.
            val first = count - 1
            for (cols in intArrayOf(28, 178_956_971, Int.MAX_VALUE)) {
                for (dir in intArrayOf(-1, 0, 1)) {
                    val actual = ThumbnailPrefetchPlan.slots(first, first, count, cols, dir, Float.MAX_VALUE)
                    assertTrue(actual.size <= first)
                    assertTrue(actual.size <= ThumbnailPrefetchPlan.MAX_SLOTS_PER_SIDE)
                    val maximumRequested = if (dir < 0) 12L * cols else if (dir == 0) 2L * cols else cols.toLong()
                    assertTrue(actual.size.toLong() <= maximumRequested)
                    assertTrue(actual.all { it in 0 until first })
                }
            }
        }
    }

    /** Optional, explicitly uncalibrated JVM diagnostics; not a phone/jank benchmark. */
    @Test fun optionalHotPathSample() {
        if (System.getProperty("atlas.perf21.benchmark") != "true") return
        for ((name, work) in listOf<Pair<String, () -> IntArray>>(
            "baseline" to { legacy(40_000, 40_027, 250_000, 7, 1, 20f) },
            "candidate" to { ThumbnailPrefetchPlan.slots(40_000, 40_027, 250_000, 7, 1, 20f) }
        )) {
            repeat(10_000) { work() }
            val start = System.nanoTime()
            repeat(100_000) { work() }
            val ns = System.nanoTime() - start
            println("PERF21 $name 100000 calls elapsed_ns=$ns (JVM-only, order/JIT-sensitive; allocation bytes not measured)")
        }
    }
}
