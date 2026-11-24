package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.lazy.LazyListState

/**
 * The catalogue media index under ([x], [y]) in original-proportion rows, or
 * null over empty space. Rows are found by y from their live list offsets
 * ([visibleRows] as row index to offset, [viewportStartOffset] as in Compose's
 * layout info); inside a row the cell under x wins, and the gap between two
 * cells goes to the nearer one, so a drag never stalls on a seam.
 */
internal fun ratioHitMedia(
    layout: RatioLayout,
    visibleRows: List<Pair<Int, Int>>,
    viewportStartOffset: Int,
    x: Float,
    y: Float
): Int? {
    val gap = layout.options.gapPx
    val inset = layout.options.horizontalInsetPx
    for ((rowIndex, offset) in visibleRows) {
        val row = layout.rows.getOrNull(rowIndex) ?: continue
        val top = offset - viewportStartOffset
        // A row owns the gap below it, so moving down between rows keeps it.
        if (y < top || y >= top + row.heightPx + gap) continue
        var left = inset
        for (i in 0 until row.mediaCount) {
            val right = left + row.cellWidthsPx[i]
            val isLast = i == row.mediaCount - 1
            if (isLast || x < right + gap / 2f) return row.firstMediaIndex + i
            left = right + gap
        }
    }
    return null
}

internal fun LazyListState.ratioHitMedia(layout: RatioLayout, x: Float, y: Float): Int? {
    val info = layoutInfo
    return ratioHitMedia(layout, info.visibleItemsInfo.map { it.index to it.offset },
        info.viewportStartOffset, x, y)
}
