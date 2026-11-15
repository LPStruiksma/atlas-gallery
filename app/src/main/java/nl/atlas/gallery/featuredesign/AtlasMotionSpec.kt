package nl.atlas.gallery.featuredesign

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper

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
 * Keep the reduced-motion flag in sync while the app is in the foreground.
 * Some devices toggle the accessibility setting without Activity recreation;
 * observing this key avoids continuing an old animation until the next restart.
 */
@Composable
fun atlasReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val key = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
    var reduced by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(key), false, observer
        )
        reduced = readReducedMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(resolver: ContentResolver): Boolean =
    runCatching {
        android.provider.Settings.Global.getFloat(
            resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) <= 0f
    }.getOrDefault(false)

@Composable
fun <T> atlasTween(kind: AtlasMotionKind): androidx.compose.animation.core.TweenSpec<T> {
    val spec = atlasMotionSpec(kind, atlasReduceMotion())
    return tween(durationMillis = spec.durationMillis, delayMillis = spec.delayMillis,
        easing = AtlasMotionEase)
}
