package nl.atlas.gallery

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Non-decoding viewport-to-source plan. Rectangles are [left,right) x [top,bottom). */
internal data class ViewerSourceRect(
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    init { require(left >= 0 && top >= 0 && right > left && bottom > top) }
}

internal data class ViewerTilePlan(
    val visibleSource: ViewerSourceRect,
    val sampleSize: Int,
    val tiles: List<ViewerSourceRect>,
    val maxDecodedBytes: Long,
    val reducedQualityForBudget: Boolean
)

/**
 * Preview-first geometry for a possible Android BitmapRegionDecoder / Telephoto
 * adapter. Coordinates refer to the DECODED/ORIENTED source, never arbitrary
 * raw-EXIF pixel coordinates: a decoder must map rotations separately.
 *
 * The planner NEVER reads files. It rejects impossible/huge metadata, limits
 * the total visible decoded work, and never allocates an original-size bitmap.
 * maxDecodedBytes is an ARGB_8888 tile-byte upper bound, not RSS/GPU memory.
 */
internal fun planViewerTiles(
    sourceWidth: Int, sourceHeight: Int,
    viewportWidth: Int, viewportHeight: Int,
    zoom: Float, translationX: Float, translationY: Float,
    tilePixels: Int = 512, maxTiles: Int = 12,
    maxDecodedBytes: Long = 16L * 1024 * 1024,
    /**
     * The plan for the previous pan/zoom step of the SAME source, if any.
     * When the sample size and tile grid range are unchanged its tile list
     * is reused (no tile allocation); any mismatch simply plans afresh, so a
     * stale or foreign value can never change the result.
     */
    previous: ViewerTilePlan? = null
): ViewerTilePlan? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0 ||
        sourceWidth > 1_000_000 || sourceHeight > 1_000_000 ||
        sourceWidth.toLong() * sourceHeight > 1_000_000_000L ||
        !zoom.isFinite() || zoom < 1f || zoom > 32f ||
        !translationX.isFinite() || !translationY.isFinite() ||
        tilePixels !in 128..1024 || maxTiles !in 1..32 ||
        maxDecodedBytes !in (4L * tilePixels * tilePixels)..(64L * 1024 * 1024)
    ) return null

    val fit = min(viewportWidth.toDouble() / sourceWidth,
        viewportHeight.toDouble() / sourceHeight)
    val scale = fit * zoom
    if (!scale.isFinite() || scale <= 0.0) return null
    val cx = sourceWidth / 2.0 - translationX / scale
    val cy = sourceHeight / 2.0 - translationY / scale
    val halfW = viewportWidth / (2.0 * scale)
    val halfH = viewportHeight / (2.0 * scale)
    val left = floor(cx - halfW).coerceIn(0.0, sourceWidth.toDouble()).toInt()
    val top = floor(cy - halfH).coerceIn(0.0, sourceHeight.toDouble()).toInt()
    val right = ceil(cx + halfW).coerceIn(0.0, sourceWidth.toDouble()).toInt()
    val bottom = ceil(cy + halfH).coerceIn(0.0, sourceHeight.toDouble()).toInt()
    if (left >= right || top >= bottom) return null
    val visible = ViewerSourceRect(left, top, right, bottom)

    var sample = 1
    // The largest power of two that still supplies >= one decoded pixel per
    // device pixel wherever possible; increasing beyond this is a deliberate
    // quality downgrade only when bounded-memory admission requires it.
    while (sample < (1 shl 20) && sample.toDouble() * 2.0 <= 1.0 / scale)
        sample *= 2
    val idealSample = sample
    while (true) {
        val span = tilePixels.toLong() * sample
        val x0 = left / span
        val x1 = (right.toLong() - 1) / span
        val y0 = top / span
        val y1 = (bottom.toLong() - 1) / span
        val tileCount = (x1 - x0 + 1) * (y1 - y0 + 1)
        // Exact allocation upper bound, including partially clipped edge tiles.
        // Ignore the source pixels outside each tile's visible intersection.
        // Tile widths depend only on the column and heights only on the row,
        // so the sum over the grid is (sum of widths) x (sum of heights):
        // O(columns + rows) arithmetic, and nothing is allocated for a
        // sample level that the budget then rejects (PERF-28, #329).
        if (tileCount <= maxTiles) {
            var decodedWidths = 0L
            for (x in x0..x1) {
                val sx = x * span
                decodedWidths += (min(sourceWidth.toLong(), sx + span) - sx + sample - 1) / sample
            }
            var decodedHeights = 0L
            for (y in y0..y1) {
                val sy = y * span
                decodedHeights += (min(sourceHeight.toLong(), sy + span) - sy + sample - 1) / sample
            }
            val budget = decodedWidths * decodedHeights * 4L
            if (budget <= maxDecodedBytes) {
                val reduced = sample > idealSample
                if (previous != null && previous.sampleSize == sample &&
                    previous.maxDecodedBytes == budget &&
                    sameTileGrid(previous.tiles, tileCount, span, x0, y0, x1, y1,
                        sourceWidth, sourceHeight)) {
                    return ViewerTilePlan(visible, sample, previous.tiles, budget, reduced)
                }
                val tiles = ArrayList<ViewerSourceRect>(tileCount.toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val sx = x * span
                    val sy = y * span
                    tiles += ViewerSourceRect(sx.toInt(), sy.toInt(),
                        min(sourceWidth.toLong(), sx + span).toInt(),
                        min(sourceHeight.toLong(), sy + span).toInt())
                }
                return ViewerTilePlan(visible, sample, tiles, budget, reduced)
            }
        }
        if (sample >= (1 shl 20)) return null
        sample *= 2
    }
}

/**
 * A row-major tile grid is fully determined by its span, index range and
 * source size. Only the last column/row can be clamped by the source edge, so
 * matching the count plus the first and last rectangle proves every tile
 * matches. O(1).
 */
private fun sameTileGrid(
    tiles: List<ViewerSourceRect>, count: Long, span: Long,
    x0: Long, y0: Long, x1: Long, y1: Long, sourceWidth: Int, sourceHeight: Int
): Boolean {
    if (tiles.size.toLong() != count || tiles.isEmpty()) return false
    val first = tiles[0]
    val last = tiles[tiles.size - 1]
    return first.left.toLong() == x0 * span && first.top.toLong() == y0 * span &&
        first.right.toLong() == min(sourceWidth.toLong(), x0 * span + span) &&
        first.bottom.toLong() == min(sourceHeight.toLong(), y0 * span + span) &&
        last.left.toLong() == x1 * span && last.top.toLong() == y1 * span &&
        last.right.toLong() == min(sourceWidth.toLong(), x1 * span + span) &&
        last.bottom.toLong() == min(sourceHeight.toLong(), y1 * span + span)
}
