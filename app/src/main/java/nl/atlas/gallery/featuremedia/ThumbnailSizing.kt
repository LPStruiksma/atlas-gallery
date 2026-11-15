package nl.atlas.gallery.featuremedia

import kotlin.math.ceil

/**
 * Bounded, bucketed grid decode request size. The sharp layer is deliberately
 * oversampled by 1.5x so a settled cell never loses detail compared with the
 * established grid budget. Nearby pixel sizes share a 32-pixel Coil cache key.
 *
 * This policy describes a planned cell; URI/revision/permission identity stays
 * with the existing mediaPreviewRequest and presentation cache key.
 */
internal fun thumbnailDecodePixels(cellWidthPx: Int, cellHeightPx: Int): Int {
    if (cellWidthPx <= 0 || cellHeightPx <= 0) return 400
    val longest = maxOf(cellWidthPx, cellHeightPx).toDouble()
    val desired = ceil(longest * 1.5)
    if (desired >= 900.0) return 900
    return (ceil(desired / 32.0).toInt() * 32).coerceIn(96, 900)
}
