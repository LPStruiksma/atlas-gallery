package nl.atlas.gallery.featureeditor.auto

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import nl.atlas.gallery.featureeditor.engine.EditOperation

/** A clockwise correction, in degrees, and the share of directional edge evidence supporting it. */
data class StraightenSuggestion(val degrees: Double, val confidence: Double) {
    init {
        require(degrees.isFinite() && degrees in -45.0..45.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun asOperation(id: String = "auto-straighten"): EditOperation.Straighten {
        require(id.isNotBlank()) { "Operation id must not be blank" }
        return EditOperation.Straighten(id, degrees)
    }
}

/** Deterministic Sobel-gradient voting for prominent horizontal and vertical lines. */
object AutoStraighten {
    /**
     * [luma] contains one unsigned 8-bit luminance value per pixel, row-major, with no stride.
     * Input should already be reduced to at most 512 px on its long edge.
     */
    fun suggest(luma: ByteArray, width: Int, height: Int): StraightenSuggestion? {
        require(width >= 3 && height >= 3) { "Image must be at least 3 by 3" }
        require(width.toLong() * height == luma.size.toLong()) { "Luminance size does not match dimensions" }

        val horizontal = ArrayList<Int>()
        val vertical = ArrayList<Int>()
        fun p(x: Int, y: Int) = luma[y * width + x].toInt() and 0xff
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val gx = -p(x - 1, y - 1) + p(x + 1, y - 1) - 2 * p(x - 1, y) +
                2 * p(x + 1, y) - p(x - 1, y + 1) + p(x + 1, y + 1)
            val gy = -p(x - 1, y - 1) - 2 * p(x, y - 1) - p(x + 1, y - 1) +
                p(x - 1, y + 1) + 2 * p(x, y + 1) + p(x + 1, y + 1)
            val magnitude = hypot(gx.toDouble(), gy.toDouble())
            if (magnitude < MIN_GRADIENT) continue
            (if (abs(gy) >= abs(gx)) horizontal else vertical).add(y * width + x)
        }
        val points = if (horizontal.size >= vertical.size) horizontal else vertical
        val isHorizontal = points === horizontal
        if (points.size < maxOf(24, (width + height) / 8)) return null
        val stride = (points.size / MAX_POINTS).coerceAtLeast(1)
        val sampled = points.filterIndexed { index, _ -> index % stride == 0 }
        val diagonal = hypot(width.toDouble(), height.toDouble()).roundToInt()
        val accumulator = IntArray(diagonal * 2 + 3)
        val scores = DoubleArray(ANGLE_STEPS)
        for (step in 0 until ANGLE_STEPS) {
            accumulator.fill(0)
            val angle = (-MAX_EVIDENCE_ANGLE + step * ANGLE_STEP) * Math.PI / 180.0
            val nx = if (isHorizontal) -sin(angle) else cos(angle)
            val ny = if (isHorizontal) cos(angle) else sin(angle)
            for (point in sampled) {
                val projection = ((point % width) * nx + (point / width) * ny).roundToInt() + diagonal
                accumulator[projection]++
            }
            scores[step] = accumulator.sumOf { it.toDouble() * it }
        }
        val best = scores.indices.maxByOrNull(scores::get) ?: return null
        val runner = scores.indices.filter { abs(it - best) > 4 }.maxOfOrNull(scores::get) ?: 0.0
        val baseline = sampled.size.toDouble() * sampled.size / accumulator.size + sampled.size
        val concentration = ((scores[best] - baseline) / (sampled.size.toDouble() * sampled.size) * 12.0).coerceIn(0.0, 1.0)
        val dominance = ((scores[best] - runner) / scores[best]).coerceIn(0.0, 1.0)
        val confidence = (concentration * (dominance * 4.0).coerceAtMost(1.0)).coerceIn(0.0, 1.0)
        if (concentration < 0.08 || dominance < 0.015 || confidence < 0.08) return null
        val lineAngle = -MAX_EVIDENCE_ANGLE + best * ANGLE_STEP
        // A clockwise source tilt needs the opposite (counter-clockwise) recipe correction.
        val correction = (-lineAngle).coerceIn(-45.0, 45.0)
        return StraightenSuggestion(correction, confidence)
    }

    private const val MAX_EVIDENCE_ANGLE = 20.0
    private const val ANGLE_STEP = 0.25
    private const val ANGLE_STEPS = 161
    private const val MAX_POINTS = 8_000
    private const val MIN_GRADIENT = 80.0
}
