package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frozen reference of the main-branch planner at 31231dd82a851657477641947d8c5a9462a7e6f7.
 * This test intentionally retains the original boxed ArrayList<Int> width allocation.
 * Synthetic fixtures only; JVM timings are not a phone performance claim.
 */
class JustifiedRowsPerfTest {
    private fun reference(items: List<RatioMedia>, options: RatioLayoutOptions,
                          checkpoint: (() -> Unit)? = null): RatioLayout {
        if (items.isEmpty()) return RatioLayout(emptyList(), options, 0, 0)
        val rows = ArrayList<RatioRow>((items.size / 2).coerceAtLeast(1))
        val ratios = DoubleArray(options.maxItemsPerRow)
        var offset = 0
        var top = 0L
        val contentWidth = options.contentWidthPx
        while (offset < items.size) {
            if ((offset and 1023) == 0) checkpoint?.invoke()
            var count = 0
            var sum = 0.0
            while (offset + count < items.size && count < options.maxItemsPerRow) {
                val candidate = items[offset + count].displayAspectRatio?.toDouble()
                val ratio = if (candidate == null || !candidate.isFinite() || candidate <= 0.0)
                    1.0 else candidate.coerceIn(0.08, 16.0)
                val newSum = sum + ratio
                val available = contentWidth - count * options.gapPx
                if (count > 0 && available < count + 1) break
                if (count > 0 && available.toDouble() / newSum < options.targetRowHeightPx) {
                    val previousAvailable = contentWidth - (count - 1) * options.gapPx
                    val beforeHeight = previousAvailable / sum
                    val afterHeight = available.toDouble() / newSum
                    if (abs(beforeHeight - options.targetRowHeightPx) <=
                        abs(afterHeight - options.targetRowHeightPx)) break
                }
                ratios[count] = ratio
                sum = newSum
                count++
                if (available.toDouble() / sum <= options.targetRowHeightPx) break
            }
            check(count > 0)
            val last = offset + count == items.size
            val available = contentWidth - (count - 1) * options.gapPx
            require(available >= count)
            val fitHeight = available.toDouble() / sum
            val height = if (last) {
                minOf(fitHeight, options.targetRowHeightPx.toDouble())
            } else {
                minOf(fitHeight, options.maxRowHeightPx.toDouble())
            }.roundToInt().coerceAtLeast(1)
            val nominalWidth = if (!last) available else {
                minOf(available, (sum * height).roundToInt()).coerceAtLeast(count)
            }
            val widths = ArrayList<Int>(count)
            var used = 0
            var cumulative = 0.0
            for (j in 0 until count) {
                cumulative += ratios[j]
                val target = if (j == count - 1) nominalWidth else
                    (nominalWidth * cumulative / sum).roundToInt()
                val maxHere = nominalWidth - used - (count - j - 1)
                val next = (target - used).coerceIn(1, maxHere)
                widths.add(next)
                used += next
            }
            check(widths.sum() == nominalWidth)
            rows.add(RatioRow(offset, count, top, height, widths, nominalWidth == available))
            top += height.toLong() + options.gapPx
            offset += count
        }
        checkpoint?.invoke()
        return RatioLayout(rows, options, items.size, top - options.gapPx)
    }

    private fun assertIdentical(expected: RatioLayout, actual: RatioLayout) {
        assertEquals(expected.mediaCount, actual.mediaCount)
        assertEquals(expected.contentHeightPx, actual.contentHeightPx)
        assertEquals(expected.rows, actual.rows)
        if (actual.mediaCount == 0) return
        for (index in listOf(0, 1, actual.mediaCount / 2, actual.mediaCount - 1)
            .filter { it in 0 until actual.mediaCount }) {
            assertEquals(expected.rowIndexOf(index), actual.rowIndexOf(index))
            for (fraction in listOf(0f, .35f, 1f)) {
                assertEquals(expected.scrollTarget(index, fraction, 400f),
                    actual.scrollTarget(index, fraction, 400f))
            }
        }
    }

    @Test fun geometryMatchesFrozenPlannerAcrossEdgeCasesAndRotation() {
        val widths = listOf(11, 24, 120, 420, 1080, 1440)
        val patterns = listOf(
            listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                0f, -1f, .00001f, 100000f),
            listOf(.08f, .15f, .35f, .55f, 1f, 1.33f, 2f, 16f),
            listOf(1f), listOf(16f), listOf(.08f)
        )
        for (width in widths) for (pattern in patterns) for (n in listOf(0, 1, 2, 5, 6, 7, 19, 101)) {
            val options = RatioLayoutOptions(width, 2340, horizontalInsetPx = 0,
                gapPx = if (width < 24) 1 else 3,
                maxItemsPerRow = 6)
            val items = List(n) { i -> RatioMedia("m$i", pattern[(i * 7 + i / 3) % pattern.size]) }
            assertIdentical(reference(items, options), planRatioRows(items, options))
            val rotated = options.copy(viewportWidthPx = 2340, viewportHeightPx = width)
            assertIdentical(reference(items, rotated), planRatioRows(items, rotated))
        }
    }

    @Test fun cancellationCheckpointSequenceAndExceptionRemainEquivalent() {
        val items = List(10_000) { RatioMedia("m$it", 1f) }
        val options = RatioLayoutOptions(420, 810)
        fun run(planner: (List<RatioMedia>, RatioLayoutOptions, (() -> Unit)?) -> RatioLayout):
            Pair<List<Int>, String?> {
            val points = mutableListOf<Int>()
            var calls = 0
            var failure: String? = null
            try {
                planner(items, options) {
                    points.add(calls++)
                    if (calls == 3) error("cancel")
                }
            } catch (e: IllegalStateException) {
                failure = e.message
            }
            return points to failure
        }
        assertEquals(run(::reference), run(::planRatioRows))
    }

    @Test fun quarterMillionRowsAndPairedJvmProbe() {
        val ratios = listOf(.08f, .35f, .75f, 1f, 1.33f, 2f, 5f, 16f, null, Float.NaN)
        val items = List(250_000) { i -> RatioMedia("synthetic:$i", ratios[(i * 13 + i / 9) % ratios.size]) }
        val options = RatioLayoutOptions(1080, 2340, 0, 6, 422, 675, 6)
        val before = reference(items, options)
        val after = planRatioRows(items, options)
        assertIdentical(before, after)

        val bean = ManagementFactory.getThreadMXBean()
        val allocated = bean as? com.sun.management.ThreadMXBean
        if (allocated != null && allocated.isThreadAllocatedMemorySupported &&
            !allocated.isThreadAllocatedMemoryEnabled) {
            allocated.isThreadAllocatedMemoryEnabled = true
        }
        val threadId = Thread.currentThread().id
        fun measure(plan: () -> RatioLayout): Pair<Long, Long?> {
            val startBytes = if (allocated?.isThreadAllocatedMemoryEnabled == true)
                allocated.getThreadAllocatedBytes(threadId) else -1L
            val start = System.nanoTime()
            val layout = plan()
            val nanos = System.nanoTime() - start
            assertEquals(items.size, layout.mediaCount)
            val endBytes = if (startBytes >= 0L) allocated!!.getThreadAllocatedBytes(threadId) else -1L
            return nanos to (if (startBytes >= 0L && endBytes >= 0L) endBytes - startBytes else null)
        }
        // Alternating order controls JIT and GC drift; no hard real-time threshold in CI.
        repeat(3) { measure { reference(items, options) }; measure { planRatioRows(items, options) } }
        val baseline = mutableListOf<Pair<Long, Long?>>()
        val candidate = mutableListOf<Pair<Long, Long?>>()
        repeat(7) {
            if (it % 2 == 0) {
                baseline += measure { reference(items, options) }
                candidate += measure { planRatioRows(items, options) }
            } else {
                candidate += measure { planRatioRows(items, options) }
                baseline += measure { reference(items, options) }
            }
        }
        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        println("PERF19 250k synthetic JVM n=7 baseline median_ns=${median(baseline.map { it.first })} candidate median_ns=${median(candidate.map { it.first })}")
        val beforeBytes = baseline.mapNotNull { it.second }
        val afterBytes = candidate.mapNotNull { it.second }
        println("PERF19 allocated_bytes baseline=${if (beforeBytes.size == 7) median(beforeBytes).toString() else "unavailable"} candidate=${if (afterBytes.size == 7) median(afterBytes).toString() else "unavailable"}")
    }
}
