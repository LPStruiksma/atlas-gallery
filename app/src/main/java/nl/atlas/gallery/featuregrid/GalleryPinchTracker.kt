package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure pinch state machine for the gallery zoom ladder (#494), independent of
 * gesture plumbing so the whole ladder is JVM-tested.
 *
 * Input per pointer event: how many fingers are down, their span (mean
 * distance from the centroid) and the centroid. The pinch starts once the
 * span has changed by more than touch slop since the current finger set went
 * down, which is the distance the fingers actually travelled. A change in
 * finger count re-baselines the span, so adding or lifting a finger never
 * reads as a jump in scale. Once started every later sample, including the
 * lifts, belongs to the pinch and must be consumed before children see it.
 */
class GalleryPinchTracker(
    private val initial: GalleryZoomStop,
    private val touchSlopPx: Float,
    allowOriginal: Boolean = true,
    allowOverview: Boolean = true
) {
    private val ladder = GalleryZoomGesture(initial, allowOriginal, allowOverview)
    private var baselineSpan = Float.NaN
    private var lastSpan = Float.NaN
    private var lastCount = 0

    var started: Boolean = false
        private set
    var focusX: Float = 0f
        private set
    var focusY: Float = 0f
        private set

    /** Live ladder progress in stops (negative = towards original rows). */
    val progress: Float get() = ladder.progress

    /**
     * Live visual zoom since the pinch started (finger span ratio, >1 when
     * spreading), clamped to [MIN_ZOOM]..[MAX_ZOOM]. Drives the continuous
     * transition (#507); 1 until the pinch starts.
     */
    var zoom: Float = 1f
        private set

    private fun applyZoom(factor: Float) {
        if (factor.isFinite() && factor > 0f) zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Returns true when this sample belongs to a started pinch and must be consumed. */
    fun onSample(pressedCount: Int, spanPx: Float, centroidX: Float, centroidY: Float): Boolean {
        if (pressedCount < 2 || !spanPx.isFinite() || spanPx <= 0f) {
            lastCount = pressedCount
            lastSpan = Float.NaN
            return started
        }
        if (centroidX.isFinite() && centroidY.isFinite()) {
            focusX = centroidX
            focusY = centroidY
        }
        if (pressedCount != lastCount || !lastSpan.isFinite()) {
            lastCount = pressedCount
            lastSpan = spanPx
            if (!started) baselineSpan = spanPx
            return started
        }
        if (!started) {
            if (abs(spanPx - baselineSpan) > touchSlopPx) {
                started = true
                ladder.addZoomFactor(spanPx / baselineSpan)
                applyZoom(spanPx / baselineSpan)
            }
        } else {
            ladder.addZoomFactor(spanPx / lastSpan)
            applyZoom(spanPx / lastSpan)
        }
        lastSpan = spanPx
        return started
    }

    /** The stop to switch to on release, or null when nothing should change. */
    fun destination(): GalleryZoomStop? =
        if (!started) null else ladder.settle().takeIf { it != initial }

    companion object {
        const val MIN_ZOOM = 0.4f
        const val MAX_ZOOM = 2.5f
    }
}

/** Mean distance of the points from their centroid, plus the centroid; null when empty. */
internal fun pinchSpan(xs: FloatArray, ys: FloatArray): Triple<Float, Float, Float>? {
    val n = minOf(xs.size, ys.size)
    if (n == 0) return null
    var cx = 0f
    var cy = 0f
    for (i in 0 until n) { cx += xs[i]; cy += ys[i] }
    cx /= n
    cy /= n
    var span = 0f
    for (i in 0 until n) span += hypot(xs[i] - cx, ys[i] - cy)
    return Triple(span / n, cx, cy)
}

/**
 * Reusable pressed-pointer measurement. One instance is allocated per gesture,
 * never per event. The exact mean Euclidean distance from the centroid requires
 * two scans: first find the centroid, then the distances around that centroid.
 * The traversal and Float operation order match the old filter/arrays/Triple
 * implementation, including non-finite coordinates and finger lifts.
 */
internal class PinchEventMeasurement {
    var pressedCount: Int = 0
        private set
    var spanPx: Float = Float.NaN
        private set
    var centroidX: Float = Float.NaN
        private set
    var centroidY: Float = Float.NaN
        private set

    fun read(changes: List<PointerInputChange>) {
        var count = 0
        var cx = 0f
        var cy = 0f
        for (change in changes) {
            if (change.pressed) {
                count++
                cx += change.position.x
                cy += change.position.y
            }
        }
        pressedCount = count
        if (count == 0) {
            spanPx = Float.NaN
            centroidX = Float.NaN
            centroidY = Float.NaN
            return
        }
        cx /= count
        cy /= count
        var span = 0f
        for (change in changes) {
            if (change.pressed) span += hypot(change.position.x - cx, change.position.y - cy)
        }
        spanPx = span / count
        centroidX = cx
        centroidY = cy
    }
}

/**
 * Parent-level pinch detector. It observes the Initial pass, before the tile
 * clickables and the lazy list's scrollable see the event, so a child can
 * never consume the second finger (the #465/#475 failure). Single-finger taps
 * and scrolls are left untouched until the pinch starts.
 */
internal fun Modifier.galleryPinchLadder(
    vararg keys: Any?,
    newTracker: (touchSlopPx: Float) -> GalleryPinchTracker,
    /** Every sample of a started pinch: live zoom and focus, for a visual transition. */
    onZoom: ((zoom: Float, focus: Offset) -> Unit)? = null,
    /** A started pinch ended without changing stop (also after a cancel). */
    onCancelled: ((zoom: Float) -> Unit)? = null,
    onSettled: (destination: GalleryZoomStop, focus: Offset) -> Unit
): Modifier = pointerInput(*keys) {
    awaitEachGesture {
        val tracker = newTracker(viewConfiguration.touchSlop)
        val sample = PinchEventMeasurement()
        try {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            sample.read(event.changes)
            val consume = tracker.onSample(sample.pressedCount, sample.spanPx,
                sample.centroidX, sample.centroidY)
            if (consume) event.changes.forEach { it.consume() }
            if (consume && onZoom != null) onZoom(tracker.zoom, Offset(tracker.focusX, tracker.focusY))
        } while (event.changes.any { it.pressed })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (tracker.started) onCancelled?.invoke(tracker.zoom)
            throw cancelled
        }
        val destination = tracker.destination()
        if (destination != null) onSettled(destination, Offset(tracker.focusX, tracker.focusY))
        else if (tracker.started) onCancelled?.invoke(tracker.zoom)
    }
}
