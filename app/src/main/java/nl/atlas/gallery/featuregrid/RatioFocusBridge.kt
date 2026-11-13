package nl.atlas.gallery.featuregrid

/** Measured LazyColumn row offset; never a private URI or an untrusted row index. */
data class RatioVisibleRow(val rowIndex: Int, val topPx: Int)

/**
 * Select the actual uncropped ratio tile below a pinch, not merely the first
 * item in a justified row. Only currently visible rows/cells are examined;
 * 150k-source lookups are O(visible items), with no global URI search.
 */
fun focalRatioAnchor(
    layout: RatioLayout,
    visibleRows: List<RatioVisibleRow>,
    focusX: Float,
    focusY: Float,
    mediaKeyAtIndex: (Int) -> String?
): GridFocalAnchor? {
    if (!focusX.isFinite() || !focusY.isFinite()) return null
    val visibleCells = ArrayList<GridVisibleCell>()
    for (visible in visibleRows) {
        val row = layout.rows.getOrNull(visible.rowIndex) ?: continue
        var x = layout.options.horizontalInsetPx
        for (inRow in 0 until row.mediaCount) {
            val index = row.firstMediaIndex + inRow
            val width = row.cellWidthsPx[inRow]
            val key = mediaKeyAtIndex(index)
            if (!key.isNullOrBlank()) {
                visibleCells.add(GridVisibleCell(
                    key, index, x, visible.topPx, width, row.heightPx
                ))
            }
            x += width + layout.options.gapPx
        }
    }
    return focalGridAnchor(visibleCells, focusX, focusY)
}
