package nl.atlas.gallery.featureaiedit.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureaiedit.preview.CompareFrameViewportPlan
import nl.atlas.gallery.featureaiedit.preview.CompareNormalizedPoint
import nl.atlas.gallery.featureaiedit.preview.CompareViewportPlan
import nl.atlas.gallery.featureaiedit.preview.CompareViewportPlanner
import nl.atlas.gallery.featureaiedit.preview.CompareViewportSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Viewer state that survives switching results: compare mode, divider, zoom and the one
 * normalized focus both frames share (AIE-21 rev 5 D). Hoisted so the filmstrip can change the
 * edited frame without losing the user's crop.
 */
@Stable
internal class AiEditViewerState {
    var compare by mutableStateOf(false)
        private set
    var split by mutableFloatStateOf(.5f)
        private set
    var zoom by mutableDoubleStateOf(1.0)
        private set
    var focusX by mutableDoubleStateOf(.5)
        private set
    var focusY by mutableDoubleStateOf(.5)
        private set
    /** Finger held on the photo (outside the grip): the full original shows until release. */
    var holding by mutableStateOf(false)
    /** "Show original" from TalkBack or the accessibility action; same effect as holding. */
    var pinnedOriginal by mutableStateOf(false)
    /** Bytes of the currently decoded preview frames (evidence for the 8 MiB budget). */
    var decodedBytes by mutableLongStateOf(0L)
        internal set
    /** False while a new crop is being decoded for the current view (evidence waits on it). */
    var settled by mutableStateOf(false)
        internal set
    /** Number of centre haptic ticks so far (evidence; the tick itself goes to the system). */
    var centreTicks by mutableStateOf(0)
        private set
    private var detent = AiEditCompareMath.CentreDetent()

    val showingOriginal: Boolean get() = holding || pinnedOriginal

    /** "Compare" starts at 50/50; no automatic divider motion afterwards. */
    fun startCompare() {
        compare = true
        split = .5f
        detent = AiEditCompareMath.CentreDetent(.5f)
        pinnedOriginal = false
    }

    fun endCompare() { compare = false }

    /** Moves the divider; returns true when the move crossed the centre (one haptic tick). */
    fun moveSplit(fraction: Float): Boolean {
        split = AiEditCompareMath.clampSplit(fraction)
        val tick = detent.update(split)
        if (tick) centreTicks++
        return tick
    }

    fun setView(view: AiEditCompareMath.View) {
        zoom = AiEditCompareMath.clampZoom(view.zoom)
        if (zoom == AiEditCompareMath.MIN_ZOOM) { focusX = .5; focusY = .5 }
        else { focusX = view.focusX.coerceIn(0.0, 1.0); focusY = view.focusY.coerceIn(0.0, 1.0) }
    }

    fun resetZoom() = setView(AiEditCompareMath.View(.5, .5, 1.0))
    val view: AiEditCompareMath.View get() = AiEditCompareMath.View(focusX, focusY, zoom)
}

private val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

/** Tracks the last completed tap for double-tap detection across gestures. */
private class TapMemory { var upTime = 0L; var position = Offset.Zero; var valid = false }

/**
 * Photo pane of the editor: fit on black, with hold-to-original, double-tap and pinch zoom,
 * one-finger pan when zoomed, and the split divider in compare mode. Both frames are decoded
 * through [AiEditPreviewDecoder] with the planner's bounded crop; Coil is used only when the
 * bounded decoder can't read the file at all.
 */
@Composable
internal fun AiEditCompareViewer(
    state: AiEditViewerState,
    originalUri: Uri,
    editedUri: Uri?,
    editedLabel: String,
    showHoldHint: Boolean,
    onHoldShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val decoder = remember(context) { AiEditPreviewDecoder(context.contentResolver) }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var originalInfo by remember(originalUri) { mutableStateOf<AiEditPreviewDecoder.Info?>(null) }
    var originalFailed by remember(originalUri) { mutableStateOf(false) }
    var editedInfo by remember(editedUri) { mutableStateOf<AiEditPreviewDecoder.Info?>(null) }
    var editedFailed by remember(editedUri) { mutableStateOf(false) }
    LaunchedEffect(originalUri) {
        originalInfo = withContext(Dispatchers.IO) { decoder.info(originalUri) }
        originalFailed = originalInfo == null
    }
    LaunchedEffect(editedUri) {
        if (editedUri == null) return@LaunchedEffect
        editedInfo = withContext(Dispatchers.IO) { decoder.info(editedUri) }
        editedFailed = editedInfo == null
    }
    val hasEdited = editedUri != null
    val original = originalInfo
    val edited = if (hasEdited) editedInfo else original
    val plan: CompareViewportPlan? = if (original != null && edited != null &&
        paneSize.width > 0 && paneSize.height > 0) runCatching {
        CompareViewportPlanner.plan(original.upright, edited.upright,
            CompareViewportSize(paneSize.width.coerceAtMost(CompareViewportPlanner.MAX_VIEWPORT_SIDE),
                paneSize.height.coerceAtMost(CompareViewportPlanner.MAX_VIEWPORT_SIDE)),
            CompareNormalizedPoint(state.focusX, state.focusY), state.zoom)
    }.getOrNull() else null

    class Decoded(val frame: AiEditPreviewDecoder.Frame, val image: ImageBitmap)
    var originalFrame by remember(originalUri) { mutableStateOf<Decoded?>(null) }
    var editedFrame by remember(editedUri) { mutableStateOf<Decoded?>(null) }
    // Decode after the view settles; while a gesture runs the last frames are re-projected.
    LaunchedEffect(plan?.original?.previewDecode, plan?.candidate?.previewDecode, originalUri, editedUri) {
        val p = plan ?: return@LaunchedEffect
        state.settled = false
        if (originalFrame != null || editedFrame != null) delay(90)
        val o = original ?: return@LaunchedEffect
        val wantOriginal = originalFrame?.frame?.let { it.source.matches(p.original) && it.bytes > 0 } != true
        val newOriginal = if (wantOriginal) withContext(Dispatchers.IO) {
            decoder.decode(originalUri, o, p.original.previewDecode)
        } else null
        if (newOriginal != null) originalFrame = Decoded(newOriginal, newOriginal.bitmap.asImageBitmap())
        val e = editedInfo
        if (editedUri != null && e != null) {
            val wantEdited = editedFrame?.frame?.source?.matches(p.candidate) != true
            val newEdited = if (wantEdited) withContext(Dispatchers.IO) {
                decoder.decode(editedUri, e, p.candidate.previewDecode)
            } else null
            if (newEdited != null) editedFrame = Decoded(newEdited, newEdited.bitmap.asImageBitmap())
        }
        state.decodedBytes = (originalFrame?.frame?.bytes ?: 0L) + (if (hasEdited) editedFrame?.frame?.bytes ?: 0L else 0L)
        state.settled = true
    }

    val showOriginalNow = !hasEdited || state.showingOriginal
    val gripHalf = with(density) { 24.dp.toPx() }
    val currentEdited by rememberUpdatedState(hasEdited)
    val currentPlanInputs by rememberUpdatedState(Triple(original, edited, paneSize))
    val tapMemory = remember { TapMemory() }
    val holdShown by rememberUpdatedState(onHoldShown)

    fun applyView(next: AiEditCompareMath.View) = state.setView(next)

    Box(
        modifier
            .background(AiEditColors.Ground)
            .onSizeChanged { paneSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = paneSize.width.toFloat().coerceAtLeast(1f)
                    // 1. The divider grip captures horizontal drag.
                    if (state.compare && currentEdited && abs(down.position.x - state.split * w) <= gripHalf) {
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (state.moveSplit(change.position.x / w))
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }
                    val (o, e, s) = currentPlanInputs
                    val img = (if (currentEdited) e else o)?.upright
                    val vw = s.width.toDouble()
                    val vh = s.height.toDouble()
                    // 2. Double tap outside the grip zooms around the tapped point (or resets).
                    val slop = viewConfiguration.touchSlop
                    if (tapMemory.valid &&
                        down.uptimeMillis - tapMemory.upTime <= viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - tapMemory.position).getDistance() <= slop * 4) {
                        tapMemory.valid = false
                        if (img != null && vw > 0 && vh > 0) {
                            val target = AiEditCompareMath.doubleTapZoom(state.zoom)
                            applyView(if (target == AiEditCompareMath.MIN_ZOOM) AiEditCompareMath.View(.5, .5, 1.0)
                            else AiEditCompareMath.zoomAround(state.view, target, down.position.x.toDouble(),
                                down.position.y.toDouble(), img.width, img.height, vw, vh))
                        }
                        do { val ev = awaitPointerEvent() } while (ev.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    // 3. Hold shows the original; movement past slop cancels the pending hold;
                    //    a second finger pinches/pans; one finger pans only when zoomed.
                    var moved = false
                    var multi = false
                    var travel = Offset.Zero
                    var upTime = down.uptimeMillis
                    var lastTime = down.uptimeMillis
                    val holdAt = down.uptimeMillis + AiEditCompareMath.HOLD_DELAY_MS
                    while (true) {
                        val waitingForHold = !state.holding && !moved && !multi && currentEdited
                        val event = if (waitingForHold) {
                            withTimeoutOrNull((holdAt - lastTime).coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        event?.changes?.maxOfOrNull { it.uptimeMillis }?.let { lastTime = maxOf(lastTime, it) }
                        if (event == null) {
                            if (!moved && !multi && currentEdited) { state.holding = true; holdShown() }
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            upTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: upTime
                            break
                        }
                        if (pressed.size >= 2) {
                            multi = true
                            state.holding = false
                            if (img != null && vw > 0 && vh > 0) {
                                val zoomChange = event.calculateZoom().toDouble()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                var next = AiEditCompareMath.zoomAround(state.view, state.zoom * zoomChange,
                                    centroid.x.toDouble(), centroid.y.toDouble(), img.width, img.height, vw, vh)
                                next = AiEditCompareMath.pan(next, pan.x.toDouble(), pan.y.toDouble(),
                                    img.width, img.height, vw, vh)
                                applyView(next)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.first()
                        travel += change.positionChange()
                        if (!moved && travel.getDistance() > slop) {
                            moved = true
                            state.holding = false
                        }
                        // Event time alone can also reach the hold delay (no timer needed).
                        if (!moved && !state.holding && currentEdited && lastTime >= holdAt) {
                            state.holding = true
                            holdShown()
                        }
                        if (moved && state.zoom > AiEditCompareMath.MIN_ZOOM && img != null && vw > 0 && vh > 0) {
                            val d = change.positionChange()
                            applyView(AiEditCompareMath.pan(state.view, d.x.toDouble(), d.y.toDouble(),
                                img.width, img.height, vw, vh))
                            change.consume()
                        }
                    }
                    val wasHolding = state.holding
                    state.holding = false
                    if (!moved && !multi && !wasHolding &&
                        upTime - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis) {
                        tapMemory.valid = true
                        tapMemory.upTime = upTime
                        tapMemory.position = down.position
                    } else tapMemory.valid = false
                }
            }
            .semantics {
                contentDescription = when {
                    !hasEdited -> "Original photo"
                    state.compare -> "Comparison of original and $editedLabel"
                    showOriginalNow -> "Original photo"
                    else -> "Edited photo, $editedLabel"
                }
                val actions = mutableListOf<CustomAccessibilityAction>()
                if (hasEdited) {
                    actions += CustomAccessibilityAction(if (state.compare) "Exit compare" else "Compare") {
                        if (state.compare) state.endCompare() else state.startCompare(); true
                    }
                    if (!state.compare) actions += CustomAccessibilityAction(
                        if (state.pinnedOriginal) "Show edited" else "Show original") {
                        state.pinnedOriginal = !state.pinnedOriginal; true
                    }
                }
                actions += CustomAccessibilityAction("Zoom in") { applyView(state.view.copy(zoom = AiEditCompareMath.zoomIn(state.zoom))); true }
                if (state.zoom > AiEditCompareMath.MIN_ZOOM) {
                    actions += CustomAccessibilityAction("Zoom out") { applyView(state.view.copy(zoom = AiEditCompareMath.zoomOut(state.zoom))); true }
                    actions += CustomAccessibilityAction("Reset zoom") { state.resetZoom(); true }
                }
                customActions = actions
            }
    ) {
        val fallback = (originalFailed && showOriginalNow) || (hasEdited && editedFailed && !showOriginalNow)
        if (fallback || plan == null) {
            if (originalFailed || editedFailed) AsyncImage(
                model = if (showOriginalNow) originalUri else editedUri,
                contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            val oFrame = originalFrame
            val eFrame = editedFrame
            Canvas(Modifier.fillMaxSize().testTag("atlas-ai-edit-canvas")) {
                if (hasEdited && state.compare) {
                    val x = size.width * state.split
                    clipRect(right = x) { oFrame?.let { drawFrame(it.frame, it.image, plan.original) } }
                    clipRect(left = x) { eFrame?.let { drawFrame(it.frame, it.image, plan.candidate) } }
                    val line = 2.dp.toPx()
                    drawRect(AiEditColors.OnBrand, topLeft = Offset(x - line / 2, 0f),
                        size = androidx.compose.ui.geometry.Size(line, size.height))
                    val grip = 16.dp.toPx()
                    val c = Offset(x, size.height / 2)
                    drawCircle(AiEditColors.OnBrand, radius = grip, center = c)
                    val a = 5.dp.toPx()
                    for (dir in listOf(-1f, 1f)) {
                        val tip = Offset(c.x + dir * 9.dp.toPx(), c.y)
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(tip.x - dir * a, tip.y - a)
                            lineTo(tip.x - dir * a, tip.y + a)
                            close()
                        }
                        drawPath(path, AiEditColors.Slab)
                    }
                } else if (showOriginalNow) {
                    oFrame?.let { drawFrame(it.frame, it.image, plan.original) }
                } else {
                    eFrame?.let { drawFrame(it.frame, it.image, plan.candidate) }
                }
            }
        }
        if (hasEdited && state.compare) {
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp)) {
                ViewerPill("Original", Modifier.testTag("atlas-ai-edit-label-original"))
                Box(Modifier.weight(1f))
                ViewerPill("Edited", Modifier.testTag("atlas-ai-edit-label-edited"))
            }
            // The 48 dp accessible divider: adjustable for TalkBack, no pointer handling of its own.
            val splitX = with(density) { (paneSize.width * state.split).toDp() }
            Box(
                Modifier.fillMaxHeight().width(48.dp).offset(x = splitX - 24.dp)
                    .testTag("atlas-ai-edit-divider")
                    .semantics {
                        contentDescription = "Comparison divider"
                        stateDescription = AiEditCompareMath.splitDescription(state.split)
                        progressBarRangeInfo = ProgressBarRangeInfo(state.split, 0f..1f, steps = 3)
                        setProgress { value ->
                            if (state.moveSplit(value)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            true
                        }
                        customActions = AiEditCompareMath.SPLIT_STOPS.map { stop ->
                            CustomAccessibilityAction("Show ${(stop * 100).roundToInt()} percent original") {
                                state.moveSplit(stop); true
                            }
                        }
                    }
            )
        }
        if (hasEdited && !state.compare && (showHoldHint || showOriginalNow)) Text(
            if (showOriginalNow) "Original" else "Hold to see original",
            style = Secondary13, color = AiEditColors.Ink,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                .background(AiEditColors.Surface, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("atlas-ai-edit-compare-hint")
        )
        if (state.zoom > AiEditCompareMath.MIN_ZOOM) Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).heightIn(min = 48.dp)
                .background(AiEditColors.Surface, RoundedCornerShape(24.dp))
                .clickable { state.resetZoom() }
                .padding(horizontal = 14.dp)
                .testTag("atlas-ai-edit-zoom-reset"),
            contentAlignment = Alignment.Center
        ) { Text("Reset zoom", style = Label, color = AiEditColors.Ink) }
    }
}

private val Secondary13 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun ViewerPill(text: String, modifier: Modifier) {
    Text(text, style = Label, color = AiEditColors.Ink,
        modifier = modifier.background(AiEditColors.Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

private fun AiEditCompareMath.IntRect.matches(plan: CompareFrameViewportPlan): Boolean =
    left == plan.visibleSource.left && top == plan.visibleSource.top &&
        right == plan.visibleSource.right && bottom == plan.visibleSource.bottom

/** Projects a decoded crop through the live plan, so pinch and pan stay smooth between decodes. */
private fun DrawScope.drawFrame(frame: AiEditPreviewDecoder.Frame, image: ImageBitmap, plan: CompareFrameViewportPlan) {
    val s = frame.source
    val l = plan.imageLeft + s.left * plan.scale
    val t = plan.imageTop + s.top * plan.scale
    val r = plan.imageLeft + s.right * plan.scale
    val b = plan.imageTop + s.bottom * plan.scale
    val w = (r - l).roundToInt().coerceAtLeast(1)
    val h = (b - t).roundToInt().coerceAtLeast(1)
    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(l.roundToInt(), t.roundToInt()), dstSize = IntSize(w, h),
        filterQuality = FilterQuality.Medium)
}
