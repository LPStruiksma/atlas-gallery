package nl.atlas.gallery.featuredesign

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * FLOW (#710): the one set of motion values every Atlas transition uses, so
 * gestures and transitions move the same way everywhere.
 *
 * Behaviour follows Samsung Gallery (parity research GRID:G3, VIEW:V20,
 * VIEW:V23; `docs/product/SAMSUNG_PARITY_SPEC.md` Motion row): sine in-out
 * curves, 160 ms double-tap zoom, 200 ms shared element, 350 ms pinch
 * settle. The curves are the One UI sine in-out family, written as cubic
 * Béziers (0.33, 0) → (x, 1); a lower x eases out more strongly.
 */
object AtlasMotion {
    /** Strong ease-out sine in-out: layout changes such as a pinch settle. */
    val SineInOut80: Easing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)

    /** Medium sine in-out: zooms and shared-element moves. */
    val SineInOut70: Easing = CubicBezierEasing(0.33f, 0f, 0.3f, 1f)

    /** Full-length settle of a grid pinch after release (GRID:G3). */
    const val PINCH_SETTLE_MS = 350

    /** Shortest pinch settle, so a nearly finished pinch still eases in. */
    const val PINCH_SETTLE_MIN_MS = 120

    /** Viewer double-tap zoom (VIEW:V20). */
    const val DOUBLE_TAP_ZOOM_MS = 160

    /** Grid tile ↔ viewer shared element (VIEW:V23). */
    const val SHARED_ELEMENT_MS = 200

    /**
     * True when the system "Remove animations" setting (animator duration
     * scale 0) is on. Then transitions cut straight to their end; motion that
     * follows the finger still follows it, because that is not an animation.
     */
    fun animationsDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
    }.getOrDefault(false)
}
