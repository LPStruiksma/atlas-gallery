package nl.atlas.gallery.featurecuration.quality

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Host-independent, bounded observations for an ARGB8888 frame. */
data class WholePhotoQuality(
    val blur: BlurSignal?,
    val exposure: ExposureSignal?,
    val sampledWidth: Int,
    val sampledHeight: Int,
    val validSamples: Int
)

data class BlurSignal(val laplacianVariance: Double)
data class ExposureSignal(
    val clippedDarkFraction: Double,
    val clippedLightFraction: Double,
    val p05: Double,
    val p95: Double
) { val spread: Double get() = p95 - p05 }

data class FaceLandmarkObservation(val leftEyeOpen: Float?, val rightEyeOpen: Float?, val smile: Float?)
data class FaceEvidence(val eyeOpenMean: Float?, val smileMean: Float?, val observedFaces: Int)

object PhotoQualitySignals {
    const val MAX_EDGE = 256
    const val MIN_VALID_SAMPLES = 64

    /**
     * Samples an evenly spaced thumbnail without allocating it. [stride] is pixels, not bytes.
     * Pixels with alpha below 128 are masked. Invalid dimensions/array bounds return no signals.
     */
    fun analyseArgb8888(pixels: IntArray, width: Int, height: Int, stride: Int = width): WholePhotoQuality {
        if (width <= 0 || height <= 0 || stride < width || requiredSize(width, height, stride) > pixels.size)
            return WholePhotoQuality(null, null, 0, 0, 0)
        val scale = max(1, max(width, height) / MAX_EDGE + if (max(width, height) % MAX_EDGE == 0) 0 else 1)
        val sw = (width + scale - 1) / scale
        val sh = (height + scale - 1) / scale
        val luminance = DoubleArray(sw * sh) { Double.NaN }
        val histogram = IntArray(256)
        var valid = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            val argb = pixels[min(y * scale, height - 1) * stride + min(x * scale, width - 1)]
            if ((argb ushr 24) < 128) continue
            val l = luminance(argb)
            luminance[y * sw + x] = l
            histogram[(l * 255.0 + .5).toInt().coerceIn(0, 255)]++
            valid++
        }
        if (valid < MIN_VALID_SAMPLES) return WholePhotoQuality(null, null, sw, sh, valid)
        val exposure = ExposureSignal(
            histogram.sliceArray(0..5).sum().toDouble() / valid,
            histogram.sliceArray(250..255).sum().toDouble() / valid,
            percentile(histogram, valid, .05) / 255.0,
            percentile(histogram, valid, .95) / 255.0)
        var count = 0
        var mean = 0.0
        var m2 = 0.0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val c = luminance[i]
            val n = luminance[i - sw]
            val s = luminance[i + sw]
            val w = luminance[i - 1]
            val e = luminance[i + 1]
            if (!c.isFinite() || !n.isFinite() || !s.isFinite() || !w.isFinite() || !e.isFinite()) continue
            val laplacian = n + s + w + e - 4.0 * c
            count++
            val delta = laplacian - mean
            mean += delta / count
            m2 += delta * (laplacian - mean)
        }
        val blur = if (count >= MIN_VALID_SAMPLES) BlurSignal(m2 / count) else null
        return WholePhotoQuality(blur, exposure, sw, sh, valid)
    }

    /** Aggregates authorised detector observations only; absent values remain unknown. */
    fun aggregateFaces(faces: List<FaceLandmarkObservation>): FaceEvidence {
        val eyes = faces.mapNotNull { face ->
            val values = listOfNotNull(face.leftEyeOpen, face.rightEyeOpen)
            if (values.isEmpty()) null else values.average().toFloat()
        }
        val smiles = faces.mapNotNull { it.smile }
        return FaceEvidence(eyes.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            smiles.takeIf { it.isNotEmpty() }?.average()?.toFloat(), faces.size)
    }

    private fun requiredSize(width: Int, height: Int, stride: Int): Long =
        (height - 1L) * stride + width

    private fun luminance(argb: Int): Double {
        fun linear(byte: Int): Double {
            val value = byte / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear((argb ushr 16) and 255) + .7152 * linear((argb ushr 8) and 255) + .0722 * linear(argb and 255)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = max(1, (total * fraction).toInt())
        var sum = 0
        for (value in histogram.indices) { sum += histogram[value]; if (sum >= target) return value }
        return 255
    }
}
