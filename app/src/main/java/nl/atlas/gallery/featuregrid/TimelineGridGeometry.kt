package nl.atlas.gallery.featuregrid

import kotlin.math.abs
import kotlin.math.roundToLong

/** A full-width header or a single row of 1..columns photos. */
data class TimelineVisualRow(
    val firstSlotIndex: Int,
    val slotCount: Int,
    val topPx: Long,
    val heightPx: Int,
    val header: Boolean
)

data class TimelineScrollTarget(
    val firstSlotIndex: Int,
    val firstSlotScrollOffsetPx: Int,
    val clampedByBounds: Boolean
)

/** One frozen catalogue, grouping and grid width; does not decode or retain image bytes. */
class TimelineGridGeometry(
    val rows: List<TimelineVisualRow>,
    private val slotToRow: IntArray,
    val timeline: GridTimelineIndex,
    val viewport: GridViewportGeometry,
    val columns: Int,
    val heightPx: Long
) {
    /** Column (0-based) of [mediaIndex]'s tile inside its photo row. */
    fun columnOfMedia(mediaIndex: Int): Int {
        val slotIndex = timeline.entryForMedia(mediaIndex)
        return slotIndex - rows[slotToRow[slotIndex]].firstSlotIndex
    }

    /** Target the actual media slot's row, even if preceding headers change row counts. */
    fun targetMedia(mediaIndex: Int, fractionInsidePhotoY: Float, focusYPx: Float): TimelineScrollTarget {
        require(fractionInsidePhotoY.isFinite() && focusYPx.isFinite())
        val slotIndex = timeline.entryForMedia(mediaIndex)
        val focusedRow = rows[slotToRow[slotIndex]]
        check(!focusedRow.header)
        val wanted = focusedRow.topPx.toDouble() +
            focusedRow.heightPx * fractionInsidePhotoY.coerceIn(0f, 1f).toDouble() - focusYPx.toDouble()
        val maxScroll = (heightPx - viewport.heightPx).coerceAtLeast(0L)
        val bounded = wanted.coerceIn(0.0, maxScroll.toDouble()).roundToLong()
        var lo = 0
        var hi = rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rows[mid].topPx <= bounded) lo = mid else hi = mid - 1
        }
        return TimelineScrollTarget(
            firstSlotIndex = rows[lo].firstSlotIndex,
            firstSlotScrollOffsetPx = (bounded - rows[lo].topPx).toInt(),
            clampedByBounds = abs(wanted - bounded) > .501
        )
    }
}

/**
 * Compose contract: LazyVerticalGrid(Fixed(columns)) with header span=maxLineSpan,
 * one header height [headerHeightPx], vertical gap [viewport.gapPx], horizontal
 * padding [viewport.horizontalInsetPx], and zero vertical content padding.
 *
 * This exact row model is REQUIRED before headers and pinch can be combined: a
 * naïve index/columns scroll calculation drifts by every inserted header row.
 */
fun planTimelineVisualRows(
    timeline: GridTimelineIndex,
    columns: Int,
    viewport: GridViewportGeometry,
    headerHeightPx: Int,
    cancellationCheckpoint: (() -> Unit)? = null
): TimelineGridGeometry {
    require(columns > 0 && headerHeightPx > 0)
    require(timeline.mediaCount == viewport.itemCount)
    val rows = ArrayList<TimelineVisualRow>(timeline.slots.size / columns + timeline.headers.size + 1)
    val slotToRow = IntArray(timeline.slots.size)
    val photoHeightPx = viewport.cellSidePx(columns)
    var position = 0
    var top = 0L
    while (position < timeline.slots.size) {
        if ((rows.size and 255) == 0) cancellationCheckpoint?.invoke()
        val header = timeline.slots[position] is TimelineSlot.Header
        var span = 1
        if (!header) while (span < columns && position + span < timeline.slots.size &&
            timeline.slots[position + span] is TimelineSlot.Media) span++
        val rowIndex = rows.size
        val height = if (header) headerHeightPx else photoHeightPx
        for (i in position until position + span) slotToRow[i] = rowIndex
        rows.add(TimelineVisualRow(position, span, top, height, header))
        top += height.toLong() + viewport.gapPx
        position += span
    }
    cancellationCheckpoint?.invoke()
    val totalHeight = if (rows.isEmpty()) 0L else top - viewport.gapPx
    return TimelineGridGeometry(rows, slotToRow, timeline, viewport, columns, totalHeight)
}
