package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TrackTouchWidth = 28.dp
private val ThumbHeight = 44.dp
private const val HIDE_DELAY_MS = 1_500L
/** Below this the release is a placement, not a throw (ViewConfiguration-like). */
private const val MIN_FLING_PX_PER_S = 400f

/** Track maths, shared with tests: thumb centre follows the finger, clamped. */
internal fun fastScrollFractionForTouch(touchY: Float, trackHeightPx: Float, thumbHeightPx: Float): Float {
    val travel = trackHeightPx - thumbHeightPx
    if (travel <= 0f || !touchY.isFinite()) return 0f
    return ((touchY - thumbHeightPx / 2f) / travel).coerceIn(0f, 1f)
}

/**
 * Samsung-style fast scroll (#514): a thin handle on the right edge that
 * appears while the grid scrolls and fades out after it stops. Dragging it (or
 * touching the track while it shows) jumps straight to that point of the
 * SORTED list, with a bubble beside the thumb. No permanent bar, label or %.
 * Feel (spec §0): a throw carries on with platform fling decay and settles
 * softly; a light haptic tick marks each new date/size bucket.
 *
 * Touch is only taken while the handle shows, so taps on the right column's
 * photos reach the grid while it's hidden. TalkBack can always set progress.
 */
@Composable
internal fun TimelineFastScroller(
    fraction: Float,
    label: String?,
    enabled: Boolean,
    gridScrolling: Boolean,
    alwaysShown: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp
) {
    var dragging by remember { mutableStateOf(false) }
    var flinging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val held = dragging || flinging
    LaunchedEffect(gridScrolling, held) {
        if (gridScrolling || held) shown = true
        else if (shown) { delay(HIDE_DELAY_MS); shown = false }
    }
    val visible = enabled && (shown || held || alwaysShown)
    // §0 motion: chrome fades (200 ms in, 300 ms out), never pops.
    val alpha by animateFloatAsState(if (visible) 1f else 0f,
        tween(if (visible) 200 else 300, easing = FastOutSlowInEasing), label = "fast-scroll-alpha")
    val dragCurrent by rememberUpdatedState(onDrag)
    val releaseCurrent by rememberUpdatedState(onRelease)
    val density = LocalDensity.current
    val thumbPx = with(density) { ThumbHeight.toPx() }
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    // §0 haptics: a light tick each time the bubble enters a new date/size
    // bucket while the handle is held or carried by momentum.
    val haptics = LocalHapticFeedback.current
    var tickedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(label, held) {
        if (!held) { tickedLabel = null; return@LaunchedEffect }
        if (label != null && label != tickedLabel) {
            if (tickedLabel != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            tickedLabel = label
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()
        .padding(top = topInset, bottom = LocalAtlasBottomChrome.current)) {
        val trackPx = constraints.maxHeight.toFloat()
        val travelPx = (trackPx - thumbPx).coerceAtLeast(0f)
        val thumbY = (fraction.coerceIn(0f, 1f) * travelPx).roundToInt()
        AnimatedVisibility(
            visible = held && label != null,
            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-(TrackTouchWidth + 12.dp).roundToPx(),
                    thumbY + ((thumbPx - 40.dp.toPx()) / 2f).roundToInt()) }
        ) {
            // §0 floating chrome: translucent dark, fully rounded pill.
            Box(
                Modifier.height(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .70f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label.orEmpty(), style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.testTag("atlas-fast-scroll-bubble"))
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).width(TrackTouchWidth).fillMaxHeight()
                .then(if (visible) Modifier.pointerInput(trackPx, thumbPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        // A new touch catches a running fling, like a real list.
                        flingJob?.cancel()
                        flingJob = null
                        flinging = false
                        dragging = true
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var last = fastScrollFractionForTouch(down.position.y, trackPx, thumbPx)
                        var completed = false
                        try {
                            dragCurrent(last)
                            completed = drag(down.id) { change ->
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                last = fastScrollFractionForTouch(change.position.y, trackPx, thumbPx)
                                dragCurrent(last)
                            }
                            // drag() reports only moves: add the lift itself, so a
                            // finger that paused before lifting reads as stopped
                            // (no fling) instead of carrying its last motion.
                            currentEvent.changes.firstOrNull { it.id == down.id }
                                ?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        } finally {
                            dragging = false
                            val velocityPx = if (completed) tracker.calculateVelocity().y else 0f
                            if (travelPx > 0f && abs(velocityPx) > MIN_FLING_PX_PER_S) {
                                // §0 fling: the platform spline decay at full
                                // velocity carries the thumb on and settles softly;
                                // the jump is committed only once it has stopped.
                                flinging = true
                                val start = last
                                flingJob = scope.launch {
                                    try {
                                        val anim = Animatable(start * travelPx)
                                        anim.updateBounds(0f, travelPx)
                                        anim.animateDecay(velocityPx, decay) { dragCurrent(value / travelPx) }
                                    } finally {
                                        flinging = false
                                        releaseCurrent()
                                    }
                                }
                            } else releaseCurrent()
                        }
                    }
                } else Modifier)
                .semantics {
                    stateDescription = label.orEmpty()
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                    if (enabled) setProgress { target ->
                        dragCurrent(target.coerceIn(0f, 1f)); releaseCurrent(); true
                    }
                }
                .testTag("atlas-timeline-date-scrub")
        ) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbY) }
                    .padding(end = 4.dp)
                    .width(if (held) 6.dp else 4.dp)
                    .height(ThumbHeight)
                    .alpha(alpha)
                    .clip(CircleShape)
                    // #662: the page's ink, so the thumb reads on a light page too.
                    .background(MaterialTheme.colorScheme.onSurface.let {
                        if (held) it else it.copy(alpha = .70f) })
                    .testTag("atlas-fast-scroll-thumb")
            )
        }
    }
}
