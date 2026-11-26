package nl.atlas.gallery

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
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
import nl.atlas.gallery.featuredesign.AtlasMotion

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

/** Only the requested tile reports geometry; no catalogue-sized bounds cache. */
internal class MeasuredViewerReturn(val request: PendingViewerReturn) {
    var measured by mutableStateOf<SharedPhotoOrigin?>(null)
        private set

    fun offer(media: MediaRecord, epoch: Long, bounds: Rect, nowMillis: Long) {
        if (media.uri != request.uri || epoch != request.accessEpoch) return
        measured = if (!media.isVideo && bounds.width > 0f && bounds.height > 0f &&
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
            SharedPhotoOrigin(media, epoch, bounds, nowMillis) else null
    }

    fun clear() { measured = null }
}

internal val LocalMeasuredViewerReturn = compositionLocalOf<MeasuredViewerReturn?> { null }

@Composable
internal fun Modifier.measureViewerReturn(media: MediaRecord, epoch: Long): Modifier {
    val target = LocalMeasuredViewerReturn.current ?: return this
    if (target.request.uri != media.uri || target.request.accessEpoch != epoch || media.isVideo)
        return this
    DisposableEffect(target, media.uri, epoch) {
        onDispose { target.clear() }
    }
    return onGloballyPositioned { coordinates ->
        target.offer(media, epoch, coordinates.boundsInWindow(), SystemClock.uptimeMillis())
    }
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


/** Local fitted-image rectangle at the instant a gesture commits. */
internal fun sharedPhotoGestureFrame(viewportWidth: Int, viewportHeight: Int,
    imageWidth: Int, imageHeight: Int, pose: ViewerPinchExitPose): Rect? {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 ||
        !pose.scale.isFinite() || pose.scale <= 0f || pose.scale > 1f ||
        !pose.x.isFinite() || !pose.y.isFinite()) return null
    val fit = minOf(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
    val halfWidth = imageWidth * fit * pose.scale / 2f
    val halfHeight = imageHeight * fit * pose.scale / 2f
    val cx = viewportWidth / 2f + pose.x
    val cy = viewportHeight / 2f + pose.y
    return Rect(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
}

/**
 * The viewer owns Back and settled-URI semantics as before. The image-only
 * overlay travels from the measured thumbnail to the full viewer, while the
 * viewer fades in underneath. Exit uses a separately measured current tile,
 * or fades when no authorized target was obtained. Entry state is unchanged.
 */
@Composable
internal fun SharedPhotoMotionLayer(
    origin: SharedPhotoOrigin?,
    exiting: Boolean,
    onExitFinished: () -> Unit,
    modifier: Modifier = Modifier,
    returnOrigin: SharedPhotoOrigin? = origin,
    allowUnderlay: Boolean = false,
    exitWithoutUnderlay: Boolean = false,
    gestureFrame: Rect? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val disabled = remember(origin) { sharedPhotoAnimationDisabled(context) }
    val exitDisabled = remember(exiting) { exiting && sharedPhotoAnimationDisabled(context) }
    val animated = origin != null && !disabled
    val progress = remember(origin) { Animatable(if (animated) 0f else 1f) }
    val currentOnExit = androidx.compose.runtime.rememberUpdatedState(onExitFinished)
    var windowRect by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(origin, exiting, animated) {
        if (exiting) {
            if (!exitDisabled) {
                progress.animateTo(0f,
                    tween(AtlasMotion.SHARED_ELEMENT_MS, easing = AtlasMotion.SineInOut70))
            }
            currentOnExit.value()
        } else if (animated) {
            progress.animateTo(1f, tween(AtlasMotion.SHARED_ELEMENT_MS, easing = AtlasMotion.SineInOut70))
        }
    }

    val movingOrigin = if (exiting) returnOrigin else origin
    val moving = if (exiting) !exitDisabled else animated
    Box(modifier.fillMaxSize().clipToBounds().drawBehind {
        if (exiting && exitWithoutUnderlay) drawRect(Color.Black)
        else if (exiting && !allowUnderlay) drawRect(Color.Black.copy(alpha = progress.value))
        else if (!exiting && (!allowUnderlay || progress.value < 1f)) drawRect(Color.Black)
    }
        .onGloballyPositioned { coords ->
            val now = coords.boundsInWindow()
            if (now != windowRect) windowRect = now
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (animated || exiting) progress.value else 1f
        }) { content() }

        // Draw only while a measured, authorized entry is moving. During the
        // transition the image is a single low-resolution copy; GalleryViewer
        // retains ownership of the full-res decode and of all gestures.
        if (moving && movingOrigin != null && windowRect.width > 0f &&
            windowRect.height > 0f && (progress.value < 1f || exiting)
        ) {
            val viewport = if (exiting && gestureFrame != null) Rect(
                windowRect.left + gestureFrame.left, windowRect.top + gestureFrame.top,
                windowRect.left + gestureFrame.right, windowRect.top + gestureFrame.bottom)
            else Rect(windowRect.left, windowRect.top, windowRect.right, windowRect.bottom)
            val frame = sharedPhotoRect(movingOrigin.bounds, viewport, progress.value)
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
                MediaPreview(movingOrigin.media, Modifier.fillMaxSize(),
                    ContentScale.Crop, 600)
            }
        }
    }
}
