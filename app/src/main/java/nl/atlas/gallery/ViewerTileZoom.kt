package nl.atlas.gallery

import kotlin.math.max
import kotlin.math.min

/**
 * PERF-32 (#334): pure policy that ties the live viewer pose to the tile
 * planner. The viewer keeps its 2400 px Coil decode as the base layer and
 * overlays region-decoded tiles only where that base would visibly soften.
 * No decoding, Bitmap or Android type here, so every rule is JVM-tested.
 */
internal object ViewerTileZoom {
    /** Screen pixels per base-layer pixel at which the base visibly softens. */
    const val BASE_SOFTENS_AT = 1.5f

    /** Tiles help only when the source is clearly larger than the base decode. */
    const val SOURCE_OVER_BASE = 1.25f

    /** Today's viewer limit, kept for photos that have no tiled source. */
    const val BASE_MAX_ZOOM = 5f

    /** Never zoom past 16x, whatever the source size. */
    const val TILED_MAX_ZOOM = 16f

    /** At most two region decoders per photo (and two decodes in flight). */
    const val MAX_DECODERS = 2
}

/**
 * True when the visible part of a large photo deserves full-resolution
 * tiles: the base decode is magnified past [ViewerTileZoom.BASE_SOFTENS_AT]
 * screen pixels per base pixel, and the source has clearly more pixels than
 * the base. Unknown sizes fail closed (base layer only).
 */
internal fun viewerTilesWanted(
    zoom: Float, viewportWidth: Int, viewportHeight: Int,
    baseWidth: Int, baseHeight: Int, sourceWidth: Int, sourceHeight: Int
): Boolean {
    if (!zoom.isFinite() || zoom <= 1.01f || viewportWidth <= 0 || viewportHeight <= 0 ||
        baseWidth <= 0 || baseHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return false
    if (sourceWidth < baseWidth * ViewerTileZoom.SOURCE_OVER_BASE &&
        sourceHeight < baseHeight * ViewerTileZoom.SOURCE_OVER_BASE) return false
    val baseFit = min(viewportWidth.toDouble() / baseWidth, viewportHeight.toDouble() / baseHeight)
    return baseFit * zoom >= ViewerTileZoom.BASE_SOFTENS_AT
}

/**
 * The viewer's pinch limit. Without a tiled source it stays at today's 5x.
 * With one it allows up to two screen pixels per source pixel (so a 50 MP
 * photo on a phone reaches about 15x), never below 5x and never above 16x.
 */
internal fun viewerMaxZoom(
    viewportWidth: Int, viewportHeight: Int, tiledSourceWidth: Int, tiledSourceHeight: Int
): Float {
    if (viewportWidth <= 0 || viewportHeight <= 0 ||
        tiledSourceWidth <= 0 || tiledSourceHeight <= 0) return ViewerTileZoom.BASE_MAX_ZOOM
    val fit = min(viewportWidth.toDouble() / tiledSourceWidth,
        viewportHeight.toDouble() / tiledSourceHeight)
    if (!fit.isFinite() || fit <= 0.0) return ViewerTileZoom.BASE_MAX_ZOOM
    return (2.0 / fit).toFloat().coerceIn(ViewerTileZoom.BASE_MAX_ZOOM, ViewerTileZoom.TILED_MAX_ZOOM)
}

/**
 * Tile memory, bounded by the app heap like the thumbnail cache (PERF-2,
 * #147, which takes at most half the heap class): one eighth of the
 * process heap limit, between 4 MB and 32 MB. Visible tiles never need
 * more than the planner's 16 MB budget, so the plan budget is the smaller
 * of the two and the cache always holds one full viewport.
 */
internal fun viewerTileCacheBytes(maxHeapBytes: Long): Long {
    val mb = 1024L * 1024L
    if (maxHeapBytes <= 0L) return 4L * mb
    return (maxHeapBytes / 8L).coerceIn(4L * mb, 32L * mb)
}

internal fun viewerTilePlanBudgetBytes(cacheBytes: Long): Long =
    min(16L * 1024 * 1024, max(2L * 1024 * 1024, cacheBytes))

/**
 * Plan for the live pose. The pose's scale and translation are the photo
 * layer's graphicsLayer values relative to the viewport centre, which is
 * exactly the planner's convention, so they pass straight through.
 */
internal fun viewerTilePlanForPose(
    orientation: ViewerSourceOrientation,
    viewportWidth: Int, viewportHeight: Int,
    pose: ViewerZoomPosition,
    budgetBytes: Long,
    previous: ViewerOrientedTilePlan? = null
): ViewerOrientedTilePlan? = planViewerOrientedTiles(
    orientation, viewportWidth, viewportHeight,
    pose.scale, pose.translationX, pose.translationY,
    totalTileBufferBudgetBytes = budgetBytes, previous = previous)

/** A float rectangle in the photo layer's own (untransformed) pixels. */
internal data class ViewerDrawRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where an upright source rectangle sits inside the photo layer before its
 * zoom transform: the same ContentScale.Fit placement as the base image.
 * Float edges, never rounded, so neighbouring tiles share exact edges.
 */
internal fun viewerTileDrawRect(
    tile: ViewerSourceRect, sourceWidth: Int, sourceHeight: Int,
    viewportWidth: Int, viewportHeight: Int
): ViewerDrawRect? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return null
    val fit = min(viewportWidth.toDouble() / sourceWidth, viewportHeight.toDouble() / sourceHeight)
    val left = (viewportWidth - sourceWidth * fit) / 2.0
    val top = (viewportHeight - sourceHeight * fit) / 2.0
    return ViewerDrawRect((left + tile.left * fit).toFloat(), (top + tile.top * fit).toFloat(),
        (left + tile.right * fit).toFloat(), (top + tile.bottom * fit).toFloat())
}

/** Where a layer-space point lands on screen after the pose's graphicsLayer. */
internal fun viewerLayerToScreen(
    x: Float, y: Float, pose: ViewerZoomPosition, viewportWidth: Int, viewportHeight: Int
): Pair<Float, Float> {
    val cx = viewportWidth / 2f
    val cy = viewportHeight / 2f
    return (cx + (x - cx) * pose.scale + pose.translationX) to
        (cy + (y - cy) * pose.scale + pose.translationY)
}

/** What to cancel and what to start after the visible tile set changed. */
internal data class ViewerTileWork<K>(val cancel: List<K>, val start: List<K>)

/**
 * Decode scheduling for one pose. In-flight work for a tile that left the
 * active set is cancelled (rapid pan or zoom), tiles already in flight are
 * never started twice, and new work is started in the requests' priority
 * order (centre first). O(active + in flight).
 */
internal fun <K> viewerTileWork(
    missingInPriorityOrder: List<K>, activeKeys: Set<K>, inFlight: Set<K>
): ViewerTileWork<K> {
    val cancel = inFlight.filter { it !in activeKeys }
    val start = missingInPriorityOrder.filter { it !in inFlight }
    return ViewerTileWork(cancel, start)
}

/**
 * A 2x3 affine (x' = a*x + b*y + c, y' = d*x + e*y + f) that turns a decoded
 * RAW tile of size [rawWidth] x [rawHeight] into its upright pixels: rotate
 * clockwise, then mirror horizontally, then shift back to positive
 * coordinates. Bitmap.createBitmap(matrix) applies the same transform, so
 * this is the pure contract the Android adapter follows.
 */
internal data class ViewerTileAffine(
    val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float
) {
    fun map(x: Float, y: Float): Pair<Float, Float> = (a * x + b * y + c) to (d * x + e * y + f)
}

internal fun viewerTilePixelTransform(
    orientation: ViewerSourceOrientation, rawWidth: Int, rawHeight: Int
): ViewerTileAffine {
    val w = rawWidth.toFloat()
    val h = rawHeight.toFloat()
    // Clockwise rotation in screen coordinates (y down), shifted so the
    // rotated tile starts at (0,0).
    val rotated = when (orientation.clockwiseDegrees) {
        0 -> ViewerTileAffine(1f, 0f, 0f, 0f, 1f, 0f)
        90 -> ViewerTileAffine(0f, -1f, h, 1f, 0f, 0f)
        180 -> ViewerTileAffine(-1f, 0f, w, 0f, -1f, h)
        270 -> ViewerTileAffine(0f, 1f, 0f, -1f, 0f, w)
        else -> error("unreachable")
    }
    if (!orientation.mirrorHorizontally) return rotated
    val uprightW = if (orientation.clockwiseDegrees % 180 == 0) w else h
    return ViewerTileAffine(-rotated.a, -rotated.b, uprightW - rotated.c,
        rotated.d, rotated.e, rotated.f)
}

/**
 * EXIF tag for a MediaStore rotation, used only when the file's own EXIF
 * cannot be read. MediaStore has no mirror bit, so this never mirrors.
 */
internal fun viewerExifForDegrees(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
    0 -> 1
    90 -> 6
    180 -> 3
    270 -> 8
    else -> 0
}

/**
 * The region decoder's oriented size must describe the same picture as the
 * base decode (Coil applies orientation itself). A different aspect ratio
 * means the orientation was read wrongly, for example a container rotation
 * that EXIF does not report, so tiling is refused and the base stays.
 */
internal fun viewerTileSourceMatchesBase(
    orientation: ViewerSourceOrientation, baseWidth: Int, baseHeight: Int
): Boolean {
    if (baseWidth <= 0 || baseHeight <= 0) return false
    val source = orientation.uprightWidth.toDouble() / orientation.uprightHeight
    val base = baseWidth.toDouble() / baseHeight
    return kotlin.math.abs(source - base) <= base * 0.02 &&
        orientation.uprightWidth >= baseWidth && orientation.uprightHeight >= baseHeight
}
