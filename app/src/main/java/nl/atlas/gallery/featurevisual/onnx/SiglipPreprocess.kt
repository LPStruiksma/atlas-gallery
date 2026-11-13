package nl.atlas.gallery.featurevisual.onnx

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel preprocessing for the SigLIP 2 B/16 224 image embedder, per the VS-S0 contract
 * (`docs/visual-search/VS_S0_MODEL_SPIKE.md`, "Contract for VS-S1"):
 * oriented RGB → 224×224 bilinear resize without cropping (aspect ratio is not kept)
 * → `x/255`, then `(x − 0.5) / 0.5` → NCHW float32.
 *
 * The resize reproduces Pillow's `Image.resize(size, Image.BILINEAR)` bit for bit: a separable
 * triangle filter whose support widens with the downscale factor (so large photos are
 * antialiased, not point-sampled), horizontal pass first, 22-bit fixed-point weights and
 * rounding to 8 bits between the passes. That is the resampler the spike measured with and
 * the one `export_onnx.py` uses for the golden vectors, so on-device vectors match host ones.
 *
 * Pure Kotlin on ARGB `IntArray`s, so it runs in JVM tests. Alpha is dropped, as Pillow's
 * `convert("RGB")` does.
 */
object SiglipPreprocess {
    const val INPUT_SIZE = 224

    private const val PRECISION_BITS = 32 - 8 - 2
    private const val HALF = 1 shl (PRECISION_BITS - 1)

    /** Full preprocessing of one oriented ARGB frame into a fresh `[3, 224, 224]` NCHW buffer. */
    fun preprocess(argb: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        preprocessInto(argb, width, height, out)
        return out
    }

    /** As [preprocess], writing into [out] (length `3 * 224 * 224`). */
    fun preprocessInto(argb: IntArray, width: Int, height: Int, out: FloatArray) {
        require(out.size == 3 * INPUT_SIZE * INPUT_SIZE) { "Output holds ${out.size} floats, expected ${3 * INPUT_SIZE * INPUT_SIZE}" }
        val resized = resizeBilinear(argb, width, height, INPUT_SIZE, INPUT_SIZE)
        toNchw(resized, out)
    }

    /** `(v/255 − 0.5) / 0.5` per channel, planar R, G, B. [rgb] is ARGB, alpha ignored. */
    fun toNchw(rgb: IntArray, out: FloatArray) {
        val plane = rgb.size
        require(out.size == 3 * plane) { "Output holds ${out.size} floats, expected ${3 * plane}" }
        for (i in 0 until plane) {
            val p = rgb[i]
            out[i] = normalize((p shr 16) and 0xFF)
            out[plane + i] = normalize((p shr 8) and 0xFF)
            out[2 * plane + i] = normalize(p and 0xFF)
        }
    }

    /** One channel value 0..255 to the model's [-1, 1] range. */
    fun normalize(v: Int): Float = (v / 255f - 0.5f) / 0.5f

    /**
     * Pillow-exact bilinear resize of an ARGB image to [outW]×[outH]; the result is opaque ARGB.
     * The aspect ratio is not kept (SigLIP "squash" resize).
     */
    fun resizeBilinear(argb: IntArray, width: Int, height: Int, outW: Int, outH: Int): IntArray {
        require(width > 0 && height > 0 && outW > 0 && outH > 0) { "Empty image ${width}x$height -> ${outW}x$outH" }
        require(argb.size >= width * height) { "Pixel buffer holds ${argb.size}, expected ${width * height}" }
        // Channels as bytes in R, G, B order.
        var w = width
        var h = height
        var chans = Array(3) { c ->
            val shift = 16 - 8 * c
            IntArray(w * h) { (argb[it] shr shift) and 0xFF }
        }
        if (outW != w) {
            val k = Coefficients.of(w, outW)
            chans = Array(3) { c -> horizontal(chans[c], w, h, outW, k) }
            w = outW
        }
        if (outH != h) {
            val k = Coefficients.of(h, outH)
            chans = Array(3) { c -> vertical(chans[c], w, h, outH, k) }
            h = outH
        }
        return IntArray(w * h) { (0xFF shl 24) or (chans[0][it] shl 16) or (chans[1][it] shl 8) or chans[2][it] }
    }

    private fun horizontal(src: IntArray, w: Int, h: Int, outW: Int, k: Coefficients): IntArray {
        val dst = IntArray(outW * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until outW) {
                val first = k.bounds[2 * x]
                val count = k.bounds[2 * x + 1]
                val base = x * k.size
                var ss = HALF.toLong()
                for (i in 0 until count) ss += src[row + first + i].toLong() * k.weights[base + i]
                dst[y * outW + x] = clip8(ss)
            }
        }
        return dst
    }

    private fun vertical(src: IntArray, w: Int, h: Int, outH: Int, k: Coefficients): IntArray {
        val dst = IntArray(w * outH)
        for (y in 0 until outH) {
            val first = k.bounds[2 * y]
            val count = k.bounds[2 * y + 1]
            val base = y * k.size
            for (x in 0 until w) {
                var ss = HALF.toLong()
                for (i in 0 until count) ss += src[(first + i) * w + x].toLong() * k.weights[base + i]
                dst[y * w + x] = clip8(ss)
            }
        }
        return dst
    }

    private fun clip8(ss: Long): Int {
        val v = ss shr PRECISION_BITS
        return if (v <= 0) 0 else if (v >= 255) 255 else v.toInt()
    }

    /** Pillow `precompute_coeffs` + `normalize_coeffs_8bpc` for the bilinear (triangle) filter. */
    private class Coefficients(val size: Int, val bounds: IntArray, val weights: IntArray) {
        companion object {
            fun of(inSize: Int, outSize: Int): Coefficients {
                val scale = inSize.toDouble() / outSize
                val filterScale = max(scale, 1.0)
                val support = 1.0 * filterScale
                val size = ceil(support).toInt() * 2 + 1
                val bounds = IntArray(outSize * 2)
                val weights = IntArray(outSize * size)
                val tmp = DoubleArray(size)
                val ss = 1.0 / filterScale
                for (xx in 0 until outSize) {
                    val center = (xx + 0.5) * scale
                    // C's (int) cast truncates toward zero, as Kotlin's toInt() does.
                    val xmin = max((center - support + 0.5).toInt(), 0)
                    val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
                    var ww = 0.0
                    for (x in 0 until xmax) {
                        val t = (x + xmin - center + 0.5) * ss
                        val wgt = triangle(t)
                        tmp[x] = wgt
                        ww += wgt
                    }
                    for (x in 0 until xmax) {
                        val v = if (ww != 0.0) tmp[x] / ww else tmp[x]
                        weights[xx * size + x] = if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt()
                            else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
                    }
                    bounds[2 * xx] = xmin
                    bounds[2 * xx + 1] = xmax
                }
                return Coefficients(size, bounds, weights)
            }

            private fun triangle(x: Double): Double {
                val a = if (x < 0) -x else x
                return if (a < 1.0) 1.0 - a else 0.0
            }
        }
    }
}
