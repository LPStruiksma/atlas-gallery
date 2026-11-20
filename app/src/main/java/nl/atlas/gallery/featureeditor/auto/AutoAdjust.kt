package nl.atlas.gallery.featureeditor.auto

import kotlin.math.abs
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation

/** Immutable 256-bin RGB histograms. Arrays passed by callers are defensively copied. */
class ToneStatistics(red: LongArray, green: LongArray, blue: LongArray) {
    internal val redBins: LongArray = red.copyOf()
    internal val greenBins: LongArray = green.copyOf()
    internal val blueBins: LongArray = blue.copyOf()
    val red: LongArray get() = redBins.copyOf()
    val green: LongArray get() = greenBins.copyOf()
    val blue: LongArray get() = blueBins.copyOf()
    val pixelCount: Long

    init {
        require(redBins.size == 256 && greenBins.size == 256 && blueBins.size == 256) {
            "RGB histograms must each contain 256 bins"
        }
        require(redBins.all { it >= 0 } && greenBins.all { it >= 0 } && blueBins.all { it >= 0 }) {
            "Histogram counts must not be negative"
        }
        val counts = listOf(redBins.sum(), greenBins.sum(), blueBins.sum())
        require(counts[0] > 0 && counts.distinct().size == 1) { "RGB histograms must have the same non-zero count" }
        pixelCount = counts[0]
    }

    override fun equals(other: Any?): Boolean = other is ToneStatistics &&
        redBins.contentEquals(other.redBins) && greenBins.contentEquals(other.greenBins) && blueBins.contentEquals(other.blueBins)

    override fun hashCode(): Int = 31 * (31 * redBins.contentHashCode() + greenBins.contentHashCode()) + blueBins.contentHashCode()

    companion object {
        /** Packed row-major RGB bytes (R,G,B for every pixel); channels are unsigned. */
        fun fromRgb(rgb: ByteArray, width: Int, height: Int): ToneStatistics {
            require(width > 0 && height > 0) { "Image dimensions must be positive" }
            require(width.toLong() * height * 3 == rgb.size.toLong()) { "RGB size does not match dimensions" }
            val channels = Array(3) { LongArray(256) }
            for (i in rgb.indices) channels[i % 3][rgb[i].toInt() and 0xff]++
            return ToneStatistics(channels[0], channels[1], channels[2])
        }
    }
}

/** Histogram-only, deterministic automatic tone and white-balance suggestions. */
object AutoAdjust {
    fun suggest(stats: ToneStatistics): List<EditOperation.Adjust> {
        val combined = LongArray(256) { stats.redBins[it] + stats.greenBins[it] + stats.blueBins[it] }
        val mean = mean(combined)
        val low = percentile(combined, 0.05)
        val high = percentile(combined, 0.95)
        val redMean = mean(stats.redBins)
        val greenMean = mean(stats.greenBins)
        val blueMean = mean(stats.blueBins)

        val exposure = ((0.5 - mean) * 1.6).deadZone(0.015)
        val contrast = ((0.90 - (high - low)) * 0.9).deadZone(0.02)
        val shadows = ((0.05 - low) * 1.25).deadZone(0.025)
        val highlights = ((0.95 - high) * 1.25).deadZone(0.025)
        val temperature = ((blueMean - redMean) * 1.5).deadZone(0.012)
        val tint = (((redMean + blueMean) / 2.0 - greenMean) * 1.5).deadZone(0.012)
        return listOf(
            operation(AdjustParam.EXPOSURE, exposure),
            operation(AdjustParam.CONTRAST, contrast),
            operation(AdjustParam.HIGHLIGHTS, highlights),
            operation(AdjustParam.SHADOWS, shadows),
            operation(AdjustParam.TEMPERATURE, temperature),
            operation(AdjustParam.TINT, tint),
        )
    }

    private fun operation(param: AdjustParam, value: Double) = EditOperation.Adjust(
        id = "auto-${param.key}", param = param, value = value.coerceIn(param.min, param.max),
    )

    private fun mean(histogram: LongArray): Double {
        val count = histogram.sum()
        return histogram.indices.sumOf { it.toDouble() * histogram[it] } / count / 255.0
    }

    private fun percentile(histogram: LongArray, fraction: Double): Double {
        val target = (histogram.sum() * fraction).toLong().coerceAtLeast(1)
        var accumulated = 0L
        for (i in histogram.indices) {
            accumulated += histogram[i]
            if (accumulated >= target) return i / 255.0
        }
        return 1.0
    }

    private fun Double.deadZone(radius: Double): Double = if (abs(this) < radius) 0.0 else this
}
