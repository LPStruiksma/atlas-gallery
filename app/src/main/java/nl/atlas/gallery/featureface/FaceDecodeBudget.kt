package nl.atlas.gallery.featureface

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Pure predecode plan for ImageDecoder.setTargetSize, never decoded image evidence.
 * The caller must still verify decoded bitmap bounds, EXIF-oriented dimensions and
 * actual allocation; decoder, ML Kit and non-bitmap memory are NOT accounted for.
 */
data class FaceDecodePlan(val targetWidth: Int, val targetHeight: Int, val estimatedBitmapBytes: Long) {
    init {
        require(targetWidth > 0 && targetHeight > 0)
        require(estimatedBitmapBytes >= 0)
    }
}

object FaceDecodeBudget {
    const val DEFAULT_LONG_EDGE = FaceDiscoveryContract.DEFAULT_DECODE_LONG_EDGE_PX
    const val DEFAULT_BITMAP_BUDGET_BYTES = 8L * 1024 * 1024

    /** Original dimensions from decoded image HEADER, before allocating pixels.
     * 64-bit arithmetic and Double ratio avoid overflow on forged 2-billion-pixel headers.
     * Do not upscale tiny images; they may legitimately contain no discoverable faces.
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        maxLongEdge: Int = DEFAULT_LONG_EDGE,
        maxBitmapBytes: Long = DEFAULT_BITMAP_BUDGET_BYTES,
        estimatedBytesPerPixel: Int = 4
    ): FaceDecodePlan {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(maxLongEdge in 480..2048)
        require(estimatedBytesPerPixel in 1..8)
        require(maxBitmapBytes in 1024L..(128L * 1024 * 1024))
        val originalPixels = sourceWidth.toDouble() * sourceHeight.toDouble()
        val maxScaleByEdge = maxLongEdge.toDouble() / maxOf(sourceWidth, sourceHeight)
        val maxScaleByMemory = sqrt(maxBitmapBytes.toDouble() / (originalPixels * estimatedBytesPerPixel))
        val scale = min(1.0, min(maxScaleByEdge, maxScaleByMemory))
        var width = maxOf(1, floor(sourceWidth.toDouble() * scale).toInt())
        var height = maxOf(1, floor(sourceHeight.toDouble() * scale).toInt())
        // If extreme aspect ratios round the short edge to 1 pixel, output remains bounded.
        width = min(width, maxLongEdge)
        height = min(height, maxLongEdge)
        return FaceDecodePlan(width, height,
            width.toLong() * height.toLong() * estimatedBytesPerPixel)
    }
}
