package nl.atlas.gallery.featureaiedit.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure compare geometry for the split divider, zoom and pan (AIE-21 rev 5 D and the gesture
 * contract). No Android or Compose types, so every rule is JVM-tested. Coordinates:
 * - split: fraction of the pane width shown as the original, 0..1;
 * - focus: one normalized point shared by both frames, as in CompareViewportPlanner;
 * - viewport: pane pixels.
 */
object AiEditCompareMath {
    const val MIN_ZOOM = 1.0
    const val MAX_ZOOM = 8.0
    const val DOUBLE_TAP_ZOOM = 2.5
    /** Zoom in/out accessibility step. */
    const val ZOOM_STEP = 1.5
    /** Wait before a stationary press shows the original, so a tap or double tap never flashes it. */
    const val HOLD_DELAY_MS = 120L
    /** Divider positions offered to TalkBack and by the adjustable semantics. */
    val SPLIT_STOPS = listOf(0f, .25f, .5f, .75f, 1f)

    fun clampSplit(fraction: Float): Float = if (fraction.isNaN()) .5f else fraction.coerceIn(0f, 1f)

    fun clampZoom(zoom: Double): Double =
        if (zoom.isNaN()) MIN_ZOOM else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM).let { if (it < 1.01) MIN_ZOOM else it }

    fun zoomIn(zoom: Double): Double = clampZoom(zoom * ZOOM_STEP)
    fun zoomOut(zoom: Double): Double = clampZoom(zoom / ZOOM_STEP)

    /** Double tap: zoom in around the tap, or back to fit when already zoomed. */
    fun doubleTapZoom(zoom: Double): Double = if (zoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM

    /** Next TalkBack stop above (or below) the current divider position. */
    fun stepSplit(fraction: Float, up: Boolean): Float {
        val f = clampSplit(fraction)
        return if (up) SPLIT_STOPS.firstOrNull { it > f + 1e-3f } ?: 1f
        else SPLIT_STOPS.lastOrNull { it < f - 1e-3f } ?: 0f
    }

    /** Spoken divider state: how much of the photo shows the original. */
    fun splitDescription(fraction: Float): String {
        val pct = Math.round(clampSplit(fraction) * 100)
        return "$pct percent original, ${100 - pct} percent edited"
    }

    /**
     * One haptic tick when the divider crosses the centre. It re-arms only after the divider has
     * moved [rearm] away from the centre, so tiny oscillations around 50 % never repeat it.
     */
    class CentreDetent(start: Float = .5f, private val rearm: Float = .04f) {
        private var side = sideOf(start)
        private var armed = abs(start - .5f) >= rearm

        /** Returns true when this move should tick. */
        fun update(fraction: Float): Boolean {
            val f = clampSplit(fraction)
            val now = sideOf(f)
            var tick = false
            if (armed && now != 0 && side != 0 && now != side) tick = true
            if (armed && now == 0) tick = true
            if (tick) armed = false
            if (!tick && abs(f - .5f) >= rearm) armed = true
            if (now != 0) side = now
            return tick
        }

        private fun sideOf(f: Float): Int = when {
            f < .5f - 1e-4f -> -1
            f > .5f + 1e-4f -> 1
            else -> 0
        }
    }

    /**
     * The focus range that actually moves the picture: once the scaled image is wider than the
     * pane, focus beyond this range would only be clamped away by the planner and make the next
     * pan feel stuck. A side that fits stays centred.
     */
    fun effectiveFocus(focus: Double, viewportSide: Double, scaledSide: Double): Double {
        if (!focus.isFinite() || scaledSide <= viewportSide || scaledSide <= 0.0) return .5
        val half = viewportSide / (2.0 * scaledSide)
        return focus.coerceIn(half, 1.0 - half)
    }

    /** Fit scale of an image in the pane (zoom 1), as CompareViewportPlanner uses. */
    fun fitScale(imageW: Int, imageH: Int, viewportW: Double, viewportH: Double): Double =
        min(viewportW / imageW, viewportH / imageH)

    data class View(val focusX: Double, val focusY: Double, val zoom: Double)

    /** Pan by a finger movement in pane pixels; content follows the finger. */
    fun pan(view: View, dx: Double, dy: Double, imageW: Int, imageH: Int, viewportW: Double, viewportH: Double): View {
        val scale = fitScale(imageW, imageH, viewportW, viewportH) * view.zoom
        val sw = imageW * scale
        val sh = imageH * scale
        return view.copy(
            focusX = effectiveFocus(effectiveFocus(view.focusX, viewportW, sw) - dx / sw, viewportW, sw),
            focusY = effectiveFocus(effectiveFocus(view.focusY, viewportH, sh) - dy / sh, viewportH, sh)
        )
    }

    /**
     * Zoom to [newZoom] keeping the image point under [anchorX], [anchorY] (pane pixels) where it
     * is, as far as the edges allow. Used by pinch (anchor = centroid) and double tap.
     */
    fun zoomAround(
        view: View, newZoom: Double, anchorX: Double, anchorY: Double,
        imageW: Int, imageH: Int, viewportW: Double, viewportH: Double
    ): View {
        val fit = fitScale(imageW, imageH, viewportW, viewportH)
        val z0 = clampZoom(view.zoom)
        val z1 = clampZoom(newZoom)
        val sw0 = imageW * fit * z0
        val sh0 = imageH * fit * z0
        val sw1 = imageW * fit * z1
        val sh1 = imageH * fit * z1
        // Image origin before the change, matching CompareViewportPlanner.origin.
        val left0 = origin(viewportW, sw0, effectiveFocus(view.focusX, viewportW, sw0))
        val top0 = origin(viewportH, sh0, effectiveFocus(view.focusY, viewportH, sh0))
        val px = ((anchorX - left0) / sw0).coerceIn(0.0, 1.0)
        val py = ((anchorY - top0) / sh0).coerceIn(0.0, 1.0)
        // Solve origin(viewport, sw1, f) + p * sw1 = anchor for f (unclamped branch).
        val fx = (viewportW / 2.0 - anchorX) / sw1 + px
        val fy = (viewportH / 2.0 - anchorY) / sh1 + py
        return View(effectiveFocus(fx, viewportW, sw1), effectiveFocus(fy, viewportH, sh1), z1)
    }

    /** Same origin rule as CompareViewportPlanner. */
    fun origin(viewportSide: Double, scaledSide: Double, focus: Double): Double =
        if (scaledSide <= viewportSide) (viewportSide - scaledSide) / 2.0
        else (viewportSide / 2.0 - focus * scaledSide).coerceIn(viewportSide - scaledSide, 0.0)

    /** EXIF orientations, by value, so this stays free of android.media. */
    object Orientation {
        const val NORMAL = 1
        const val FLIP_HORIZONTAL = 2
        const val ROTATE_180 = 3
        const val FLIP_VERTICAL = 4
        const val TRANSPOSE = 5
        const val ROTATE_90 = 6
        const val TRANSVERSE = 7
        const val ROTATE_270 = 8
        fun swapsAxes(o: Int) = o == TRANSPOSE || o == ROTATE_90 || o == TRANSVERSE || o == ROTATE_270
    }

    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * Maps a crop in upright (as displayed) coordinates to the stored pixel grid, so a region
     * decoder can read just that crop before the EXIF orientation is applied to it.
     * [rawW] x [rawH] is the stored size.
     */
    fun uprightToRaw(r: IntRect, orientation: Int, rawW: Int, rawH: Int): IntRect {
        val out = when (orientation) {
            Orientation.FLIP_HORIZONTAL -> IntRect(rawW - r.right, r.top, rawW - r.left, r.bottom)
            Orientation.ROTATE_180 -> IntRect(rawW - r.right, rawH - r.bottom, rawW - r.left, rawH - r.top)
            Orientation.FLIP_VERTICAL -> IntRect(r.left, rawH - r.bottom, r.right, rawH - r.top)
            // Upright (x, y) = raw (y, x).
            Orientation.TRANSPOSE -> IntRect(r.top, r.left, r.bottom, r.right)
            // Rotate 90 clockwise: raw x = upright y, raw y = rawH - upright x.
            Orientation.ROTATE_90 -> IntRect(r.top, rawH - r.right, r.bottom, rawH - r.left)
            // Transverse: upright (x, y) = raw (rawW - y, rawH - x).
            Orientation.TRANSVERSE -> IntRect(rawW - r.bottom, rawH - r.right, rawW - r.top, rawH - r.left)
            // Rotate 270 clockwise: raw x = rawW - y, raw y = x.
            Orientation.ROTATE_270 -> IntRect(rawW - r.bottom, r.left, rawW - r.top, r.right)
            else -> r
        }
        return IntRect(max(0, out.left), max(0, out.top), min(rawW, out.right), min(rawH, out.bottom))
    }
}
