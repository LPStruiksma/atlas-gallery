package nl.atlas.gallery.featuregrid

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sign

/** A small, deliberate phone timeline scale. No Samsung source or vendor code. */
enum class GridDensity(val columns: Int, val accessibleName: String) {
    LARGE(3, "Large thumbnails"),
    MEDIUM(4, "Medium thumbnails"),
    SMALL(7, "Small thumbnails");

    companion object {
        fun fromColumns(columns: Int): GridDensity = entries.firstOrNull { it.columns == columns } ?: MEDIUM
    }
}

/**
 * Focal media identity survives layout changes. [fractionY] captures the point *inside*
 * the focused cell, not merely the cell's top edge. The horizontal fraction is retained
 * for future cross-layout animation; a fixed-column vertical-only grid cannot make an
 * arbitrary X coordinate stationary when the column count changes.
 */
data class GridFocalAnchor(
    val mediaKey: String,
    val itemIndex: Int,
    val fractionX: Float,
    val fractionY: Float
)

data class GridVisibleCell(
    val mediaKey: String,
    val itemIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/** Pick the finger's actual cell, falling back to its nearest visible neighbour at gaps. */
fun focalGridAnchor(cells: List<GridVisibleCell>, focusX: Float, focusY: Float): GridFocalAnchor? {
    if (cells.isEmpty() || !focusX.isFinite() || !focusY.isFinite()) return null
    val nearest = cells.asSequence().filter { it.width > 0 && it.height > 0 && it.itemIndex >= 0 }
        .minByOrNull { cell ->
            val dx = when {
                focusX < cell.x -> cell.x - focusX
                focusX > cell.x + cell.width -> focusX - (cell.x + cell.width)
                else -> 0f
            }
            val dy = when {
                focusY < cell.y -> cell.y - focusY
                focusY > cell.y + cell.height -> focusY - (cell.y + cell.height)
                else -> 0f
            }
            dx * dx + dy * dy
        } ?: return null
    return GridFocalAnchor(
        mediaKey = nearest.mediaKey,
        itemIndex = nearest.itemIndex,
        fractionX = ((focusX - nearest.x) / nearest.width).coerceIn(0f, 1f),
        fractionY = ((focusY - nearest.y) / nearest.height).coerceIn(0f, 1f)
    )
}

data class GridViewportGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val horizontalInsetPx: Int,
    val gapPx: Int,
    val itemCount: Int
) {
    init {
        require(widthPx > 0 && heightPx > 0 && horizontalInsetPx >= 0 && gapPx >= 0)
        require(itemCount >= 0)
    }

    /**
     * Used by out-of-layout controls (such as timeline "View as") to refuse
     * focal coordinates from an obsolete layout after rotate/split resize.
     * This is an O(1) guard, not an attempt to infer a new layout size.
     */
    fun matchesMeasuredViewport(measuredWidthPx: Int, measuredHeightPx: Int): Boolean =
        measuredWidthPx > 0 && measuredHeightPx > 0 &&
            widthPx == measuredWidthPx && heightPx == measuredHeightPx

    /**
     * Compose GridCells.Fixed distributes remainder pixels across the first columns.
     * Every photo is square and the *row* uses the tallest (rounded-up) column,
     * including when the final row is incomplete. Rounding DOWN silently loses
     * one pixel per row: at 150k items that becomes tens of thousands of pixels
     * of erroneous focal scroll. This is a row-height, not every item's width.
     */
    fun cellSidePx(columns: Int): Int {
        require(columns > 0)
        val available = widthPx.toLong() - 2L * horizontalInsetPx -
            (columns - 1L) * gapPx
        return if (available <= 0) 1 else
            ((available + columns - 1L) / columns).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

data class GridScrollTarget(
    /** First item in the topmost visible target row, always on a row boundary. */
    val firstItemIndex: Int,
    /** Positive pixels scrolled beyond that row's start. */
    val firstItemScrollOffsetPx: Int,
    /** True near either catalog end, where exact focal alignment is impossible. */
    val clampedByBounds: Boolean
)

/**
 * Deterministic, allocation-free anchor transform for a uniform square grid with zero
 * *vertical* content padding. The Compose adapter uses matching Fixed columns, gaps,
 * horizontal insets, and vertical-zero padding. Keep this pure for 150k-item checks.
 *
 * The destination is expressed as a row-start item plus positive pixel offset, rather
 * than passing an arbitrary large negative offset to LazyGridState.scrollToItem.
 */
fun resolveGridAnchor(
    anchor: GridFocalAnchor,
    newIndex: Int,
    columns: Int,
    viewport: GridViewportGeometry,
    focusYPx: Float
): GridScrollTarget {
    require(columns > 0 && newIndex in 0 until viewport.itemCount)
    require(focusYPx.isFinite() && anchor.fractionY.isFinite())
    val cell = viewport.cellSidePx(columns)
    val stride = cell + viewport.gapPx
    val rows = (viewport.itemCount.toLong() + columns - 1L) / columns
    val contentHeight = rows * cell + (rows - 1L).coerceAtLeast(0) * viewport.gapPx
    val maxScroll = (contentHeight - viewport.heightPx).coerceAtLeast(0L)
    val desiredScroll = (newIndex / columns).toDouble() * stride +
        anchor.fractionY.coerceIn(0f, 1f).toDouble() * cell - focusYPx.toDouble()
    val bounded = desiredScroll.coerceIn(0.0, maxScroll.toDouble())
    // Round the *absolute* scroll coordinate before splitting row/offset; otherwise
    // a near-row-boundary fraction can round up to a full stride and get clipped.
    val roundedScroll = bounded.roundToLong().coerceIn(0L, maxScroll)
    val row = (roundedScroll / stride).coerceIn(0L, (rows - 1).coerceAtLeast(0))
    val offset = (roundedScroll - row * stride).toInt()
    return GridScrollTarget(
        firstItemIndex = (row * columns).toInt(),
        firstItemScrollOffsetPx = offset,
        clampedByBounds = abs(desiredScroll - bounded) > 0.501
    )
}

/**
 * Gesture progress is capped per event to reject bogus pointer jumps. Scale out makes
 * thumbnails larger; scale in makes more columns. Density remains unchanged during
 * the gesture, preserving already-decoded thumbnails until release.
 */
class GridPinchAccumulator(private val initial: GridDensity) {
    var progress: Float = 0f
        private set

    fun addZoomFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        // Ordinary quick pinches can move more than a quarter density level in
        // one pointer sample (e.g. a 20% scale change). The old unconditional
        // 0.2 cap made those gestures settle as no-ops even after touch slop.
        // Retain the tighter cap only for implausibly large one-frame jumps,
        // so a corrupted event cannot trigger an accidental density change.
        val maxStep = if (factor < 0.5f || factor > 2f) 0.2f else 0.5f
        val step = (-ln(factor.toDouble()) / ln(1.5)).toFloat().coerceIn(-maxStep, maxStep)
        progress = (progress + step).coerceIn(-initial.ordinal.toFloat(),
            (GridDensity.entries.lastIndex - initial.ordinal).toFloat())
    }

    /** A quarter-level threshold avoids tiny shakes changing View as. */
    fun settle(): GridDensity {
        val steps = floor(abs(progress) + 0.75f).toInt() * progress.sign.toInt()
        return GridDensity.entries[(initial.ordinal + steps).coerceIn(0, GridDensity.entries.lastIndex)]
    }
}
