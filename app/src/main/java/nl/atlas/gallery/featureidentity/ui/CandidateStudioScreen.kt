package nl.atlas.gallery.featureidentity.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureidentity.engine.FaceBox
import kotlin.math.abs

/** Shared zoom for the canvas: it carries across swipes so a detail is compared at the same spot. */
internal class SwapZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    fun reset() { scale = 1f; offset = Offset.Zero }
    fun clamp(w: Float, h: Float) {
        val mx = (scale - 1f) * w / 2f
        val my = (scale - 1f) * h / 2f
        offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
    }
}

/** Plain TalkBack label for a strip tile or pager page. */
internal fun versionLabel(state: SwapPersonUiState, key: Int?): String {
    if (key == null) return "Original"
    val index = state.versions.indexOfFirst { it.key == key }
    val v = state.versions.getOrNull(index) ?: return "Original"
    val parts = mutableListOf("Version ${index + 1} of ${state.versions.size}")
    if (key == state.selected) parts += "selected"
    if (v.hair && state.headCapable) parts += "with their hair"
    when (v.status) {
        VersionStatus.PENDING -> parts += "still being made"
        VersionStatus.FAILED -> parts += "couldn't be made, tap to try again"
        VersionStatus.READY -> Unit
    }
    return parts.joinToString(", ")
}

/**
 * S3/S4 studio: ✕ · Compare · ⋮ · Save top bar; the selected version full-bleed on a black
 * canvas with swipe between versions (Original at position 0), pinch/double-tap zoom, hold for
 * Original; a strip of face-cropped tiles; a refine row that only ever adds versions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CandidateStudioScreen(
    state: SwapPersonUiState,
    source: EditImage,
    machine: SwapPersonMachine,
    onView: (String) -> Unit
) {
    val zoom = remember { SwapZoom() }
    var holdingOriginal by remember { mutableStateOf(false) }
    val pages = state.pages
    val selectedPage = pages.indexOf(state.selected).coerceAtLeast(0)
    val pager = rememberPagerState(selectedPage) { pages.size }
    // Selection drives the pager (strip taps, Compare all, new batches)…
    LaunchedEffect(selectedPage, pages.size) {
        if (pager.currentPage != selectedPage) pager.animateScrollToPage(selectedPage)
    }
    // …and a settled swipe drives the selection, skipping versions that are not ready yet.
    val latestPages by rememberUpdatedState(pages)
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val key = latestPages.getOrNull(page)
            val ready = key == null || latestState.versions.any { it.key == key && it.status == VersionStatus.READY }
            if (ready && key != machine.state.selected) machine.select(key)
        }
    }

    Column(Modifier.fillMaxSize().background(SwapColors.Canvas).testTag("swap-studio")) {
        StudioTopBar(state, machine)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp)).testTag("swap-canvas")) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = zoom.scale <= 1f,
                key = { pages.getOrNull(it) ?: -1 }) { page ->
                val key = pages.getOrNull(page)
                val version = state.versions.firstOrNull { it.key == key }
                CanvasPage(state, source, version, zoom, holdingOriginal,
                    onHold = { holdingOriginal = it })
            }
            OriginalPill(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)) { holdingOriginal = it }
            StudioCanvasMessages(state, machine, onView, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        }
        VersionStrip(state, source, machine)
        BottomBand(state, machine)
    }
    when (val overlay = state.overlay) {
        SwapOverlay.CompareAll -> CompareAllScreen(state, source, machine)
        is SwapOverlay.Split -> SplitCompareScreen(state, source, overlay, machine)
        SwapOverlay.None -> Unit
    }
}

@Composable
private fun StudioTopBar(state: SwapPersonUiState, machine: SwapPersonMachine) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(machine::close, Modifier.size(48.dp).testTag("swap-close")) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = SwapColors.OnCanvas)
        }
        Spacer(Modifier.weight(1f))
        IconButton(machine::openCompareAll, Modifier.size(48.dp).testTag("swap-compare"),
            enabled = state.ready.isNotEmpty()) {
            Icon(Icons.Outlined.GridView, contentDescription = "Compare all versions",
                tint = if (state.ready.isNotEmpty()) SwapColors.OnCanvas else SwapColors.OnCanvasMuted)
        }
        Box {
            IconButton({ menu = true }, Modifier.size(48.dp).testTag("swap-menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = SwapColors.OnCanvas)
            }
            DropdownMenu(menu, { menu = false }, Modifier.testTag("swap-menu-sheet")) {
                if (state.canChangeFace) DropdownMenuItem({ Text("Change face") },
                    { menu = false; machine.changeFace() }, Modifier.testTag("swap-change-face"))
                DropdownMenuItem({ Text("Change person") }, { menu = false; machine.changePerson() },
                    Modifier.testTag("swap-change-person"))
                state.person?.let { p ->
                    DropdownMenuItem({ Text("Photos used for ${p.displayName}") },
                        { menu = false; machine.openPhotosUsed() }, Modifier.testTag("swap-photos-used"))
                }
            }
        }
        val canSave = state.selectedVersion?.status == VersionStatus.READY && state.save != SwapSave.Saving
        TextButton(machine::save, Modifier.heightIn(min = 48.dp).testTag("swap-save"), enabled = canSave) {
            if (state.save == SwapSave.Saving) CircularProgressIndicator(Modifier.size(18.dp), color = SwapColors.Accent, strokeWidth = 2.dp)
            else Text("Save", color = if (canSave) SwapColors.Accent else SwapColors.OnCanvasMuted,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CanvasPage(
    state: SwapPersonUiState,
    source: EditImage,
    version: SwapVersion?,
    zoom: SwapZoom,
    holdingOriginal: Boolean,
    onHold: (Boolean) -> Unit
) {
    val target = version?.request?.target ?: state.target
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val rect = fit(source.width, source.height, w, h)
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || zoom.scale > 1f) {
                            zoom.scale = (zoom.scale * event.calculateZoom()).coerceIn(1f, 8f)
                            zoom.offset += event.calculatePan()
                            if (zoom.scale <= 1.01f) zoom.reset() else zoom.clamp(w, h)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(target) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom.scale > 1f || target == null) zoom.reset() else {
                            val c = rect.toView((target.box.left + target.box.right) / 2f, (target.box.top + target.box.bottom) / 2f)
                            zoom.scale = 3f
                            zoom.offset = Offset((w / 2 - c.x) * 3f, (h / 2 - c.y) * 3f)
                            zoom.clamp(w, h)
                        }
                    },
                    onLongPress = { onHold(true) },
                    onPress = { tryAwaitRelease(); onHold(false) }
                )
            }
            .graphicsLayer {
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.offset.x; translationY = zoom.offset.y
            }) {
            val shown = if (holdingOriginal || version?.status != VersionStatus.READY) source else version.image ?: source
            Crossfade(shown, animationSpec = tween(SwapMotion.CROSSFADE_MS), label = "version") { image ->
                FittedImage(image, Modifier.fillMaxSize().testTag(if (image === source) "swap-canvas-original" else "swap-canvas-version"))
            }
            if (version?.status == VersionStatus.PENDING && target != null && !holdingOriginal) FaceShimmer(rect, target.box)
        }
    }
}

/** S3: a soft shimmer over the target face only; a still tint under reduced motion. */
@Composable
private fun FaceShimmer(rect: FitRect, box: FaceBox) {
    val reduced = rememberReducedMotion()
    val phase = if (reduced) .5f else rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "shimmer").value
    val density = LocalDensity.current
    val tl = rect.toView(box.left.toFloat(), box.top.toFloat())
    val br = rect.toView(box.right.toFloat(), box.bottom.toFloat())
    with(density) {
        Box(Modifier.padding(start = tl.x.toDp(), top = tl.y.toDp()).size((br.x - tl.x).toDp(), (br.y - tl.y).toDp())
            .clip(RoundedCornerShape(12.dp)).testTag("swap-face-shimmer")
            .background(Brush.linearGradient(
                listOf(Color.White.copy(alpha = .05f), Color.White.copy(alpha = .35f), Color.White.copy(alpha = .05f)),
                start = Offset((br.x - tl.x) * (phase * 2 - 1), 0f),
                end = Offset((br.x - tl.x) * (phase * 2), br.y - tl.y))))
    }
}

/** Labelled "Original" hold pill (36 dp, 48 dp hit area), after Samsung's "View original". */
@Composable
private fun OriginalPill(modifier: Modifier, onHold: (Boolean) -> Unit) {
    Box(modifier.heightIn(min = 48.dp).testTag("swap-original-pill")
        .semantics { contentDescription = "Hold to see original" }
        .pointerInput(Unit) { detectTapGestures(onPress = { onHold(true); tryAwaitRelease(); onHold(false) }) },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.height(36.dp), shape = CircleShape, color = SwapColors.Chrome, contentColor = SwapColors.OnCanvas) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("Original", fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StudioCanvasMessages(state: SwapPersonUiState, machine: SwapPersonMachine, onView: (String) -> Unit, modifier: Modifier) {
    when {
        state.notice == SwapNotice.AllFailed -> CanvasMessage("Couldn't make versions of this photo", modifier,
            primary = "Try again" to machine::tryAgain,
            secondary = if (state.canChangeFace) "Change face" to machine::changeFace else null, tag = "swap-all-failed")
        state.notice == SwapNotice.OutOfMemory -> CanvasMessage(
            "Your phone needed more memory. Close some apps and try again.", modifier,
            primary = "Try again" to machine::tryAgain, tag = "swap-out-of-memory")
        state.notice is SwapNotice.TooFewPhotos -> CanvasMessage(
            "Add a few more photos of ${(state.notice as SwapNotice.TooFewPhotos).name}", modifier,
            primary = "Add photos" to machine::openPhotosUsed, secondary = "Not now" to machine::dismissNotice,
            tag = "swap-too-few")
        state.save is SwapSave.Saved -> CanvasMessage("Saved as a copy", modifier,
            primary = "View" to { onView((state.save as SwapSave.Saved).handle) }, tag = "swap-saved")
        state.save == SwapSave.Failed -> CanvasMessage("Couldn't save this copy.", modifier,
            primary = "Try again" to machine::save, tag = "swap-save-failed")
    }
}

/** Strip: 72 dp face-cropped tiles, Original first, a 12 dp gap between batches, More (+) last. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionStrip(state: SwapPersonUiState, source: EditImage, machine: SwapPersonMachine) {
    val list = rememberLazyListState()
    val selectedIndex = state.pages.indexOf(state.selected).coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { list.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0)) }
    val target = state.target
    LazyRow(Modifier.fillMaxWidth().height(88.dp).testTag("swap-strip"), state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item(key = "original") {
            StripTile(state, null, source, target?.box, Modifier.testTag("swap-tile-original"),
                onTap = { machine.select(null) }, onLong = { machine.split(null) }) {
                // The caption grows with the font up to 1.3× so it still fits the 72 dp tile; TalkBack reads "Original".
                val fontScale = LocalDensity.current.fontScale
                val captionScale = minOf(fontScale, 1.3f) / fontScale
                Text("Original", fontSize = (11 * captionScale).sp, lineHeight = (12 * captionScale).sp, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(SwapColors.Scrim).padding(vertical = 2.dp))
            }
        }
        state.versions.forEachIndexed { i, v ->
            // A new batch starts after a 12 dp gap: 8 dp spacing plus 4 dp here.
            val newBatch = i > 0 && state.versions[i - 1].batch != v.batch
            item(key = v.key) {
                StripTile(state, v.key, if (v.status == VersionStatus.READY) v.image else source,
                    v.request.target.box, Modifier.padding(start = if (newBatch) 4.dp else 0.dp).testTag("swap-tile-${i + 1}"),
                    onTap = { if (v.status == VersionStatus.FAILED) machine.retry(v.key) else machine.select(v.key) },
                    onLong = { machine.split(v.key) }) {
                    if (v.status == VersionStatus.PENDING) TileShimmer()
                    if (v.status == VersionStatus.FAILED) Box(Modifier.fillMaxSize().background(SwapColors.StrongScrim),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
        item(key = "more") {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, SwapColors.OnCanvasMuted, RoundedCornerShape(12.dp))
                .combinedClickable(enabled = state.ready.isNotEmpty(), onClick = machine::more)
                .testTag("swap-more").semantics { contentDescription = "More versions" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = SwapColors.OnCanvas)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTile(
    state: SwapPersonUiState,
    key: Int?,
    image: EditImage?,
    box: FaceBox?,
    modifier: Modifier,
    onTap: () -> Unit,
    onLong: () -> Unit,
    overlay: @Composable () -> Unit
) {
    val reduced = rememberReducedMotion()
    val selected = state.selected == key
    val scale by animateFloatAsState(if (selected) 1.08f else 1f,
        tween(if (reduced) 0 else SwapMotion.SELECT_MS), label = "tile")
    val label = versionLabel(state, key)
    Box(modifier.size(72.dp).scale(scale).clip(RoundedCornerShape(12.dp))
        .then(if (selected) Modifier.border(2.dp, SwapColors.Accent, RoundedCornerShape(12.dp)) else Modifier)
        .combinedClickable(onClick = onTap, onLongClick = onLong)
        .semantics { contentDescription = label; this.selected = selected }) {
        FaceCropImage(image, box, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter)) { overlay() }
    }
}

@Composable
private fun TileShimmer() {
    val reduced = rememberReducedMotion()
    val a = if (reduced) .5f else rememberInfiniteTransition(label = "tile-shimmer").animateFloat(
        .25f, .7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    Box(Modifier.size(72.dp).background(SwapColors.Placeholder.copy(alpha = a + .3f)).testTag("swap-tile-pending"))
}

/** Bottom band: "Making 4 versions…" while the first batch runs, else the refine row. */
@Composable
private fun BottomBand(state: SwapPersonUiState, machine: SwapPersonMachine) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).navigationBarsPadding(), contentAlignment = Alignment.CenterStart) {
        // The brief keeps "Making 4 versions…" for the whole first batch, even after its first tiles fill in.
        val first = state.firstBatchRunning
        if (first != null || (state.generating && state.selectedVersion == null)) {
            Text(if (first != null) "Making ${first.size} versions…" else "Making ${state.pending} more…",
                fontSize = 14.sp, color = SwapColors.OnCanvasMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("swap-making"))
        } else RefineRow(state, machine)
    }
}

@Composable
private fun RefineRow(state: SwapPersonUiState, machine: SwapPersonMachine) {
    val enabled = state.selectedVersion?.status == VersionStatus.READY
    val chips = buildList {
        add("More natural" to { machine.refine(SwapRefine.MORE_NATURAL) })
        add("Stronger likeness" to { machine.refine(SwapRefine.STRONGER_LIKENESS) })
        add("Keep expression" to { machine.refine(SwapRefine.KEEP_EXPRESSION) })
        if (state.headCapable) add((if (state.selectedVersion?.hair == true) "Keep original hair" else "With their hair") to
            { machine.refine(SwapRefine.HAIR) })
        state.otherLooks.forEach { look -> add(look.label to { machine.useLook(look) }) }
    }
    LazyRow(Modifier.fillMaxWidth().testTag("swap-refine-row"), contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.generating) item {
            CircularProgressIndicator(Modifier.size(20.dp).testTag("swap-refine-running"), color = SwapColors.Accent, strokeWidth = 2.dp)
        }
        items(chips, key = { it.first }) { (label, action) ->
            AssistChip(onClick = action, enabled = enabled, label = { Text(label, fontSize = 14.sp) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("swap-chip-$label"),
                colors = AssistChipDefaults.assistChipColors(labelColor = SwapColors.OnCanvas,
                    disabledLabelColor = SwapColors.OnCanvasMuted, containerColor = SwapColors.ChipContainer),
                border = AssistChipDefaults.assistChipBorder(enabled, borderColor = SwapColors.ChipBorder))
        }
    }
}

/**
 * Split shortcut: the selected version and a long-pressed tile, stacked in portrait, with linked
 * zoom and pan and a draggable divider. Tap a side to pick it; Back or ✕ exits without a change.
 */
@Composable
internal fun SplitCompareScreen(state: SwapPersonUiState, source: EditImage, split: SwapOverlay.Split, machine: SwapPersonMachine) {
    androidx.activity.compose.BackHandler { machine.closeOverlay() }
    var divider by remember { mutableFloatStateOf(.5f) }
    val zoom = remember { SwapZoom() }
    fun imageOf(key: Int?) = if (key == null) source else state.versions.firstOrNull { it.key == key }?.image ?: source
    Column(Modifier.fillMaxSize().background(SwapColors.Canvas).statusBarsPadding().navigationBarsPadding()
        .testTag("swap-split")) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(machine::closeOverlay, Modifier.size(48.dp).testTag("swap-split-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = SwapColors.OnCanvas)
            }
            Text("Tap the one you like", color = SwapColors.OnCanvasMuted, fontSize = 14.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2 || zoom.scale > 1f) {
                        zoom.scale = (zoom.scale * event.calculateZoom()).coerceIn(1f, 8f)
                        zoom.offset += event.calculatePan()
                        if (zoom.scale <= 1.01f) zoom.reset()
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
            val total = constraints.maxHeight.toFloat()
            Column(Modifier.fillMaxSize()) {
                SplitSide(state, split.older, imageOf(split.older), zoom, Modifier.fillMaxWidth().weight(divider.coerceIn(.2f, .8f)),
                    "swap-split-a") { machine.select(split.older) }
                Box(Modifier.fillMaxWidth().height(24.dp).testTag("swap-split-divider")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val e = awaitPointerEvent()
                                val dy = e.changes.firstOrNull()?.let { it.position.y - it.previousPosition.y } ?: 0f
                                if (abs(dy) > 0f) divider = (divider + dy / total).coerceIn(.2f, .8f)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(SwapColors.OnCanvas))
                    Box(Modifier.size(40.dp, 6.dp).clip(CircleShape).background(SwapColors.OnCanvas))
                }
                SplitSide(state, split.newer, imageOf(split.newer), zoom, Modifier.fillMaxWidth().weight(1f - divider.coerceIn(.2f, .8f)),
                    "swap-split-b") { machine.select(split.newer) }
            }
        }
    }
}

@Composable
private fun SplitSide(state: SwapPersonUiState, key: Int?, image: EditImage, zoom: SwapZoom, modifier: Modifier, tag: String, onPick: () -> Unit) {
    val label = versionLabel(state, key).substringBefore(", selected")
    Box(modifier.clip(RoundedCornerShape(0.dp)).testTag(tag).semantics { contentDescription = label }
        .pointerInput(key) { detectTapGestures(onTap = { onPick() }) }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.offset.x; translationY = zoom.offset.y
        }) { FittedImage(image, Modifier.fillMaxSize()) }
        Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = CircleShape, color = SwapColors.Chrome,
            contentColor = SwapColors.OnCanvas) {
            Text(label.substringBefore(" of "), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}
