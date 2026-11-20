package nl.atlas.gallery.featuregrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * FLOW-1 (#711): the pure maths of the two-layer grid pinch, JVM-tested in
 * PinchCrossGeometryTest. No Compose state lives here.
 *
 * Samsung reference (atlas-native samsung-rea GRID:G2–G7): during a pinch the
 * old and the new layout are both on screen. The photo under the fingers (the
 * anchor) is one rectangle that grows or shrinks between its size in the two
 * layouts; each layout is drawn with the one scale and offset that puts its
 * own copy of the anchor exactly on that rectangle, and the two layouts
 * crossfade. Everything around the anchor therefore flows from one layout
 * into the other, and the anchor itself never doubles or drifts.
 *
 * Coordinates: "screen" means the grid composable's own coordinates (pointer
 * input, graphicsLayer). A LazyGrid reports item offsets without its content
 * padding, so screen = item offset + (start padding, top padding).
 */

/** Cell x offsets and widths of `GridCells.Fixed(columns)`, exactly as LazyGrid measures them. */
internal class GridColumnSlots(val x: IntArray, val width: IntArray) {
    val columns: Int get() = x.size
}

/**
 * LazyGrid gives every slot `(available − gaps) / columns` and the first
 * `remainder` slots one extra pixel (LazyGridDsl calculateCellsCrossAxisSizeImpl).
 * [x] excludes the start padding.
 */
internal fun composeGridColumnSlots(availableWidthPx: Int, gapPx: Int, columns: Int): GridColumnSlots {
    require(columns > 0 && gapPx >= 0)
    val withoutGaps = (availableWidthPx - gapPx * (columns - 1)).coerceAtLeast(0)
    val base = withoutGaps / columns
    val remainder = withoutGaps % columns
    val xs = IntArray(columns)
    val widths = IntArray(columns)
    var x = 0
    for (i in 0 until columns) {
        widths[i] = base + if (i < remainder) 1 else 0
        xs[i] = x
        x += widths[i] + gapPx
    }
    return GridColumnSlots(xs, widths)
}

/** One slot of a pinch layer, in screen coordinates. */
internal data class CrossCell(
    val slotIndex: Int,
    val header: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/** Where the layer would be scrolled: content y at the top of the content area. */
internal fun TimelineGridGeometry.scrollTopFor(target: TimelineScrollTarget): Long =
    rowForSlot(target.firstSlotIndex).topPx + target.firstSlotScrollOffsetPx

/**
 * The slots of [geometry] a LazyGrid scrolled to [scrollTopPx] would draw in a
 * viewport [viewportHeightPx] high, including rows under the top padding
 * (the floating header) and the bottom padding (the nav pill). Visits only
 * the visible rows: O(log rows + visible).
 */
internal fun crossVisibleCells(
    geometry: TimelineGridGeometry,
    slots: GridColumnSlots,
    scrollTopPx: Long,
    startPaddingPx: Int,
    topPaddingPx: Int,
    headerWidthPx: Int,
    viewportHeightPx: Int
): List<CrossCell> {
    val rows = geometry.rows
    if (rows.isEmpty() || viewportHeightPx <= 0) return emptyList()
    // First row whose bottom is below the top edge of the viewport.
    val firstVisibleContentY = scrollTopPx - topPaddingPx
    var lo = 0
    var hi = rows.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val row = rows[mid]
        if (row.topPx + row.heightPx <= firstVisibleContentY) lo = mid + 1 else hi = mid
    }
    val out = ArrayList<CrossCell>()
    var r = lo
    while (r < rows.size) {
        val row = rows[r]
        val top = topPaddingPx + row.topPx - scrollTopPx
        if (top >= viewportHeightPx) break
        val screenTop = top.toInt()
        if (row.header) {
            out.add(CrossCell(row.firstSlotIndex, true, startPaddingPx, screenTop,
                headerWidthPx, row.heightPx))
        } else {
            for (c in 0 until row.slotCount) {
                val column = c.coerceAtMost(slots.columns - 1)
                val side = slots.width[column]
                out.add(CrossCell(row.firstSlotIndex + c, false,
                    startPaddingPx + slots.x[column], screenTop, side, side))
            }
        }
        r++
    }
    return out
}

/** The anchor photo's rectangle in a layout scrolled to [scrollTopPx], in screen coordinates. */
internal fun crossAnchorRect(
    geometry: TimelineGridGeometry,
    slots: GridColumnSlots,
    mediaIndex: Int,
    scrollTopPx: Long,
    startPaddingPx: Int,
    topPaddingPx: Int
): Rect {
    val row = geometry.rowOfMedia(mediaIndex)
    val column = geometry.columnOfMedia(mediaIndex).coerceIn(0, slots.columns - 1)
    val side = slots.width[column].toFloat()
    val left = (startPaddingPx + slots.x[column]).toFloat()
    val top = (topPaddingPx + row.topPx - scrollTopPx).toFloat()
    return Rect(left, top, left + side, top + side)
}

/** Stand-in anchor rectangle for a level whose layout is not planned yet: the same point under the focus. */
internal fun estimatedAnchorRect(focus: Offset, fractionX: Float, fractionY: Float, sidePx: Float): Rect {
    val left = focus.x - fractionX.coerceIn(0f, 1f) * sidePx
    val top = focus.y - fractionY.coerceIn(0f, 1f) * sidePx
    return Rect(left, top, left + sidePx, top + sidePx)
}

/** Uniform scale + translation (transform origin at 0,0) for a graphicsLayer. */
internal data class LayerPose(val scale: Float, val translationX: Float, val translationY: Float) {
    fun map(point: Offset): Offset = Offset(point.x * scale + translationX, point.y * scale + translationY)

    companion object {
        val Identity = LayerPose(1f, 0f, 0f)
    }
}

/** The pose that draws [from] exactly onto [to] (square cells: width ratio is the scale). */
internal fun poseOnto(from: Rect, to: Rect): LayerPose {
    if (from.width <= 0f || !from.width.isFinite() || !to.width.isFinite()) return LayerPose.Identity
    val scale = to.width / from.width
    return LayerPose(scale, to.left - scale * from.left, to.top - scale * from.top)
}

internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t
)

/** Scales [rect] about [pivot] by [factor]. */
internal fun scaleRectAbout(rect: Rect, pivot: Offset, factor: Float): Rect = Rect(
    pivot.x + (rect.left - pivot.x) * factor, pivot.y + (rect.top - pivot.y) * factor,
    pivot.x + (rect.right - pivot.x) * factor, pivot.y + (rect.bottom - pivot.y) * factor
)

/**
 * The visual position of a pinch, in density-level units: 0 = 3 columns,
 * 1 = 4, 2 = 7, 3 = 12 month, 4 = 28 year; values below 0 are the zoom past
 * 3 columns toward original-proportion rows (that destination is FLOW-2).
 */
internal data class CrossFrame(val lower: Int, val upper: Int, val fraction: Float, val beyondLarge: Float)

internal fun crossFrame(position: Float, maxLevel: Int): CrossFrame {
    val v = if (position.isFinite()) position else 0f
    if (v <= 0f) return CrossFrame(0, 0, 0f, if (v < 0f) -v else 0f)
    val clamped = min(v, maxLevel.toFloat())
    val lower = floor(clamped).toInt().coerceIn(0, maxLevel)
    val upper = min(lower + 1, maxLevel)
    val fraction = if (upper == lower) 0f else clamped - lower
    return CrossFrame(lower, upper, fraction, 0f)
}

/** Each pinch-ladder stop is one 1.5× change in finger span (GalleryZoomGesture). */
internal const val CROSS_STOP_ZOOM = 1.5f

/**
 * The anchor rectangle at [frame]: between its rest rectangles in the lower
 * and upper levels, and past 3 columns, the 3-column one enlarged about the
 * finger focus (so the pinch keeps responding until it hands over to
 * original-proportion rows).
 */
internal fun crossAnchorAt(frame: CrossFrame, restRect: (level: Int) -> Rect, focus: Offset): Rect {
    if (frame.beyondLarge > 0f) {
        return scaleRectAbout(restRect(0), focus, CROSS_STOP_ZOOM.pow(frame.beyondLarge))
    }
    if (frame.lower == frame.upper) return restRect(frame.lower)
    return lerpRect(restRect(frame.lower), restRect(frame.upper), frame.fraction)
}

/**
 * Draw order and opacity of one level at [frame]. The level that currently
 * dominates is drawn underneath at full opacity; the other is drawn on top,
 * fading by the eased fraction. At fraction 0.5 both orders give the same
 * picture, so swapping them there is invisible, and the screen never shows
 * the background through two half-transparent layers.
 */
internal data class CrossLayerLook(val alpha: Float, val onTop: Boolean)

internal fun crossLayerLook(level: Int, frame: CrossFrame, eased: (Float) -> Float): CrossLayerLook? {
    if (frame.lower == frame.upper || frame.beyondLarge > 0f)
        return if (level == frame.lower) CrossLayerLook(1f, false) else null
    val e = eased(frame.fraction.coerceIn(0f, 1f))
    val upperDominates = frame.fraction >= 0.5f
    return when (level) {
        frame.lower -> if (upperDominates) CrossLayerLook(1f - e, true) else CrossLayerLook(1f, false)
        frame.upper -> if (upperDominates) CrossLayerLook(1f, false) else CrossLayerLook(e, true)
        else -> null
    }
}

/**
 * Settle length after release (GRID:G3: the rest of the transition plays
 * over 350 ms): proportional to the distance still to travel, never shorter
 * than [minMs] so a nearly finished pinch still eases in.
 */
internal fun crossSettleMillis(from: Float, to: Float, fullMs: Int, minMs: Int): Int {
    val distance = abs(to - from)
    if (!distance.isFinite() || distance == 0f) return 0
    return max(minMs, (fullMs * min(distance, 1f)).toInt())
}
