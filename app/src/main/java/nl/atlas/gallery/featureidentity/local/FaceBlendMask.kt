package nl.atlas.gallery.featureidentity.local

import kotlin.math.PI
import kotlin.math.hypot

/*
 * IDS-9 (#475): the edit mask in crop space, its guard, and the paste-back.
 * Pure JVM. Masks are FloatArray alpha in 0..1, row-major, size x size.
 */

/** Which mask a candidate used; recorded in its provenance as `mask=segmenter|fallback`. */
enum class MaskSource(val code: String) { SEGMENTER("segmenter"), FALLBACK("fallback") }

data class MaskDecision(
    val alpha: FloatArray,
    val source: MaskSource,
    /** Segmenter face area divided by the area the five landmarks predict; null without a segmenter. */
    val coverage: Double?
)

/** What paste-back changed. Pixels outside [changed] (or all, when null) are bit-identical. */
data class PasteResult(val changedPixels: Int, val changed: PixelRect?)

object FaceBlendMask {
    /**
     * Guard threshold. IDS-2 (BENCH.md, "Mask and colour") found the MediaPipe segmenter
     * silently labelling only 2 to 7 % of the crop as face skin on T16, T23, T24, T25 and
     * T27, while a working face mask covers about 29 to 37 % ("Edit share of crop"), close
     * to the landmark oval below (about 36 % of an ArcFace crop). Failures therefore sit at
     * 0.06 to 0.19 of the predicted area and working masks near 0.8 to 1.0; half the
     * predicted area separates them with margin on both sides. IDS-18 re-measured it with
     * the bundled ONNX on the HyperSwap crop: failures 0.017 to 0.492, working 1.076 to 2.004
     * (assets/models/mediapipe_multiclass/SOURCE.md); the margin under T25 is thin.
     */
    const val MIN_COVERAGE = 0.5

    /** Alpha below this is treated as exactly 0, so the paste never touches those pixels. */
    const val ALPHA_EPSILON = 1f / 512f

    /** Ellipse the five crop-space landmarks predict for the inner face: centre and semi-axes. */
    data class Oval(val cx: Double, val cy: Double, val rx: Double, val ry: Double) {
        val area: Double get() = PI * rx * ry
    }

    fun oval(cropLandmarks: FloatArray): Oval {
        require(cropLandmarks.size == 10)
        val eyeX = (cropLandmarks[0] + cropLandmarks[2]) / 2.0
        val eyeY = (cropLandmarks[1] + cropLandmarks[3]) / 2.0
        val mouthX = (cropLandmarks[6] + cropLandmarks[8]) / 2.0
        val mouthY = (cropLandmarks[7] + cropLandmarks[9]) / 2.0
        val eyeDist = hypot((cropLandmarks[2] - cropLandmarks[0]).toDouble(), (cropLandmarks[3] - cropLandmarks[1]).toDouble())
        val eyeMouth = hypot(mouthX - eyeX, mouthY - eyeY)
        require(eyeDist > 1e-3 && eyeMouth > 1e-3) { "Landmarks give no face size" }
        return Oval(
            cx = (eyeX + mouthX + cropLandmarks[4]) / 3.0,
            cy = (eyeY + mouthY) / 2.0 + 0.05 * eyeMouth,
            rx = 1.05 * eyeDist,
            ry = 0.95 * eyeMouth
        )
    }

    /** Feathered oval: 1 inside, smooth fall-off over [featherFraction] of the radius, 0 outside. */
    fun ovalMask(oval: Oval, size: Int, featherFraction: Double = 0.15): FloatArray {
        require(size > 0 && featherFraction in 0.0..0.9)
        val alpha = FloatArray(size * size)
        val inner = 1.0 - featherFraction
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot((x + 0.5 - oval.cx) / oval.rx, (y + 0.5 - oval.cy) / oval.ry)
            alpha[y * size + x] = when {
                d <= inner -> 1f
                d >= 1.0 -> 0f
                else -> smooth(((1.0 - d) / featherFraction)).toFloat()
            }
        }
        return alpha
    }

    /**
     * Uses the segmenter's face mask when it covers at least [MIN_COVERAGE] of the area the
     * landmarks predict; otherwise the landmark oval, flagged as a fallback. [segmentation]
     * is the face-skin probability per crop pixel, or null when no segmenter is installed.
     */
    fun decide(cropLandmarks: FloatArray, size: Int, segmentation: FloatArray?): MaskDecision {
        val oval = oval(cropLandmarks)
        val fallback = { coverage: Double? -> MaskDecision(ovalMask(oval, size), MaskSource.FALLBACK, coverage) }
        if (segmentation == null) return fallback(null)
        require(segmentation.size == size * size) { "Segmentation does not match the crop" }
        val covered = segmentation.count { it >= 0.5f }
        val coverage = covered / oval.area
        if (coverage < MIN_COVERAGE) return fallback(coverage)
        return MaskDecision(feather(segmentation.map { if (it >= 0.5f) 1f else 0f }.toFloatArray(), size,
            radius = maxOf(1, size / 48)), MaskSource.SEGMENTER, coverage)
    }

    /** Two box-blur passes, which approximate a Gaussian; values stay within 0..1. */
    fun feather(mask: FloatArray, size: Int, radius: Int): FloatArray {
        require(mask.size == size * size && radius >= 0)
        var current = mask.copyOf()
        repeat(2) {
            val horizontal = FloatArray(current.size)
            for (y in 0 until size) for (x in 0 until size) {
                var sum = 0f; var n = 0
                for (k in -radius..radius) { val xx = x + k; if (xx in 0 until size) { sum += current[y * size + xx]; n++ } }
                horizontal[y * size + x] = sum / n
            }
            val vertical = FloatArray(current.size)
            for (y in 0 until size) for (x in 0 until size) {
                var sum = 0f; var n = 0
                for (k in -radius..radius) { val yy = y + k; if (yy in 0 until size) { sum += horizontal[yy * size + x]; n++ } }
                vertical[y * size + x] = sum / n
            }
            current = vertical
        }
        return current
    }

    /**
     * Pastes [swapped] (a size x size ARGB crop) into [region] (source pixels of [rect], ARGB,
     * modified in place) through [mask]. A source pixel whose mask alpha is below
     * [ALPHA_EPSILON] is not written at all, so everything outside the mask stays
     * bit-identical to the source. Source alpha is kept.
     */
    fun pasteBack(
        region: IntArray, rect: PixelRect, toCrop: Affine, swapped: IntArray, mask: FloatArray, size: Int
    ): PasteResult {
        require(region.size == rect.width * rect.height) { "Pixel count does not match the region" }
        require(swapped.size == size * size && mask.size == size * size)
        var changed = 0
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val cx = toCrop.mapX(x + 0.5, y + 0.5) - 0.5
            val cy = toCrop.mapY(x + 0.5, y + 0.5) - 0.5
            val a = sampleMask(mask, size, cx, cy)
            if (a < ALPHA_EPSILON) continue
            val s = FaceAlignWarp.sampleCrop(swapped, size, cx, cy) ?: continue
            val i = (y - rect.top) * rect.width + (x - rect.left)
            val old = region[i]
            val blended = blend(old, s, a)
            if (blended != old) {
                region[i] = blended
                changed++
                if (x < l) l = x; if (y < t) t = y; if (x >= r) r = x + 1; if (y >= b) b = y + 1
            }
        }
        return PasteResult(changed, if (changed == 0) null else PixelRect(l, t, r, b))
    }

    /** Fraction of the crop the mask covers (sum of alpha / pixels). */
    fun share(mask: FloatArray): Double = mask.sum().toDouble() / mask.size

    private fun blend(dst: Int, src: Int, a: Float): Int {
        if (a >= 1f - ALPHA_EPSILON) return (dst and 0xFF000000.toInt()) or (src and 0xFFFFFF)
        fun ch(shift: Int): Int {
            val d = (dst shr shift) and 255; val s = (src shr shift) and 255
            return FaceAlignWarp.round8(d + (s - d) * a.toDouble())
        }
        return (dst and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun sampleMask(mask: FloatArray, size: Int, cx: Double, cy: Double): Float {
        if (cx < -0.5 || cy < -0.5 || cx > size - 0.5 || cy > size - 0.5) return 0f
        val x0 = kotlin.math.floor(cx).toInt(); val y0 = kotlin.math.floor(cy).toInt()
        val fx = (cx - x0).toFloat(); val fy = (cy - y0).toFloat()
        fun at(x: Int, y: Int) = mask[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)]
        return at(x0, y0) * (1 - fx) * (1 - fy) + at(x0 + 1, y0) * fx * (1 - fy) +
            at(x0, y0 + 1) * (1 - fx) * fy + at(x0 + 1, y0 + 1) * fx * fy
    }

    private fun smooth(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
}
