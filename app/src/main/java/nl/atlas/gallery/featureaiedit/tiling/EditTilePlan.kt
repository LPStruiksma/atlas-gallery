package nl.atlas.gallery.featureaiedit.tiling

/**
 * A half-open rectangle [left, right) x [top, bottom) in integer pixel coordinates.
 * All coordinates are non-negative; the exclusive ends may equal Int.MAX_VALUE.
 */
data class EditTileRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right >= left && bottom >= top) {
            "Invalid tile rectangle"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * One tile in row-major order.
 *
 * [source] is the region to crop from the original full-resolution image, in global
 * coordinates. An edited tile must preserve this region's pixel dimensions before
 * [crop] is applied (it is not a model-output resize contract).
 *
 * [crop] is the local, half-open window to take from the edited tile.
 * [destination] is the corresponding window in the full-size stitched output.
 * Thus destination = source.origin + crop, with identical crop/destination sizes.
 *
 * Neighboring source regions overlap to give the model context, while destinations
 * are disjoint: the seam is the floor midpoint of each neighboring overlap.
 * This is a deterministic hard seam, not a quality-tested feather or blend.
 */
data class EditTile(
    val row: Int,
    val column: Int,
    val source: EditTileRect,
    val crop: EditTileRect,
    val destination: EditTileRect,
)

/**
 * Geometry-only plan. [tiles] are row-major and their destinations form an exact
 * partition of [imageWidth] x [imageHeight], with no gaps or double writes.
 *
 * If [tiled] is false, the single tile covers the original image. When tiling is
 * explicitly disabled, this tile may exceed maxTileWidth / maxTileHeight: callers
 * must only use that opt-out with a provider that supports native resolution.
 */
data class EditTilePlan(
    val imageWidth: Int,
    val imageHeight: Int,
    val columnCount: Int,
    val rowCount: Int,
    val tiled: Boolean,
    val tiles: List<EditTile>,
)

/**
 * Pure, bounded high-resolution edit geometry. No image pixels, Android APIs,
 * provider calls or implicit network work are involved.
 *
 * Each source rectangle is at most maxTileWidth x maxTileHeight when enabled.
 * Overlap on each axis is in pixels and must be smaller than that axis's maximum,
 * so every successive source makes forward progress. The default hard cap limits
 * allocations even for near-Int.MAX_VALUE synthetic image dimensions.
 */
object EditTilePlanner {
    const val MAX_TILES: Int = 4096

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        maxTileWidth: Int,
        maxTileHeight: Int,
        overlapX: Int = 0,
        overlapY: Int = 0,
        enabled: Boolean = true,
        maxTiles: Int = MAX_TILES,
    ): EditTilePlan {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(maxTileWidth > 0 && maxTileHeight > 0) { "Tile limits must be positive" }
        require(overlapX >= 0 && overlapX < maxTileWidth) { "Invalid horizontal overlap" }
        require(overlapY >= 0 && overlapY < maxTileHeight) { "Invalid vertical overlap" }
        require(maxTiles in 1..MAX_TILES) { "Tile count limit out of range" }

        val columns = if (enabled) axisCount(imageWidth, maxTileWidth, overlapX) else 1L
        val rows = if (enabled) axisCount(imageHeight, maxTileHeight, overlapY) else 1L
        // Long counts and product are safe for positive Int image dimensions,
        // but no large list is allocated before the cap has been checked.
        require(columns * rows <= maxTiles.toLong()) { "Tile plan exceeds count limit" }

        val x = if (enabled) axis(imageWidth, maxTileWidth, overlapX, columns.toInt())
                else listOf(AxisSegment(0, imageWidth, 0, imageWidth))
        val y = if (enabled) axis(imageHeight, maxTileHeight, overlapY, rows.toInt())
                else listOf(AxisSegment(0, imageHeight, 0, imageHeight))
        val tiles = ArrayList<EditTile>((columns * rows).toInt())
        for ((row, vertical) in y.withIndex()) {
            for ((column, horizontal) in x.withIndex()) {
                tiles.add(
                    EditTile(
                        row = row,
                        column = column,
                        source = EditTileRect(
                            horizontal.start, vertical.start,
                            horizontal.end, vertical.end,
                        ),
                        crop = EditTileRect(
                            horizontal.ownedStart - horizontal.start,
                            vertical.ownedStart - vertical.start,
                            horizontal.ownedEnd - horizontal.start,
                            vertical.ownedEnd - vertical.start,
                        ),
                        destination = EditTileRect(
                            horizontal.ownedStart, vertical.ownedStart,
                            horizontal.ownedEnd, vertical.ownedEnd,
                        ),
                    )
                )
            }
        }
        return EditTilePlan(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            columnCount = x.size,
            rowCount = y.size,
            tiled = enabled && (columns > 1L || rows > 1L),
            tiles = tiles,
        )
    }

    private data class AxisSegment(
        val start: Int,
        val end: Int,
        val ownedStart: Int,
        val ownedEnd: Int,
    )

    /** Number of tiles when starts advance by maxSide - overlap. */
    private fun axisCount(length: Int, maxSide: Int, overlap: Int): Long {
        if (length <= maxSide) return 1L
        val step = (maxSide - overlap).toLong()
        val excess = length.toLong() - maxSide.toLong()
        return 1L + (excess + step - 1L) / step
    }

    /**
     * Ownership boundaries are shared midpoint cuts of overlapping input windows.
     * A floor tie-break (for odd overlaps) makes the cut unambiguous.
     * All additions/multiplications use Long until bounded to image coordinates.
     */
    private fun axis(length: Int, maxSide: Int, overlap: Int, count: Int): List<AxisSegment> {
        val step = (maxSide - overlap).toLong()
        val limit = length.toLong()
        val max = maxSide.toLong()
        return List(count) { index ->
            val start = index.toLong() * step
            val end = minOf(limit, start + max)
            val ownedStart = if (index == 0) 0L else {
                val prevStart = (index - 1L) * step
                val prevEnd = minOf(limit, prevStart + max)
                start + (prevEnd - start) / 2L
            }
            val ownedEnd = if (index == count - 1) limit else {
                val nextStart = (index + 1L) * step
                nextStart + (end - nextStart) / 2L
            }
            AxisSegment(start.toInt(), end.toInt(), ownedStart.toInt(), ownedEnd.toInt())
        }
    }
}
