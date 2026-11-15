package nl.atlas.gallery.featuredesign

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * DESIGN-7. Small, repeatable motion vocabulary for ordinary gallery chrome.
 * This deliberately excludes the grid-to-viewer shared-element transition (#23).
 * Android's animator duration scale is honoured by Compose's duration-scale clock;
 * the system accessibility remove-animations switch also gives an explicit
 * zero-duration spec here instead of allowing stale transitions to linger.
 */
enum class AtlasMotionKind { TAB, SHEET, SELECTION, PRESS, PILL, HEADER }

data class AtlasMotionSpec(
    val durationMillis: Int,
    val delayMillis: Int = 0
)

/** Samsung-style restrained ease-out. Selection/headers share the same pacing. */
val AtlasMotionEase: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** A pure policy function so the reduced-motion contract is JVM-testable. */
fun atlasMotionSpec(kind: AtlasMotionKind, reduceMotion: Boolean): AtlasMotionSpec {
    if (reduceMotion) return AtlasMotionSpec(durationMillis = 0)
    return AtlasMotionSpec(
        when (kind) {
            AtlasMotionKind.TAB -> 180
            AtlasMotionKind.SHEET -> 240
            AtlasMotionKind.SELECTION -> 200
            AtlasMotionKind.PRESS -> 120
            AtlasMotionKind.PILL -> 200
            AtlasMotionKind.HEADER -> 200
        }
    )
}

/**
 * Android Settings > Accessibility > Remove animations maps to
 * ANIMATOR_DURATION_SCALE=0. Reading it from the view context avoids an
 * Activity cast and also covers Compose previews without an Activity.
 * Compose's own animation clock separately honours developer animator scale.
 */
@Composable
fun atlasReduceMotion(): Boolean {
    val context = LocalView.current.context
    val durationScale = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)
    return durationScale <= 0f
}

@Composable
fun <T> atlasTween(kind: AtlasMotionKind): androidx.compose.animation.core.TweenSpec<T> {
    val spec = atlasMotionSpec(kind, atlasReduceMotion())
    return tween(durationMillis = spec.durationMillis, delayMillis = spec.delayMillis,
        easing = AtlasMotionEase)
}
