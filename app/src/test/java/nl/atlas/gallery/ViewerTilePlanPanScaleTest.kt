package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import nl.atlas.gallery.perf.threadAllocatedBytes
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * PERF-28 (#329): pan/zoom tile planning is O(visible tiles) and reuses an
 * unchanged grid. Synthetic dimensions only; no image bytes or decoder.
 * The reference below is a frozen copy of the planner before PERF-28.
 */
class ViewerTilePlanPanScaleTest {
    private val sources = listOf(
        "12MP" to (4_000 to 3_000),
        "50MP" to (8_160 to 6_120),
        "200MP" to (16_320 to 12_240)
    )
    private val zooms = listOf(1.5f, 4f, 12f)
    private val viewW = 1_440
    private val viewH = 3_120

    /** 100 pan steps of a slow diagonal drag, then back: the common finger pace. */
    private fun panSteps(): List<Pair<Float, Float>> =
        (0 until 100).map { i ->
            val t = if (i < 50) i else 100 - i
            (t * 9f - 220f) to (t * 13f - 300f)
        }

    @Test fun everyPanStepMatchesReferenceAndStaysWithinVisiblePlusOneTile() {
        var reused = 0
        var steps = 0
        for ((_, dims) in sources) for (zoom in zooms) {
            var previous: ViewerTilePlan? = null
            for ((tx, ty) in panSteps()) {
                val (w, h) = dims
                val expected = referencePlan(w, h, viewW, viewH, zoom, tx, ty)
                val fresh = planViewerTiles(w, h, viewW, viewH, zoom, tx, ty)
                val chained = planViewerTiles(w, h, viewW, viewH, zoom, tx, ty, previous = previous)
                assertEquals(expected, fresh)
                assertEquals(expected, chained)
                val plan = requireNotNull(chained)
                if (previous != null && plan.tiles === previous.tiles) reused++
                steps++
                assertWithinVisiblePlusMargin(plan)
                previous = plan
            }
        }
        // Most small pan steps keep the same tile grid.
        assertTrue("reused $reused of $steps", reused > steps / 2)
    }

    @Test fun staleOrForeignPreviousPlanNeverChangesTheResult() {
        val other = requireNotNull(planViewerTiles(16_320, 12_240, viewW, viewH, 4f, 0f, 0f))
        for ((_, dims) in sources) for (zoom in zooms) for ((tx, ty) in panSteps().take(10)) {
            val (w, h) = dims
            assertEquals(referencePlan(w, h, viewW, viewH, zoom, tx, ty),
                planViewerTiles(w, h, viewW, viewH, zoom, tx, ty, previous = other))
        }
        // Same grid indices, different source edge: the clamped last tile differs.
        val a = requireNotNull(planViewerTiles(4_000, 3_000, 1_000, 1_000, 2f, 0f, 0f))
        val b = planViewerTiles(4_001, 3_000, 1_000, 1_000, 2f, 0f, 0f, previous = a)
        assertEquals(referencePlan(4_001, 3_000, 1_000, 1_000, 2f, 0f, 0f), b)
    }

    @Test fun reportsPerStepTimeAndAllocationBeforeAfter() {
        val measured = threadAllocatedBytes() != null
        fun allocated(): Long = threadAllocatedBytes() ?: 0L
        val rounds = 200
        // Warm both paths so the JIT sees each before measuring.
        repeat(20) { runAll(reference = true); runAll(reference = false) }
        val beforeNs = LongArray(rounds)
        val afterNs = LongArray(rounds)
        var beforeBytes = 0L
        var afterBytes = 0L
        repeat(rounds) { r ->
            val a0 = allocated(); val t0 = System.nanoTime()
            runAll(reference = true)
            val t1 = System.nanoTime(); val a1 = allocated()
            runAll(reference = false)
            val t2 = System.nanoTime(); val a2 = allocated()
            beforeNs[r] = t1 - t0; afterNs[r] = t2 - t1
            beforeBytes += a1 - a0; afterBytes += a2 - a1
        }
        val stepsPerRound = sources.size * zooms.size * panSteps().size
        beforeNs.sort(); afterNs.sort()
        fun p(a: LongArray, q: Double) = a[((a.size - 1) * q).toInt()].toDouble() / stepsPerRound
        println("PERF-28 tile plan per pan step (n=$rounds rounds x $stepsPerRound steps, JVM): " +
            "before p50=%.0f ns p95=%.0f ns alloc=%d B; after p50=%.0f ns p95=%.0f ns alloc=%d B".format(
                p(beforeNs, .5), p(beforeNs, .95), beforeBytes / (rounds.toLong() * stepsPerRound),
                p(afterNs, .5), p(afterNs, .95), afterBytes / (rounds.toLong() * stepsPerRound)))
        if (measured && beforeBytes > 0) assertTrue(afterBytes < beforeBytes)
    }

    private var sink = 0

    private fun runAll(reference: Boolean) {
        for ((_, dims) in sources) for (zoom in zooms) {
            var previous: ViewerTilePlan? = null
            for ((tx, ty) in panSteps()) {
                val (w, h) = dims
                val plan = if (reference) referencePlan(w, h, viewW, viewH, zoom, tx, ty)
                else planViewerTiles(w, h, viewW, viewH, zoom, tx, ty, previous = previous)
                sink += plan?.tiles?.size ?: 0
                previous = plan
            }
        }
    }

    private fun assertWithinVisiblePlusMargin(plan: ViewerTilePlan) {
        val span = 512 * plan.sampleSize
        val v = plan.visibleSource
        for (t in plan.tiles) {
            assertTrue(t.right > v.left - span && t.left < v.right + span &&
                t.bottom > v.top - span && t.top < v.bottom + span)
        }
        assertTrue(plan.tiles.size <= 12)
    }

    /** Frozen pre-PERF-28 planner (default tilePixels/maxTiles/budget). */
    private fun referencePlan(
        sourceWidth: Int, sourceHeight: Int, viewportWidth: Int, viewportHeight: Int,
        zoom: Float, translationX: Float, translationY: Float
    ): ViewerTilePlan? {
        val tilePixels = 512
        val maxTiles = 12
        val maxDecodedBytes = 16L * 1024 * 1024
        val fit = min(viewportWidth.toDouble() / sourceWidth, viewportHeight.toDouble() / sourceHeight)
        val scale = fit * zoom
        val cx = sourceWidth / 2.0 - translationX / scale
        val cy = sourceHeight / 2.0 - translationY / scale
        val halfW = viewportWidth / (2.0 * scale)
        val halfH = viewportHeight / (2.0 * scale)
        val left = floor(cx - halfW).coerceIn(0.0, sourceWidth.toDouble()).toInt()
        val top = floor(cy - halfH).coerceIn(0.0, sourceHeight.toDouble()).toInt()
        val right = ceil(cx + halfW).coerceIn(0.0, sourceWidth.toDouble()).toInt()
        val bottom = ceil(cy + halfH).coerceIn(0.0, sourceHeight.toDouble()).toInt()
        if (left >= right || top >= bottom) return null
        val visible = ViewerSourceRect(left, top, right, bottom)
        var sample = 1
        while (sample < (1 shl 20) && sample.toDouble() * 2.0 <= 1.0 / scale) sample *= 2
        val idealSample = sample
        while (true) {
            val span = tilePixels.toLong() * sample
            val x0 = left / span
            val x1 = (right.toLong() - 1) / span
            val y0 = top / span
            val y1 = (bottom.toLong() - 1) / span
            val tileCount = (x1 - x0 + 1) * (y1 - y0 + 1)
            if (tileCount <= maxTiles) {
                val tiles = ArrayList<ViewerSourceRect>(tileCount.toInt())
                var budget = 0L
                for (y in y0..y1) for (x in x0..x1) {
                    val sx = x * span
                    val sy = y * span
                    val ex = min(sourceWidth.toLong(), sx + span)
                    val ey = min(sourceHeight.toLong(), sy + span)
                    budget += ((ex - sx + sample - 1) / sample) * ((ey - sy + sample - 1) / sample) * 4L
                    tiles += ViewerSourceRect(sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt())
                }
                if (budget <= maxDecodedBytes) {
                    return ViewerTilePlan(visible, sample, tiles, budget, sample > idealSample)
                }
            }
            if (sample >= (1 shl 20)) return null
            sample *= 2
        }
    }
}
