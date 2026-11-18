package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.atlas.gallery.featureorganization.dragAutoScrollStep

/** One measured grid cell in the grid's own drawn coordinates. */
internal data class DragHitCell(val slotIndex: Int, val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The slot under ([x], [y]), or null over a gap, a header row or empty space.
 * A cell's own row is found by y first; inside that row the closest cell by x
 * wins, so a few pixels of horizontal content padding never miss a tile.
 */
internal fun dragHitSlot(cells: List<DragHitCell>, x: Float, y: Float): Int? {
    var best: DragHitCell? = null
    var bestDx = Float.MAX_VALUE
    for (cell in cells) {
        if (y < cell.top || y >= cell.top + cell.height) continue
        val inside = x >= cell.left && x < cell.left + cell.width
        val dx = if (inside) 0f else minOf(kotlin.math.abs(x - cell.left),
            kotlin.math.abs(x - (cell.left + cell.width)))
        if (dx < bestDx) { best = cell; bestDx = dx }
    }
    return best?.slotIndex
}

/**
 * Scan current visible geometry without mapping each item to a DragHitCell.
 * The accessors let JVM tests use synthetic grid items; the production call
 * reads layoutInfo afresh on every pointer event and auto-scroll frame.
 */
internal inline fun <T> dragHitVisibleItems(
    cells: List<T>,
    viewportStartOffset: Int,
    x: Float,
    y: Float,
    index: (T) -> Int,
    offset: (T) -> androidx.compose.ui.unit.IntOffset,
    size: (T) -> androidx.compose.ui.unit.IntSize
): Int? {
    var bestIndex: Int? = null
    var bestDx = Float.MAX_VALUE
    for (cell in cells) {
        val position = offset(cell)
        val dimensions = size(cell)
        val top = position.y - viewportStartOffset
        if (y < top || y >= top + dimensions.height) continue
        val left = position.x
        val right = left + dimensions.width
        val inside = x >= left && x < right
        val dx = if (inside) 0f else minOf(kotlin.math.abs(x - left),
            kotlin.math.abs(x - right))
        if (dx < bestDx) {
            bestIndex = index(cell)
            bestDx = dx
        }
    }
    return bestIndex
}

private fun LazyGridState.hitSlot(x: Float, y: Float): Int? {
    val info = layoutInfo
    // Pointer coordinates include before-content padding; item offsets do not.
    return dragHitVisibleItems(info.visibleItemsInfo, info.viewportStartOffset, x, y,
        index = { it.index }, offset = { it.offset }, size = { it.size })
}

/**
 * Samsung long-press drag selection (parity research `GRID:S1`, `S2`) for a lazy grid.
 *
 * A finger held still for the long-press timeout over a media slot starts
 * selection there ([onStart] returns false to refuse). Without lifting, every
 * move reports the media slot under the finger to [onExtend]; gaps and headers
 * keep the last slot. Within 28 dp of the visible top (below the pinned bar
 * over the grid's top content padding) or the bottom edge the grid scrolls
 * itself, faster the deeper the finger is in the zone, and the range is
 * re-read after every step. A second finger ends the drag. Once selection has
 * started every event is consumed in the Initial pass, so the grid neither
 * scrolls under the finger nor delivers a tap to the tile on release.
 */
internal fun Modifier.gridDragSelect(
    enabled: Boolean,
    gridState: LazyGridState,
    frameScope: CoroutineScope,
    isMediaSlot: (Int) -> Boolean,
    onStart: (slotIndex: Int) -> Boolean,
    onExtend: (slotIndex: Int) -> Unit,
    onEnd: () -> Unit
): Modifier = if (!enabled) this else pointerInput(gridState) {
    val edge = 28.dp.toPx()
    val maxStep = 10.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        // Wait out the long-press timeout without moving or a second finger.
        var abandoned = false
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!abandoned) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                abandoned = change == null || !change.pressed || change.isConsumed ||
                    event.changes.count { it.pressed } > 1 ||
                    (change.position - down.position).getDistance() > slop
            }
        }
        // Non-null means the loop ended before the timeout: tap, scroll or pinch.
        if (ended != null) return@awaitEachGesture
        val startSlot = gridState.hitSlot(down.position.x, down.position.y)
            ?.takeIf(isMediaSlot) ?: return@awaitEachGesture
        if (!onStart(startSlot)) return@awaitEachGesture
        var finger: Offset = down.position
        var lastSlot = startSlot
        fun extend() {
            val slot = gridState.hitSlot(finger.x, finger.y)
            if (slot != null && slot != lastSlot && isMediaSlot(slot)) {
                lastSlot = slot
                onExtend(slot)
            }
        }
        var scrollJob: Job? = null
        val height = size.height.toFloat()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                finger = change.position
                extend()
                val step = dragAutoScrollStep(finger.y, height, edge, maxStep,
                    top = gridState.layoutInfo.beforeContentPadding.toFloat())
                if (step != 0f && scrollJob?.isActive != true) {
                    scrollJob = frameScope.launch {
                        while (isActive) {
                            withFrameNanos { }
                            val now = dragAutoScrollStep(finger.y, height, edge, maxStep,
                                top = gridState.layoutInfo.beforeContentPadding.toFloat())
                            if (now == 0f) break
                            val moved = gridState.scrollBy(now)
                            extend()
                            if (moved == 0f) break
                        }
                    }
                }
            }
        } finally {
            scrollJob?.cancel()
            onEnd()
        }
    }
}
