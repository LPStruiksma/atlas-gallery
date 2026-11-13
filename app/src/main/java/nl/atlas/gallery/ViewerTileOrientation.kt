package nl.atlas.gallery

/**
 * Exact EXIF-orientation geometry between BitmapRegionDecoder's RAW pixels and
 * the image's upright on-screen pixels. All rectangles are half-open.
 *
 * Rotation means raw -> upright, clockwise degrees, then optional horizontal
 * mirroring in upright coordinates. The eight EXIF orientations can be mapped
 * to 0/90/180/270 plus this mirror bit.
 *
 * This is geometry only: production rendering must rotate/mirror each decoded
 * tile's pixels (and keep the sampled draw destination consistent) before
 * using an upright tile plan. It must not blit a raw tile directly.
 */
internal data class ViewerSourceOrientation(
    val rawWidth: Int,
    val rawHeight: Int,
    val clockwiseDegrees: Int,
    val mirrorHorizontally: Boolean = false
) {
    init {
        require(rawWidth > 0 && rawHeight > 0)
        require(clockwiseDegrees == 0 || clockwiseDegrees == 90 ||
            clockwiseDegrees == 180 || clockwiseDegrees == 270)
    }
    val uprightWidth: Int
        get() = if (clockwiseDegrees == 90 || clockwiseDegrees == 270) rawHeight else rawWidth
    val uprightHeight: Int
        get() = if (clockwiseDegrees == 90 || clockwiseDegrees == 270) rawWidth else rawHeight
}

/**
 * Full EXIF orientation 1..8. Unknown/malformed tags have no trusted
 * transform; caller should use its safe non-tiled Coil fallback instead.
 */
internal fun viewerOrientationFromExif(
    rawWidth: Int, rawHeight: Int, exifOrientation: Int
): ViewerSourceOrientation? {
    if (rawWidth <= 0 || rawHeight <= 0) return null
    val (degrees, mirrored) = when (exifOrientation) {
        1 -> 0 to false
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 90 to true
        6 -> 90 to false
        7 -> 270 to true
        8 -> 270 to false
        else -> return null
    }
    return ViewerSourceOrientation(rawWidth, rawHeight, degrees, mirrored)
}

/** Decode-only raw bounds for one upright tile; returns null on invalid input. */
internal fun viewerRawRegionForUprightTile(
    rect: ViewerSourceRect, orientation: ViewerSourceOrientation
): ViewerSourceRect? {
    val w = orientation.uprightWidth
    val h = orientation.uprightHeight
    if (rect.right > w || rect.bottom > h) return null
    val left = if (orientation.mirrorHorizontally) w - rect.right else rect.left
    val right = if (orientation.mirrorHorizontally) w - rect.left else rect.right
    val top = rect.top
    val bottom = rect.bottom
    return when (orientation.clockwiseDegrees) {
        0 -> ViewerSourceRect(left, top, right, bottom)
        90 -> ViewerSourceRect(top, orientation.rawHeight - right,
            bottom, orientation.rawHeight - left)
        180 -> ViewerSourceRect(orientation.rawWidth - right,
            orientation.rawHeight - bottom,
            orientation.rawWidth - left, orientation.rawHeight - top)
        270 -> ViewerSourceRect(orientation.rawWidth - bottom, left,
            orientation.rawWidth - top, right)
        else -> null
    }
}

/** Independent forward raw -> upright geometry for property/roundtrip checks. */
internal fun viewerUprightRegionForRawTile(
    rect: ViewerSourceRect, orientation: ViewerSourceOrientation
): ViewerSourceRect? {
    if (rect.right > orientation.rawWidth || rect.bottom > orientation.rawHeight)
        return null
    val unmirrored = when (orientation.clockwiseDegrees) {
        0 -> rect
        90 -> ViewerSourceRect(orientation.rawHeight - rect.bottom, rect.left,
            orientation.rawHeight - rect.top, rect.right)
        180 -> ViewerSourceRect(orientation.rawWidth - rect.right,
            orientation.rawHeight - rect.bottom,
            orientation.rawWidth - rect.left,
            orientation.rawHeight - rect.top)
        270 -> ViewerSourceRect(rect.top, orientation.rawWidth - rect.right,
            rect.bottom, orientation.rawWidth - rect.left)
        else -> return null
    }
    return if (orientation.mirrorHorizontally) ViewerSourceRect(
        orientation.uprightWidth - unmirrored.right, unmirrored.top,
        orientation.uprightWidth - unmirrored.left, unmirrored.bottom
    ) else unmirrored
}

/**
 * Paired upright/raw region plan. When a rotated/mirrored tile needs a second
 * Bitmap for its upright draw surface, conservatively reserve twice the
 * decoded ARGB bytes. This estimate covers tile buffers, NOT JVM overhead,
 * native decoder working memory, file cache, GPU copies or retained previews.
 */
internal data class ViewerOrientedTilePlan(
    val upright: ViewerTilePlan,
    val rawTiles: List<ViewerSourceRect>,
    val estimatedPeakTileBytes: Long
)

internal fun planViewerOrientedTiles(
    orientation: ViewerSourceOrientation,
    viewportWidth: Int, viewportHeight: Int,
    zoom: Float, translationX: Float, translationY: Float,
    totalTileBufferBudgetBytes: Long = 16L * 1024 * 1024
): ViewerOrientedTilePlan? {
    val requiresCopy = orientation.clockwiseDegrees != 0 ||
        orientation.mirrorHorizontally
    if (totalTileBufferBudgetBytes !in
        (2L * 1024 * 1024)..(64L * 1024 * 1024)) return null
    val multiplier = if (requiresCopy) 2 else 1
    val uprightBudget = totalTileBufferBudgetBytes / multiplier
    val plan = planViewerTiles(
        sourceWidth = orientation.uprightWidth,
        sourceHeight = orientation.uprightHeight,
        viewportWidth = viewportWidth, viewportHeight = viewportHeight,
        zoom = zoom, translationX = translationX, translationY = translationY,
        maxDecodedBytes = uprightBudget
    ) ?: return null
    val rawTiles = plan.tiles.map { tile ->
        viewerRawRegionForUprightTile(tile, orientation) ?: return null
    }
    val estimatedPeak = plan.maxDecodedBytes * multiplier
    if (estimatedPeak > totalTileBufferBudgetBytes) return null
    return ViewerOrientedTilePlan(plan, rawTiles, estimatedPeak)
}
