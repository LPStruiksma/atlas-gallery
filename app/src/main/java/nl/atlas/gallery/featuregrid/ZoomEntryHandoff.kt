package nl.atlas.gallery.featuregrid

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer

/**
 * #507: the visual handoff of a pinch between the square timeline and the
 * original-proportions rows. Those are separate screens that the host swaps,
 * so the leaving screen leaves the photo size and finger focus it was drawn
 * at, and the arriving screen starts drawn at that size and springs to its own
 * (Samsung parity research GRID:G2). Purely visual and one-shot: nothing here decides
 * navigation, anchors or permissions, and a missed or stale handoff only
 * means no animation. Main thread only (gesture callbacks and composition).
 */
internal object ZoomEntryHandoff {
    class Entry(
        val toOriginal: Boolean,
        val catalogueEpoch: Long,
        /** Drawn side of the photo under the fingers when the gesture ended (px). */
        val drawnTilePx: Float,
        /** Finger focus in the leaving screen's coordinates (same screen area). */
        val focus: Offset,
        val atMillis: Long
    )

    private var pending: Entry? = null

    fun offer(toOriginal: Boolean, catalogueEpoch: Long, drawnTilePx: Float, focus: Offset,
        nowMillis: Long = SystemClock.uptimeMillis()) {
        pending = if (drawnTilePx.isFinite() && drawnTilePx > 0f && focus.isValid())
            Entry(toOriginal, catalogueEpoch, drawnTilePx, focus, nowMillis) else null
    }

    /** The waiting handoff for this destination, at most once; null if stale or for another. */
    fun take(toOriginal: Boolean, catalogueEpoch: Long,
        nowMillis: Long = SystemClock.uptimeMillis()): Entry? {
        val entry = pending ?: return null
        pending = null
        return entry.takeIf {
            it.toOriginal == toOriginal && it.catalogueEpoch == catalogueEpoch &&
                nowMillis - it.atMillis in 0..MAX_AGE_MS
        }
    }

    /**
     * Layer scale and offset [progress] of the way from the handoff to rest.
     * At 0 a photo of [settledTilePx] centred on the viewport centre is drawn
     * at the handoff size, centred on the old finger focus; at 1 it is identity.
     */
    fun frame(entry: Entry, settledTilePx: Float, center: Offset, progress: Float): Triple<Float, Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        val start = if (settledTilePx > 0f) (entry.drawnTilePx / settledTilePx)
            .coerceIn(GalleryPinchTracker.MIN_ZOOM, GalleryPinchTracker.MAX_ZOOM) else 1f
        return Triple(start + (1f - start) * p,
            (entry.focus.x - center.x) * (1f - p), (entry.focus.y - center.y) * (1f - p))
    }

    const val MAX_AGE_MS = 2_500L
}

@Stable
internal class ZoomEntryState {
    var entry by mutableStateOf<ZoomEntryHandoff.Entry?>(null)
    val progress = Animatable(1f)
}

/**
 * Takes this screen's handoff the first time it has content ([ready]) and
 * plays it once. Hoist above any key() that remounts the content.
 */
@Composable
internal fun rememberZoomEntry(toOriginal: Boolean, catalogueEpoch: Long, ready: Boolean): ZoomEntryState {
    val state = remember { ZoomEntryState() }
    val taken = remember { booleanArrayOf(false) }
    LaunchedEffect(ready) {
        if (!ready || taken[0]) return@LaunchedEffect
        taken[0] = true
        val entry = ZoomEntryHandoff.take(toOriginal, catalogueEpoch) ?: return@LaunchedEffect
        state.entry = entry
        state.progress.snapTo(0f)
        state.progress.animateTo(1f, spring(
            dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        state.entry = null
    }
    return state
}

/**
 * Present only while an entry plays (state.entry changes twice per entry, so
 * this is no per-frame recomposition); per-frame values are draw-phase reads.
 */
internal fun Modifier.zoomEntryLayer(state: ZoomEntryState, settledTilePx: () -> Float): Modifier =
    // The parent clip bounds the *transformed* layer; a layer's own clip
    // only bounds its content before scaling.
    if (state.entry == null) this else clipToBounds().graphicsLayer {
        val entry = state.entry ?: return@graphicsLayer
        val (scale, dx, dy) = ZoomEntryHandoff.frame(entry, settledTilePx(),
            Offset(size.width / 2f, size.height / 2f), state.progress.value)
        scaleX = scale
        scaleY = scale
        translationX = dx
        translationY = dy
    }
