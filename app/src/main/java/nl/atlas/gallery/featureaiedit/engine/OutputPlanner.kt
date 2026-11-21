package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Output size for one edit (#431 rev 4): keep the source's aspect (clamped into the provider's
 * documented aspect range) at the largest size the provider offers, but never above the larger of
 * the source's own pixel count and 1 MP, so a small photo isn't billed at a 4K tier.
 * Pure: no Android, no provider name.
 */
object OutputPlanner {
    private const val FLOOR_PIXELS = 1_048_576L

    fun plan(sourceWidth: Int, sourceHeight: Int, caps: ProviderCapabilities): OutputSpec? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || caps.maxOutputPixels < 1) return null
        var aspect = sourceWidth.toDouble() / sourceHeight
        caps.aspectRange?.let { aspect = aspect.coerceIn(it.min, it.max) }
        val wanted = minOf(caps.maxOutputPixels, max(sourceWidth.toLong() * sourceHeight, FLOOR_PIXELS))
        var height = floor(sqrt(wanted / aspect)).toInt().coerceAtLeast(1)
        var width = floor(height * aspect).toInt().coerceAtLeast(1)
        while (width.toLong() * height > caps.maxOutputPixels) {
            if (width >= height && width > 1) width-- else if (height > 1) height-- else return null
        }
        if (caps.aspectRange != null && !caps.aspectRange.supports(width, height)) return null
        val format = when {
            ImageFormat.PNG in caps.outputFormats -> ImageFormat.PNG
            else -> caps.outputFormats.firstOrNull() ?: return null
        }
        return OutputSpec(width, height, format, candidates = 1)
    }
}
