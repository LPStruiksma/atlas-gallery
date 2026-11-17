package nl.atlas.gallery.featurecuration

import java.lang.management.ManagementFactory
import java.util.PriorityQueue
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** PERF-17 parity proof and opt-in, reproducible synthetic JVM benchmark. */
class CurationVisualHashPerfTest {
    @Test fun optimizedEnumerationMatchesBruteForceOnAdversarialSmallInputs() {
        val random = Random(17)
        val fixtures = listOf(
            longArrayOf(),
            longArrayOf(0, 0, 0, -1, Long.MIN_VALUE),
            LongArray(80) { it.toLong() },
            LongArray(96) { random.nextLong() },
            LongArray(72) { index -> 0x55aa55aa55aa55aaL xor (1L shl (index % 64)) }
        )
        for (hashes in fixtures) for (distance in listOf(2, 5, 11, 31)) {
            val actual = findCurationVisualPairs(
                hashes, distance, maxPairs = Int.MAX_VALUE, maxComparisons = Long.MAX_VALUE
            )
            assertFalse(actual.truncated)
            assertEquals(bruteForce(hashes, distance), actual.matches)
        }
    }

    @Test fun optimizedEnumerationIsExactlyIncumbentCompatible() {
        val inputs = listOf(
            LongArray(160) { Random(91).nextLong() },
            LongArray(160) { it.toLong() and 15L },
            LongArray(160) { 7L }
        )
        for (hashes in inputs) for (distance in listOf(2, 5, 17, 31)) {
            for (budget in listOf(0L, 1L, 37L, 4_000L, Long.MAX_VALUE)) {
                val skip: (Int, Int) -> Boolean = { left, right -> (left * 31 + right) % 7 == 0 }
                val expected = incumbent(hashes, distance, 23, budget, skip)
                val actual = findCurationVisualPairs(hashes, distance, 23, budget, skip)
                assertEquals("distance=$distance budget=$budget", expected, actual)
            }
        }
    }

    @Test fun cancellationCheckpointSequenceRemainsCompatible() {
        val hashes = LongArray(800) { it.toLong() and 3L }
        fun calls(run: ((() -> Unit) -> Unit)): Int {
            var count = 0
            try {
                run { if (++count == 4) error("cancel") }
            } catch (expected: IllegalStateException) {
                assertEquals("cancel", expected.message)
            }
            return count
        }
        val before = calls { checkpoint -> incumbent(hashes, checkpoint = checkpoint) }
        val after = calls { checkpoint -> findCurationVisualPairs(hashes, checkpoint = checkpoint) }
        assertEquals(before, after)
        assertEquals(4, after)
    }

    @Test fun benchmark250kSyntheticWorkloads() {
        assumeTrue("set -Datlas.perf17=true to run the benchmark",
            java.lang.Boolean.getBoolean("atlas.perf17"))
        val random = Random(224)
        val randomHashes = LongArray(SIZE) { random.nextLong() }
        val nearDuplicates = randomHashes.copyOf()
        for (index in nearDuplicates.indices step 32) {
            val source = nearDuplicates[index]
            for (offset in 1..minOf(7, nearDuplicates.lastIndex - index))
                nearDuplicates[index + offset] = source xor (1L shl (offset - 1))
        }
        val workloads = linkedMapOf(
            "random" to randomHashes,
            "near-duplicate" to nearDuplicates,
            "all-equal" to LongArray(SIZE) { 0x123456789abcdefL },
            "zero-match" to LongArray(SIZE) { index -> mix64(index.toLong()) }
        )
        for ((name, hashes) in workloads) {
            // Untimed warmup of both implementations before paired samples.
            incumbent(hashes, maxComparisons = BENCHMARK_BUDGET)
            findCurationVisualPairs(hashes, maxComparisons = BENCHMARK_BUDGET)
            val before = measure(SAMPLES) { incumbent(hashes, maxComparisons = BENCHMARK_BUDGET) }
            val after = measure(SAMPLES) {
                findCurationVisualPairs(hashes, maxComparisons = BENCHMARK_BUDGET)
            }
            assertEquals(before.result, after.result)
            println("PERF17 workload=$name size=$SIZE samples=$SAMPLES " +
                "before_cpu_ms_p50=${before.p50Millis} before_cpu_ms_p95=${before.p95Millis} " +
                "after_cpu_ms_p50=${after.p50Millis} after_cpu_ms_p95=${after.p95Millis} " +
                "before_alloc_bytes_peak=${before.peakAllocatedBytes} " +
                "after_alloc_bytes_peak=${after.peakAllocatedBytes}")
        }
    }

    private data class Measurement(
        val p50Millis: Double,
        val p95Millis: Double,
        val peakAllocatedBytes: Long,
        val result: CurationVisualMatches
    )

    private fun measure(samples: Int, block: () -> CurationVisualMatches): Measurement {
        val bean = ManagementFactory.getThreadMXBean()
        val allocationBean = bean as? com.sun.management.ThreadMXBean
        val cpu = LongArray(samples)
        val allocated = LongArray(samples)
        lateinit var result: CurationVisualMatches
        repeat(samples) { sample ->
            System.gc()
            val thread = Thread.currentThread().id
            val allocationStart = allocationBean?.getThreadAllocatedBytes(thread) ?: -1L
            val cpuStart = bean.currentThreadCpuTime
            result = block()
            cpu[sample] = bean.currentThreadCpuTime - cpuStart
            allocated[sample] = if (allocationStart >= 0L)
                allocationBean!!.getThreadAllocatedBytes(thread) - allocationStart else -1L
        }
        cpu.sort()
        return Measurement(
            cpu[(samples - 1) / 2] / 1_000_000.0,
            cpu[((samples * 95 + 99) / 100 - 1).coerceIn(0, samples - 1)] / 1_000_000.0,
            allocated.maxOrNull() ?: -1L,
            result
        )
    }

    private fun bruteForce(hashes: LongArray, maxDistance: Int): List<CurationVisualMatch> =
        buildList {
            for (left in hashes.indices) for (right in left + 1 until hashes.size) {
                val distance = curationHammingDistance(hashes[left], hashes[right])
                if (distance <= maxDistance) add(CurationVisualMatch(left, right, distance))
            }
        }.sortedWith(compareBy<CurationVisualMatch> { it.distance }
            .thenBy { it.left }.thenBy { it.right })

    /** Exact pre-PERF-17 implementation retained only as a benchmark/parity oracle. */
    private fun incumbent(
        hashes: LongArray,
        maxDistance: Int = VISUAL_PAIR_MAX_DISTANCE,
        maxPairs: Int = 200,
        maxComparisons: Long = 25_000_000L,
        skip: (Int, Int) -> Boolean = { _, _ -> false },
        checkpoint: () -> Unit = {}
    ): CurationVisualMatches {
        val bands = curationBands(maxDistance)
        val worstFirst = compareByDescending<CurationVisualMatch> { it.distance }
            .thenByDescending { it.left }.thenByDescending { it.right }
        val kept = PriorityQueue(maxOf(1, maxPairs), worstFirst)
        var comparisons = 0L
        var truncated = false
        val keys = LongArray(hashes.size)
        bandLoop@ for ((band, range) in bands.withIndex()) {
            checkpoint()
            for (i in hashes.indices) keys[i] = (testBandValue(hashes[i], range) shl 32) or i.toLong()
            keys.sort()
            var start = 0
            while (start < keys.size) {
                val value = keys[start] ushr 32
                var end = start + 1
                while (end < keys.size && keys[end] ushr 32 == value) end++
                for (a in start until end - 1) {
                    val i = (keys[a] and 0xffffffffL).toInt()
                    for (b in a + 1 until end) {
                        if (comparisons >= maxComparisons) {
                            truncated = true
                            break@bandLoop
                        }
                        if ((++comparisons and 0xffff) == 0L) checkpoint()
                        val j = (keys[b] and 0xffffffffL).toInt()
                        val distance = curationHammingDistance(hashes[i], hashes[j])
                        if (distance > maxDistance) continue
                        if (testFirstSharedBand(hashes[i], hashes[j], bands) != band) continue
                        val left = minOf(i, j)
                        val right = maxOf(i, j)
                        if (skip(left, right) || maxPairs == 0) continue
                        val match = CurationVisualMatch(left, right, distance)
                        if (kept.size < maxPairs) kept.add(match)
                        else if (worstFirst.compare(match, kept.peek()) > 0) {
                            kept.poll()
                            kept.add(match)
                        }
                    }
                }
                start = end
            }
        }
        return CurationVisualMatches(kept.sortedWith(compareBy<CurationVisualMatch> { it.distance }
            .thenBy { it.left }.thenBy { it.right }), comparisons, truncated)
    }

    private fun testBandValue(hash: Long, range: IntRange): Long {
        val width = range.count()
        return (hash ushr range.first) and ((1L shl width) - 1)
    }

    private fun testFirstSharedBand(a: Long, b: Long, bands: List<IntRange>): Int {
        for ((index, range) in bands.withIndex())
            if (testBandValue(a, range) == testBandValue(b, range)) return index
        return -1
    }

    private fun mix64(input: Long): Long {
        var value = input + -7046029254386353131L
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private companion object {
        const val SIZE = 250_000
        const val SAMPLES = 5
        const val BENCHMARK_BUDGET = 1_000_000L
    }
}
