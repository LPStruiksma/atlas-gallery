package nl.atlas.gallery

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * #23: a measured, one-shot photo entry. The value holds only a window rectangle
 * and the same in-memory MediaRecord already available in the authorized grid;
 * no bitmaps, thumbnail bytes, file paths or saved state. Never use an index:
 * dated grid headers and an intervening scrub make it ambiguous.
 */
internal data class SharedPhotoOrigin(
    val media: MediaRecord, val epoch: Long, val bounds: Rect, val atMillis: Long
)

/** Main-thread-only click handoff; consuming it also clears any stale offer. */
internal object SharedPhotoOrigins {
    private var pending: SharedPhotoOrigin? = null
    const val MAX_AGE_MS = 1_500L

    fun offer(media: MediaRecord, epoch: Long, bounds: Rect?,
        nowMillis: Long = SystemClock.uptimeMillis()
    ) {
        pending = if (!media.isVideo && epoch >= 0 && bounds != null &&
            bounds.width > 0f && bounds.height > 0f &&
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }
        ) SharedPhotoOrigin(media, epoch, bounds, nowMillis) else null
    }

    fun take(uri: String, epoch: Long,
        nowMillis: Long = SystemClock.uptimeMillis()
    ): SharedPhotoOrigin? {
        val entry = pending
        pending = null
        return entry?.takeIf {
            it.media.uri == uri && it.epoch == epoch &&
                nowMillis - it.atMillis in 0..MAX_AGE_MS
        }
    }

    fun clear() { pending = null }
}

internal fun sharedPhotoAnimationDisabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f ||
            Settings.Global.getFloat(context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) <= 0f
    }.getOrDefault(false)

internal fun sharedPhotoRect(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun between(a: Float, b: Float) = a + (b - a) * p
    return Rect(between(from.left, to.left), between(from.top, to.top),
        between(from.right, to.right), between(from.bottom, to.bottom))
}

private const val SHARED_PHOTO_MS = 210

/**
 * The viewer owns Back and settled-URI semantics as before. The image-only
 * overlay travels from the measured thumbnail to the full viewer, while the
 * viewer fades in underneath; on an unchanged URI Back reverses that travel.
 * The source grid is unmounted while the viewer is live. If a scrub changed
 * the URI, callers close normally rather than animating to the WRONG tile.
 */
@Composable
internal fun SharedPhotoMotionLayer(
    origin: SharedPhotoOrigin?,
    exiting: Boolean,
    onExitFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val disabled = remember(origin) { sharedPhotoAnimationDisabled(context) }
    val animated = origin != null && !disabled
    val progress = remember(origin) { Animatable(if (animated) 0f else 1f) }
    val currentOnExit = androidx.compose.runtime.rememberUpdatedState(onExitFinished)
    var windowRect by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(origin, exiting, animated) {
        if (exiting) {
            if (animated) progress.animateTo(0f,
                tween(SHARED_PHOTO_MS, easing = FastOutSlowInEasing))
            currentOnExit.value()
        } else if (animated) {
            progress.animateTo(1f, tween(SHARED_PHOTO_MS, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier.fillMaxSize().clipToBounds().background(Color.Black)
        .onGloballyPositioned { coords ->
            val now = coords.boundsInWindow()
            if (now != windowRect) windowRect = now
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (animated) progress.value else 1f
        }) { content() }

        // Draw only while a measured, authorized entry is moving. During the
        // transition the image is a single low-resolution copy; GalleryViewer
        // retains ownership of the full-res decode and of all gestures.
        if (animated && origin != null && windowRect.width > 0f &&
            windowRect.height > 0f && (progress.value < 1f || exiting)
        ) {
            val viewport = Rect(windowRect.left, windowRect.top,
                windowRect.right, windowRect.bottom)
            val frame = sharedPhotoRect(origin.bounds, viewport, progress.value)
            val density = LocalDensity.current
            Box(Modifier
                .offset {
                    IntOffset((frame.left - windowRect.left).roundToInt(),
                        (frame.top - windowRect.top).roundToInt())
                }
                .size(with(density) { frame.width.coerceAtLeast(1f).toDp() },
                    with(density) { frame.height.coerceAtLeast(1f).toDp() })
                .alpha(1f - progress.value)
                .testTag(if (exiting) "atlas-shared-photo-exit"
                    else "atlas-shared-photo-enter")) {
                MediaPreview(origin.media, Modifier.fillMaxSize(),
                    ContentScale.Crop, 600)
            }
        }
    }
}
