package nl.atlas.gallery.featuregrid

import kotlin.math.ceil

/**
 * Preview decode budget in physical pixels. Keep decoded bitmaps near their actual
 * tile footprint, with a small oversampling margin for visual sharpness, rather
 * than decoding a 900 px square for every 100 px cell in a 150k-photo gallery.
 * Rounding to 32 px buckets preserves request/cache reuse between nearby rows.
 * This does not authorize images: MediaPreview still owns the permission scope.
 */
internal fun gridPreviewRequestPixels(cellWidthPx: Int, cellHeightPx: Int): Int {
    if (cellWidthPx <= 0 || cellHeightPx <= 0) return 400 // unmeasured; conservative
    val longest = maxOf(cellWidthPx, cellHeightPx).toDouble()
    val desired = ceil(longest * 1.5)
    if (desired >= 900.0) return 900
    return (ceil(desired / 32.0).toInt() * 32).coerceIn(96, 900)
}
