package nl.atlas.gallery.featureidentity.local

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * IDS-9 (#475): Lab colour match of the swapped crop to the target crop, on by
 * default (BENCH.md: it removes much of the colour jump at no identity cost).
 * Reinhard-style transfer of per-channel mean and spread in CIE Lab (D65),
 * measured only where the mask is mostly on. Pure JVM.
 */
object FaceColourMatch {
    /** Pixels with mask alpha at or above this count towards the statistics. */
    const val STATS_ALPHA = 0.5f
    /** The spread ratio is clamped so a flat region cannot blow up contrast. */
    const val MIN_SPREAD_RATIO = 0.5
    const val MAX_SPREAD_RATIO = 2.0

    data class Stats(val mean: DoubleArray, val spread: DoubleArray, val count: Int)

    fun stats(argb: IntArray, mask: FloatArray): Stats {
        require(argb.size == mask.size)
        val sum = DoubleArray(3); val sq = DoubleArray(3); var n = 0
        val lab = DoubleArray(3)
        for (i in argb.indices) {
            if (mask[i] < STATS_ALPHA) continue
            toLab(argb[i], lab)
            for (c in 0 until 3) { sum[c] += lab[c]; sq[c] += lab[c] * lab[c] }
            n++
        }
        if (n == 0) return Stats(DoubleArray(3), DoubleArray(3), 0)
        val mean = DoubleArray(3) { sum[it] / n }
        val spread = DoubleArray(3) { sqrt((sq[it] / n - mean[it] * mean[it]).coerceAtLeast(0.0)) }
        return Stats(mean, spread, n)
    }

    /**
     * Returns [swapped] recoloured so its masked Lab statistics match [target]'s.
     * Unchanged when either side has no masked pixels. Alpha is kept.
     */
    fun match(swapped: IntArray, target: IntArray, mask: FloatArray): IntArray {
        require(swapped.size == target.size && target.size == mask.size)
        val s = stats(swapped, mask)
        val t = stats(target, mask)
        if (s.count == 0 || t.count == 0) return swapped.copyOf()
        val ratio = DoubleArray(3) {
            if (s.spread[it] < 1e-6) 1.0 else (t.spread[it] / s.spread[it]).coerceIn(MIN_SPREAD_RATIO, MAX_SPREAD_RATIO)
        }
        val lab = DoubleArray(3)
        return IntArray(swapped.size) { i ->
            toLab(swapped[i], lab)
            for (c in 0 until 3) lab[c] = (lab[c] - s.mean[c]) * ratio[c] + t.mean[c]
            (swapped[i] and 0xFF000000.toInt()) or fromLab(lab)
        }
    }

    // ---- sRGB <-> CIE Lab (D65) ----

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    fun toLab(argb: Int, out: DoubleArray) {
        val r = linear(((argb shr 16) and 255) / 255.0)
        val g = linear(((argb shr 8) and 255) / 255.0)
        val b = linear((argb and 255) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = f(x); val fy = f(y); val fz = f(z)
        out[0] = 116 * fy - 16
        out[1] = 500 * (fx - fy)
        out[2] = 200 * (fy - fz)
    }

    /** Lab to opaque RGB bits (no alpha), clamped to the sRGB gamut. */
    fun fromLab(lab: DoubleArray): Int {
        val fy = (lab[0] + 16) / 116
        val fx = fy + lab[1] / 500
        val fz = fy - lab[2] / 200
        val x = finv(fx) * XN; val y = finv(fy) * YN; val z = finv(fz) * ZN
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }

    private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun finv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389
    private fun to8(c: Double) = FaceAlignWarp.round8(gamma(c.coerceIn(0.0, 1.0)) * 255)
}
