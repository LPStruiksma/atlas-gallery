package nl.atlas.gallery

/**
 * Content URI and catalogue-grant epoch are NEVER persisted or logged here.
 * An epoch must change after revoke/regrant even for the exact same URI.
 * The revision must change after a confirmed external-editor modification,
 * including edits that preserve MediaStore timestamp/size.
 */
internal data class ViewerTileSource(
    val uri: String,
    val grantEpoch: Long,
    val contentRevision: Long
)

/** A tile is reusable only for its exact authorized source and transform. */
internal data class ViewerTileKey(
    val source: ViewerTileSource,
    val sampleSize: Int,
    val sourceRect: ViewerSourceRect,
    val clockwiseDegrees: Int,
    val mirrorHorizontally: Boolean
)

/** Decoding/rendering remains separate; this is a bounded pure request. */
internal data class ViewerTileRequest(
    val key: ViewerTileKey,
    val priority: Int,
    val maximumArgbBytes: Long
)

internal data class ViewerTileRequestBatch(
    val missing: List<ViewerTileRequest>,
    val cachedVisible: Int,
    val admittedBytes: Long,
    // Complete keyset for this exact viewport, including cache hits.
    // Supply this directly to viewerTileMayPublish at final UI publication.
    val activeVisibleKeys: Set<ViewerTileKey>
)

/**
 * Construct missing, center-first tile demands in O(n log n) where n <= 12.
 *
 * Residency is only a *hint* from a bounded, authorization-scoped cache;
 * every publication must independently check viewerTileMayPublish against
 * the currently authorized identity. A stale/grant-revoked bitmap must never
 * be surfaced merely because a request completed.
 *
 * A 0/invalid permission epoch fails CLOSED, without even consulting cache.
 * Background consumers should also cancel native decodes on generation change.
 */
internal fun viewerTileRequests(
    source: ViewerTileSource,
    orientation: ViewerSourceOrientation,
    plan: ViewerOrientedTilePlan,
    resident: Set<ViewerTileKey> = emptySet(),
    maxRequests: Int = 12
): ViewerTileRequestBatch {
    val empty = ViewerTileRequestBatch(emptyList(), 0, 0L, emptySet())
    if (orientation.rawWidth > 1_000_000 || orientation.rawHeight > 1_000_000 ||
        orientation.rawWidth.toLong() * orientation.rawHeight > 1_000_000_000L ||
        source.uri.isBlank() || source.grantEpoch <= 0L ||
        source.contentRevision < 0L || maxRequests !in 0..12 ||
        plan.upright.tiles.size !in 1..12 ||
        plan.upright.tiles.size != plan.rawTiles.size ||
        plan.upright.sampleSize <= 0 ||
        plan.upright.sampleSize and (plan.upright.sampleSize - 1) != 0
    ) return empty

    // A stale or forged plan must not request out-of-view tiles, holes or
    // overlapping decode work. Source-area cover is exact in half-open pixel
    // coordinates, with at most 12 rectangles and no image allocation.
    if (!viewerTilesExactlyCoverVisible(
            plan.upright.visibleSource, plan.upright.tiles,
            orientation.uprightWidth, orientation.uprightHeight)) return empty

    val centerX = (plan.upright.visibleSource.left.toLong() +
        plan.upright.visibleSource.right) / 2L
    val centerY = (plan.upright.visibleSource.top.toLong() +
        plan.upright.visibleSource.bottom) / 2L
    val multiplier = if (orientation.clockwiseDegrees != 0 ||
        orientation.mirrorHorizontally) 2L else 1L
    val candidates = ArrayList<Pair<Long, ViewerTileRequest>>(plan.rawTiles.size)
    var cacheHits = 0
    var totalBounds = 0L
    val activeKeys = LinkedHashSet<ViewerTileKey>(plan.rawTiles.size)
    for (index in plan.rawTiles.indices) {
        val raw = plan.rawTiles[index]
        val upright = plan.upright.tiles[index]
        // Refuse any malformed/reordered mapping before accepting cache hits.
        if (raw.right > orientation.rawWidth || raw.bottom > orientation.rawHeight ||
            viewerUprightRegionForRawTile(raw, orientation) != upright) return empty
        val sample = plan.upright.sampleSize.toLong()
        val width = (raw.right.toLong() - raw.left + sample - 1L) / sample
        val height = (raw.bottom.toLong() - raw.top + sample - 1L) / sample
        // Planner admits at most 1e9 raw source pixels, which bounds
        // multiplication; still reject each tile before adding to the total.
        val maximumBytes = width * height * 4L * multiplier
        if (maximumBytes <= 0L || maximumBytes > 16L * 1024L * 1024L ||
            totalBounds > 16L * 1024L * 1024L - maximumBytes) return empty
        totalBounds += maximumBytes
        val key = ViewerTileKey(source, plan.upright.sampleSize, raw,
            orientation.clockwiseDegrees, orientation.mirrorHorizontally)
        // Duplicate tiles make the budget and completion accounting ambiguous.
        if (!activeKeys.add(key)) return empty
        if (key in resident) {
            cacheHits++
            continue
        }
        val cx = (upright.left.toLong() + upright.right) / 2L
        val cy = (upright.top.toLong() + upright.bottom) / 2L
        val dx = cx - centerX
        val dy = cy - centerY
        candidates += (dx * dx + dy * dy) to ViewerTileRequest(
            key, index, maximumBytes)
    }
    // Do not let a forged, obsolete or incorrectly metered plan bypass its
    // global budget merely by claiming a few resident cache hits.
    if (totalBounds > plan.estimatedPeakTileBytes ||
        totalBounds > 16L * 1024L * 1024L) return empty
    val selected = candidates.sortedWith(
        compareBy<Pair<Long, ViewerTileRequest>>({ it.first }, { it.second.priority })
    ).take(maxRequests).map { it.second }
    return ViewerTileRequestBatch(selected, cacheHits,
        selected.sumOf { it.maximumArgbBytes }, activeKeys)
}

/**
 * Mandatory final publication gate, after decode and on the UI publication
 * owner. A valid grant is necessary but not sufficient: tiles from an old
 * pan/zoom/page plan must NOT appear after the viewer moves to another plan.
 * Passing the active bounded key set also rejects completed background work
 * when the viewer has closed (empty set).
 */
internal fun viewerTileMayPublish(
    completed: ViewerTileKey,
    current: ViewerTileSource?,
    activeVisibleKeys: Set<ViewerTileKey>
): Boolean = current != null && current.grantEpoch > 0L &&
    current.contentRevision >= 0L &&
    completed.source == current && completed in activeVisibleKeys


/**
 * Check the current requested viewport is partitioned exactly once by the
 * bounded tile rectangles. Cuts form <= 26 x 26 sample cells (12 tiles).
 * A point at each half-open cell's top-left has constant coverage throughout
 * that cell, proving no pixel holes/overlap without allocating image memory.
 *
 * This deliberately rejects a stale/inconsistent planner result rather than
 * decoding unrelated or overlapping image regions.
 */
internal fun viewerTilesExactlyCoverVisible(
    visible: ViewerSourceRect,
    tiles: List<ViewerSourceRect>,
    sourceWidth: Int,
    sourceHeight: Int
): Boolean {
    if (sourceWidth <= 0 || sourceHeight <= 0 ||
        visible.right > sourceWidth || visible.bottom > sourceHeight ||
        tiles.size !in 1..12) return false
    val xCuts = sortedSetOf(visible.left, visible.right)
    val yCuts = sortedSetOf(visible.top, visible.bottom)
    for (tile in tiles) {
        if (tile.right > sourceWidth || tile.bottom > sourceHeight ||
            tile.left >= visible.right || tile.right <= visible.left ||
            tile.top >= visible.bottom || tile.bottom <= visible.top) return false
        xCuts.add(tile.left.coerceAtLeast(visible.left))
        xCuts.add(tile.right.coerceAtMost(visible.right))
        yCuts.add(tile.top.coerceAtLeast(visible.top))
        yCuts.add(tile.bottom.coerceAtMost(visible.bottom))
    }
    val xs = xCuts.toList()
    val ys = yCuts.toList()
    for (xi in 0 until xs.lastIndex) for (yi in 0 until ys.lastIndex) {
        val x = xs[xi]
        val y = ys[yi]
        var coverage = 0
        for (tile in tiles) {
            if (x >= tile.left && x < tile.right &&
                y >= tile.top && y < tile.bottom) {
                coverage++
                if (coverage > 1) return false
            }
        }
        if (coverage != 1) return false
    }
    return true
}
