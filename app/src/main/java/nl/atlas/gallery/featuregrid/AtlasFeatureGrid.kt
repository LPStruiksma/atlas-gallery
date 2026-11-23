package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import nl.atlas.gallery.featuredesign.AtlasMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuredesign.AtlasTokens
import androidx.compose.ui.layout.ContentScale
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.SharedPhotoOrigins
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import nl.atlas.gallery.featureorganization.DragSelectRange
import nl.atlas.gallery.featureorganization.SelectionCheckCircle

private const val DENSITY_PREFS = "atlas_featuregrid_density"

/** Accessible, non-gesture equivalent of pinch. Also usable in previews/tests. */
@Composable
fun GridViewAsControl(
    density: GridDensity,
    onChoose: (GridDensity) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .testTag("atlas-grid-view-as"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("View as", style = MaterialTheme.typography.labelMedium)
        GridDensity.entries.takeWhile { it.columns <= GridDensity.SMALL.columns }.forEach { choice ->
            FilterChip(
                selected = choice == density,
                onClick = { onChoose(choice) },
                label = { Text(when (choice) {
                    GridDensity.LARGE -> "Large"
                    GridDensity.MEDIUM -> "Medium"
                    GridDensity.SMALL -> "Small"
                    GridDensity.MONTH -> "Month"
                    GridDensity.YEAR -> "Year"
                }) },
                modifier = Modifier.testTag("atlas-grid-density-${choice.columns}")
                    .semantics { contentDescription = "${choice.accessibleName}, ${choice.columns} columns" }
            )
        }
    }
}

/**
 * Standalone gallery grid, safe to hand to the MainActivity integrator.
 *
 * - Keeps one private preference per screen (only the density integer; no image data).
 * - Commits fixed 3/4/7 density after pinch release or via accessible View as chips.
 * - Anchors the image under the fingers *vertically* (horizontal cell coordinates must
 *   naturally reflow in a fixed-column grid). Does not start new decodes mid-gesture.
 * - Does not infer face indexing, mutate MediaStore, modify navigation, or add deps.
 *
 * Pass distinct [viewKey] values for Photos/Search/People; the host keeps ownership
 * of catalogue, sort, scroll state, media decoding and viewer navigation.
 */
@Composable
fun AtlasFeatureGrid(
    items: List<MediaRecord>,
    state: LazyGridState,
    busy: Boolean,
    onOpen: (MediaRecord) -> Unit,
    modifier: Modifier = Modifier,
    viewKey: String = "photos",
    /** Pass the host's permission/publication epoch to reject same-list grant swaps. */
    catalogueEpoch: Long = 0L,
    /** Host-owned route to original-proportion rows, when available. */
    onOriginalProportionsRequested: ((GalleryZoomRequest) -> Unit)? = null,
    /** Restore the adjacent square density when returning from original rows. */
    entryDensity: GridDensity? = null,
    /**
     * #644: false = pinch-only, as on Samsung's grids (Search). TalkBack then
     * gets "Larger tiles"/"Smaller tiles" actions instead of the chip row.
     */
    showViewAs: Boolean = true,
    /** Shown when [items] is empty and not [busy] (#654: Search says "No results"). */
    emptyText: String = "No photos yet",
    /** #134: one short neutral line under [emptyText] (Search: no results). */
    emptyDetail: String? = null,
    /** Live batch selection (null outside selection mode), as on the timeline grid. */
    selectedMediaUris: Set<String>? = null,
    /** Long-press (and drag) selection; null turns selection off (Search, People). */
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    /** A tap while selecting toggles the tile instead of opening it. */
    onMediaToggle: ((MediaRecord) -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(DENSITY_PREFS, Context.MODE_PRIVATE)
    }
    // Ungrouped Search/People/fallback screens have no chronological overview.
    // They must not inherit a 12/28-column Photos preference without the headers.
    fun classic(value: GridDensity): GridDensity =
        if (value.columns > GridDensity.SMALL.columns) GridDensity.SMALL else value
    var density by rememberSaveable(viewKey) {
        mutableStateOf(classic(entryDensity ?: GridDensity.fromColumns(
            prefs.getInt(viewKey, GridDensity.MEDIUM.columns))))
    }
    LaunchedEffect(viewKey, entryDensity) {
        val requested = entryDensity?.let(::classic)
        if (requested != null && density != requested) density = requested
    }
    LaunchedEffect(viewKey, density) { prefs.edit().putInt(viewKey, density.columns).apply() }

    val gapPx = with(LocalDensity.current) { 3.dp.roundToPx() }
    // In Compose effects and pointerInput keys, reference identity is O(1) and
    // distinguishes equal-looking catalogs published under different grants.
    val sourceEpoch = GridCatalogIdentity(items, catalogueEpoch)
    var pendingAnchor by remember { mutableStateOf<GridAnchorCommit?>(null) }
    val currentOpen by rememberUpdatedState(onOpen)
    val originalCurrent by rememberUpdatedState(onOriginalProportionsRequested)
    // FLOW-1 (#711): the same two-layer pinch as the Photos timeline. The
    // live grid and the level the fingers flow into are drawn together,
    // crossfading around the photo under the fingers; closed-form geometry
    // (UniformGridLevel), so no level has to be planned first.
    var cross by remember(sourceEpoch, viewKey) { mutableStateOf<PlainCross?>(null) }
    val crossPosition = remember { mutableFloatStateOf(0f) }
    // How far the fingers' centre has moved since the pinch began: the photo
    // under them follows it. Read only while drawing (no recomposition).
    val crossShift = remember { mutableStateOf(Offset.Zero) }
    val crossJob = remember { arrayOfNulls<Job>(1) }
    var commitScroll by remember { mutableStateOf<Pair<GridDensity, TimelineScrollTarget>?>(null) }
    val reduceMotion = remember(context) { AtlasMotion.animationsDisabled(context) }
    val frameScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Set inside the measured box: an animated, anchored level change at the centre.
    var crossChoose: ((GridDensity) -> Boolean)? = null
    val selectionCurrent by rememberUpdatedState(selectedMediaUris)
    val selectionChangeCurrent by rememberUpdatedState(onSelectionChange)
    val toggleCurrent by rememberUpdatedState(onMediaToggle)
    val dragHolder = remember { arrayOfNulls<DragSelectRange>(1) }

    fun requestOriginal(focus: Offset) {
        val info = state.layoutInfo
        // Only trust a measured grid of the currently authorized catalogue.
        val cells = if (info.totalItemsCount == items.size &&
            info.viewportSize.width > 0 && info.viewportSize.height > 0) {
            info.visibleItemsInfo.mapNotNull { cell ->
                val media = items.getOrNull(cell.index) ?: return@mapNotNull null
                GridVisibleCell(media.uri, cell.index, cell.offset.x, cell.offset.y,
                    cell.size.width, cell.size.height)
            }
        } else emptyList()
        originalCurrent?.invoke(GalleryZoomRequest(
            focalGridAnchor(cells, focus.x, focus.y), focus.y, catalogueEpoch,
            destination = GalleryZoomStop.ORIGINAL
        ))
    }

    fun chooseDensity(next: GridDensity, focus: Offset) {
        if (next == density || next.columns > GridDensity.SMALL.columns) return
        val layout = state.layoutInfo
        val visibleCells = layout.visibleItemsInfo.mapNotNull { cell ->
            val media = items.getOrNull(cell.index) ?: return@mapNotNull null
            GridVisibleCell(media.uri, cell.index, cell.offset.x, cell.offset.y,
                cell.size.width, cell.size.height)
        }
        val focal = focalGridAnchor(visibleCells, focus.x, focus.y)
        val geometry = if (layout.viewportSize.width > 0 && layout.viewportSize.height > 0)
            GridViewportGeometry(layout.viewportSize.width, layout.viewportSize.height,
                gapPx, gapPx, items.size) else null
        if (focal != null && geometry != null) {
            // Identity is checked before committing; the integrator may have swapped
            // its current inventory while a long pinch was in flight.
            // A superseded or reordered catalogue is not a reason to linearly
            // scan 150k media on the UI thread. New publication => new gesture.
            val resolvedIndex = currentFocalIndexOrNull(
                focal.itemIndex, focal.mediaKey, items.size) { items[it].uri }
            if (resolvedIndex != null) {
                pendingAnchor = GridAnchorCommit(
                    publication = sourceEpoch,
                    viewKey = viewKey,
                    density = next,
                    viewportWidthPx = geometry.widthPx,
                    viewportHeightPx = geometry.heightPx,
                    mediaKey = focal.mediaKey,
                    mediaIndex = resolvedIndex,
                    destination = resolveGridAnchor(
                        focal, resolvedIndex, next.columns, geometry, focus.y)
                )
            } else pendingAnchor = null
        } else pendingAnchor = null
        density = next
    }

    // Wait for Fixed(new density) to measure before restoring the destination row.
    LaunchedEffect(density, pendingAnchor, sourceEpoch, viewKey) {
        val command = pendingAnchor ?: return@LaunchedEffect
        fun stillCurrent(): Boolean {
            val viewport = state.layoutInfo.viewportSize
            return command.isCurrent(
                currentPublication = sourceEpoch,
                currentViewKey = viewKey,
                currentDensity = density,
                currentViewportWidthPx = viewport.width,
                currentViewportHeightPx = viewport.height,
                currentMediaKeyAtIndex = items.getOrNull(command.mediaIndex)?.uri
            )
        }
        // A new authorization epoch, screen, density or geometry invalidates
        // the command even if the URI's text happens to be identical.
        if (stillCurrent()) {
            withFrameNanos { }
            if (stillCurrent()) {
                state.scrollToItem(command.destination.firstItemIndex,
                    command.destination.firstItemScrollOffsetPx)
            }
        }
        if (pendingAnchor === command) pendingAnchor = null
    }

    fun chooseAtCentre(next: GridDensity) {
        if (next != density && crossChoose?.invoke(next) == true) return
        val size = state.layoutInfo.viewportSize
        chooseDensity(next, Offset(size.width / 2f, size.height / 2f))
    }

    Column(modifier) {
        if (showViewAs) GridViewAsControl(density, onChoose = ::chooseAtCentre)
        if (items.isEmpty()) {
            // DESIGN-6 #136: the shared quiet state; nothing while busy.
            if (!busy) AtlasEmptyState(
                title = emptyText,
                explanation = emptyDetail.orEmpty(),
                titleTag = "atlas-feature-grid-empty",
                bodyTag = "atlas-feature-grid-empty-detail")
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val cellPx = GridViewportGeometry(
                viewportWidthPx.coerceAtLeast(1), 1, gapPx, gapPx, 0
            ).cellSidePx(density.columns)
            val previewPixels = gridPreviewRequestPixels(cellPx, cellPx)
            val localDensity = LocalDensity.current
            val heightPx = with(localDensity) { maxHeight.roundToPx() }
            val startPadPx = with(localDensity) { 3.dp.roundToPx() }
            val tileGapPx = with(localDensity) { AtlasTokens.Spacing.GridGap.roundToPx() }
            val bottomPadPx = with(localDensity) { LocalAtlasBottomChrome.current.roundToPx() }
            val contentWidthPx = (viewportWidthPx - 2 * startPadPx).coerceAtLeast(0)
            fun levelPixels(level: Int): Int {
                val side = GridViewportGeometry(viewportWidthPx.coerceAtLeast(1), 1, gapPx, gapPx, 0)
                    .cellSidePx(GridDensity.entries[level.coerceIn(0, PLAIN_CROSS_MAX_LEVEL)].columns)
                return gridPreviewRequestPixels(side, side)
            }
            fun levelGeometry(level: Int) = UniformGridLevel(items.size,
                composeGridColumnSlots(contentWidthPx, tileGapPx,
                    GridDensity.entries[level.coerceIn(0, PLAIN_CROSS_MAX_LEVEL)].columns),
                tileGapPx, startPadPx, heightPx, bottomPadPx)
            // A tile whose own size is still decoding shows the copy a
            // neighbouring level already decoded: no grey blink at a commit.
            val tileFallback = remember(viewportWidthPx, density) {
                listOf(density.ordinal - 1, density.ordinal + 1)
                    .filter { it in 0..PLAIN_CROSS_MAX_LEVEL }.map(::levelPixels)
                    .filter { it != previewPixels }.distinct().toIntArray()
            }
            fun ghostFor(c: PlainCross, level: Int, focusY: Float = c.focus.y): PlainGhost? {
                if (c.anchorIndex !in items.indices || level !in 0..PLAIN_CROSS_MAX_LEVEL) return null
                val g = levelGeometry(level)
                val target = runCatching { g.target(c.anchorIndex, c.fractionY, focusY) }
                    .getOrNull() ?: return null
                val top = g.scrollTopFor(target)
                return PlainGhost(target, g.visibleCells(top), g.anchorRect(c.anchorIndex, top))
            }
            val crossNow = cross
            val crossLevels by remember(crossNow) {
                derivedStateOf {
                    if (crossNow == null) null else crossFrame(crossPosition.floatValue, PLAIN_CROSS_MAX_LEVEL)
                        .let { it.lower to it.upper }
                }
            }
            val pair = crossLevels
            val ghosts: Map<Int, PlainGhost> = remember(crossNow, pair, viewportWidthPx, heightPx, items.size) {
                PinchCrossTiming.measure("geometry") { if (crossNow == null || pair == null) emptyMap() else buildMap {
                    for (level in listOf(pair.first, pair.second).distinct()) {
                        if (level == crossNow.baseLevel) continue
                        ghostFor(crossNow, level)?.let { put(level, it) }
                    }
                } }
            }
            fun restRect(c: PlainCross, level: Int): Rect =
                if (level == c.baseLevel) c.baseAnchor
                else ghosts[level]?.anchor ?: estimatedAnchorRect(c.focus, c.fractionX, c.fractionY,
                    levelGeometry(level).rowHeightPx.toFloat())
            fun drawable(c: PlainCross, level: Int) = level == c.baseLevel || ghosts.containsKey(level)
            fun lookAt(c: PlainCross, level: Int, v: Float): CrossLayerLook? {
                val frame = crossFrame(v, PLAIN_CROSS_MAX_LEVEL)
                val lowerOk = drawable(c, frame.lower)
                val upperOk = drawable(c, frame.upper)
                return when {
                    lowerOk && upperOk -> crossLayerLook(level, frame) { AtlasMotion.SineInOut80.transform(it) }
                    lowerOk || upperOk ->
                        if (level == (if (lowerOk) frame.lower else frame.upper)) CrossLayerLook(1f, false) else null
                    else -> if (level == c.baseLevel) CrossLayerLook(1f, false) else null
                }
            }
            fun poseAt(c: PlainCross, level: Int, v: Float): LayerPose {
                val anchorNow = crossAnchorAt(crossFrame(v, PLAIN_CROSS_MAX_LEVEL), { restRect(c, it) }, c.focus)
                    .translate(crossShift.value)
                return poseOnto(restRect(c, level), anchorNow)
            }
            val liveOnTop by remember(crossNow, ghosts) {
                derivedStateOf {
                    val c = crossNow
                    c != null && lookAt(c, c.baseLevel, crossPosition.floatValue)?.onTop == true
                }
            }
            // The photo under the fingers, from what the live grid shows now.
            fun beginCross(focus: Offset): PlainCross? {
                val info = state.layoutInfo
                if (pendingAnchor != null || commitScroll != null || info.totalItemsCount != items.size ||
                    density.ordinal > PLAIN_CROSS_MAX_LEVEL) return null
                val topPad = info.beforeContentPadding
                val cells = info.visibleItemsInfo.mapNotNull { cell ->
                    val media = items.getOrNull(cell.index) ?: return@mapNotNull null
                    // Item offsets exclude the content padding; pointers do not.
                    GridVisibleCell(media.uri, cell.index, cell.offset.x + startPadPx,
                        cell.offset.y + topPad, cell.size.width, cell.size.height)
                }
                val anchor = focalGridAnchor(cells, focus.x, focus.y) ?: return null
                val cell = cells.first { it.itemIndex == anchor.itemIndex }
                return PlainCross(density.ordinal, anchor.itemIndex, anchor.mediaKey,
                    anchor.fractionX, anchor.fractionY, focus,
                    Rect(cell.x.toFloat(), cell.y.toFloat(), (cell.x + cell.width).toFloat(),
                        (cell.y + cell.height).toFloat()))
            }
            fun finishCross(c: PlainCross, target: Int) {
                cross = null
                if (target == c.baseLevel) return
                val level = GridDensity.entries[target]
                val ghost = ghostFor(c, target)
                if (ghost != null && items.getOrNull(c.anchorIndex)?.uri == c.anchorKey) {
                    // The new grid opens exactly where its pinch layer was drawn,
                    // including where the fingers moved it.
                    pendingAnchor = null
                    commitScroll = level to (c.commitTarget ?: ghost.target)
                    density = level
                } else chooseDensity(level, c.focus)
            }
            fun settleCross(target: Int) {
                val c = cross ?: return
                c.settling = true
                val from = crossPosition.floatValue
                val to = target.toFloat()
                // The photo stays where the fingers left it, as far as the new
                // level's rows allow: columns are fixed (x settles back) and
                // the library's ends clamp the scroll.
                val shiftFrom = crossShift.value
                var shiftTo = Offset.Zero
                c.commitTarget = null
                if (target != c.baseLevel && shiftFrom != Offset.Zero) {
                    val at = ghostFor(c, target)
                    val moved = ghostFor(c, target, c.focus.y + shiftFrom.y)
                    if (at != null && moved != null) {
                        shiftTo = Offset(0f, moved.anchor.top - at.anchor.top)
                        c.commitTarget = moved.target
                    }
                }
                val ms = if (reduceMotion) 0 else crossSettleMillis(from, to,
                    AtlasMotion.PINCH_SETTLE_MS, AtlasMotion.PINCH_SETTLE_MIN_MS)
                    .let { if (it == 0 && shiftFrom != shiftTo) AtlasMotion.PINCH_SETTLE_MIN_MS else it }
                crossJob[0]?.cancel()
                crossJob[0] = frameScope.launch {
                    if (ms > 0) animate(0f, 1f, animationSpec = tween(ms, easing = AtlasMotion.SineInOut80)) { t, _ ->
                        crossPosition.floatValue = from + (to - from) * t
                        crossShift.value = androidx.compose.ui.geometry.lerp(shiftFrom, shiftTo, t)
                    }
                    crossPosition.floatValue = to
                    crossShift.value = shiftTo
                    if (cross === c) { finishCross(c, target); crossShift.value = Offset.Zero }
                }
            }
            // TalkBack "Larger/Smaller tiles" and the View as chips: the same
            // anchored, animated commit as a pinch, on the photo in the middle.
            crossChoose = choose@{ next ->
                if (cross != null || next.ordinal > PLAIN_CROSS_MAX_LEVEL) return@choose false
                val info = state.layoutInfo
                val c = beginCross(Offset(info.viewportSize.width / 2f,
                    info.viewportSize.height / 2f)) ?: return@choose false
                crossPosition.floatValue = c.baseLevel.toFloat()
                crossShift.value = Offset.Zero
                cross = c
                settleCross(next.ordinal)
                true
            }
            // A committed level opens at its pinch layer's exact scroll before
            // its first measure, so the handover is pixel-identical.
            val commit = commitScroll
            if (commit != null && density == commit.first) {
                SideEffect {
                    if (commitScroll === commit) {
                        state.requestScrollToItem(commit.second.firstSlotIndex,
                            commit.second.firstSlotScrollOffsetPx)
                        commitScroll = null
                    }
                }
            }
            CompositionLocalProvider(LocalGridTileFallbackPixels provides tileFallback) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
            // Position-only lazy keys may be saved by Compose. Remount the grid
            // on any new source/grant or density, never by a private URI.
            key(sourceEpoch, density, viewKey) {
            LazyVerticalGrid(
            columns = GridCells.Fixed(density.columns),
            state = state,
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp,
                bottom = LocalAtlasBottomChrome.current),
            horizontalArrangement = Arrangement.spacedBy(AtlasTokens.Spacing.GridGap),
            verticalArrangement = Arrangement.spacedBy(AtlasTokens.Spacing.GridGap),
            modifier = Modifier.zIndex(if (liveOnTop) 1f else 0f).fillMaxSize()
                .galleryPinchLadder(sourceEpoch, density, state, newTracker = { slop ->
                GalleryPinchTracker(GalleryZoomStop.forDensity(density), slop,
                    allowOriginal = originalCurrent != null, allowOverview = false)
            }, onProgress = { progress, focusPoint ->
                if (cross == null && crossJob[0]?.isActive != true) {
                    cross = beginCross(focusPoint)?.also {
                        crossPosition.floatValue = it.baseLevel.toFloat()
                        crossShift.value = Offset.Zero
                    }
                }
                val c = cross
                if (c != null && !c.settling) {
                    crossShift.value = focusPoint - c.focus
                    val before = kotlin.math.floor(crossPosition.floatValue)
                    val v = c.baseLevel + progress
                    crossPosition.floatValue = v
                    // Parity spec Haptics: a light tick on each level change.
                    if (kotlin.math.floor(v) != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }, onCancelled = {
                cross?.let { settleCross(it.baseLevel) }
            }) { next, focus ->
                if (next == GalleryZoomStop.ORIGINAL) {
                    // Original rows start drawn at the size the fingers left.
                    val c = cross
                    if (c != null) {
                        val drawn = crossAnchorAt(crossFrame(crossPosition.floatValue, PLAIN_CROSS_MAX_LEVEL),
                            { restRect(c, it) }, c.focus).width
                        ZoomEntryHandoff.offer(true, catalogueEpoch, drawn, focus)
                    }
                    crossJob[0]?.cancel()
                    cross = null
                    crossShift.value = Offset.Zero
                    requestOriginal(focus)
                } else next.squareDensityOrNull()?.let {
                    if (cross != null) settleCross(it.ordinal) else chooseDensity(it, focus)
                }
            }
            // Samsung long-press drag selection, as on the timeline and original rows.
            // Every slot of the plain grid is a media item at the same index.
            .gridDragSelect(
                enabled = onSelectionChange != null,
                gridState = state,
                frameScope = frameScope,
                isMediaSlot = { it in items.indices },
                onStart = start@{ mediaIndex ->
                    val sink = selectionChangeCurrent ?: return@start false
                    val range = DragSelectRange.start(mediaIndex,
                        selectionCurrent.orEmpty()) { items.getOrNull(it)?.uri }
                        ?: return@start false
                    dragHolder[0] = range
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sink(range.selection)
                    true
                },
                onExtend = { mediaIndex ->
                    val range = dragHolder[0]
                    if (range != null) selectionChangeCurrent?.invoke(range.extendTo(mediaIndex))
                },
                onEnd = { dragHolder[0] = null }
            )
            .semantics {
                val actions = buildList {
                    if (originalCurrent != null) add(
                        CustomAccessibilityAction(ORIGINAL_PROPORTIONS_ACTION) {
                            val size = state.layoutInfo.viewportSize
                            requestOriginal(Offset(size.width / 2f, size.height / 2f))
                            true
                        })
                    if (!showViewAs) {
                        GridDensity.entries.getOrNull(density.ordinal - 1)?.let { larger ->
                            add(CustomAccessibilityAction(LARGER_TILES_ACTION) {
                                chooseAtCentre(larger); true })
                        }
                        GridDensity.entries.getOrNull(density.ordinal + 1)
                            ?.takeIf { it.columns <= GridDensity.SMALL.columns }?.let { smaller ->
                            add(CustomAccessibilityAction(SMALLER_TILES_ACTION) {
                                chooseAtCentre(smaller); true })
                        }
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
                stateDescription = "${density.columns} columns"
            }.testTag("atlas-feature-grid")
            // FLOW-1: only while a pinch runs or settles. Draw-phase reads only.
            .then(if (crossNow == null) Modifier else Modifier.graphicsLayer { PinchCrossTiming.measure("pose") {
                val v = crossPosition.floatValue
                val pose = poseAt(crossNow, crossNow.baseLevel, v)
                alpha = lookAt(crossNow, crossNow.baseLevel, v)?.alpha ?: 0f
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.translationX
                translationY = pose.translationY
            } })
        ) {
            itemsIndexed(items, key = { index, _ -> gridLazySaveablePositionKey(index) }) { _, media ->
                // Keep coordinates outside Compose state: scroll frames must not
                // recompose every visible tile merely to track its rectangle.
                val sourceBounds = remember(media.uri) { arrayOf<Rect?>(null) }
                val selecting = selectedMediaUris != null && onMediaToggle != null
                val isSelected = selecting && media.uri in selectedMediaUris.orEmpty()
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(AtlasTokens.Radii.GridTile))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onGloballyPositioned { sourceBounds[0] = it.boundsInWindow() }
                    // The pointer long press belongs to the grid's drag
                    // detector; TalkBack gets the same start as an action.
                    .then(if (onSelectionChange != null && !selecting) Modifier.semantics {
                        onLongClick("Select ${media.name}") {
                            selectionChangeCurrent?.invoke(selectionCurrent.orEmpty() + media.uri)
                            true
                        }
                    } else Modifier)
                    .clickable(onClickLabel = if (selecting) "Select ${media.name}"
                        else "Open ${media.name}") {
                        // Live state at click time: never open mid-selection.
                        val toggle = toggleCurrent
                        if (selectionCurrent != null && toggle != null) toggle(media)
                        else {
                            SharedPhotoOrigins.offer(media, catalogueEpoch, sourceBounds[0])
                            currentOpen(media)
                        }
                    }
                    .then(if (selecting) Modifier.semantics { selected = isSelected }
                        else Modifier)
                    .testTag("atlas-grid-media-${media.id}-${if (media.isVideo) "video" else "photo"}")) {
                    // Existing Coil media request and cache remain owned by the host.
                    // A fixed request size across 3/4/7 does not force extra decodes
                    // while the user is changing density.
                    MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Crop, previewPixels, gridTile = true)
                    if (media.isVideo) Text("▶", Modifier.align(Alignment.BottomEnd)
                        .padding(6.dp), style = MaterialTheme.typography.titleMedium)
                    // Samsung: every tile shows its circle while selecting.
                    if (selecting) SelectionCheckCircle(isSelected,
                        Modifier.align(Alignment.TopStart).padding(5.dp))
                }
            }
        }
        } // End source/grant/layout keyed Compose subtree.
            // FLOW-1: the level the pinch is flowing into, as a still layer.
            if (crossNow != null) for ((level, ghost) in ghosts) key(level) {
                val onTop by remember(crossNow, ghosts) {
                    derivedStateOf { lookAt(crossNow, level, crossPosition.floatValue)?.onTop == true }
                }
                val candidates = remember(level, crossNow.baseLevel, viewportWidthPx) {
                    listOf(levelPixels(level), levelPixels(crossNow.baseLevel)).distinct().toIntArray()
                }
                val mediaAt = remember(items) { { index: Int -> items.getOrNull(index) } }
                PinchCrossLayer(ghost.cells, mediaAt, { null }, candidates, null, false,
                    modifier = Modifier.zIndex(if (onTop) 1f else 0f).fillMaxSize().graphicsLayer { PinchCrossTiming.measure("pose") {
                        val v = crossPosition.floatValue
                        val pose = poseAt(crossNow, level, v)
                        alpha = lookAt(crossNow, level, v)?.alpha ?: 0f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = pose.scale
                        scaleY = pose.scale
                        translationX = pose.translationX
                        translationY = pose.translationY
                    } })
            }
            } // FLOW-1 layer box
            } // Tile fallback provider
        }
    }
}

/** FLOW-1 on the plain grid: levels 3, 4 and 7 columns (ordinals 0..2). */
private const val PLAIN_CROSS_MAX_LEVEL = 2

/** One two-layer pinch on [AtlasFeatureGrid]; screen = the grid's own coordinates. */
private class PlainCross(
    val baseLevel: Int,
    val anchorIndex: Int,
    val anchorKey: String,
    val fractionX: Float,
    val fractionY: Float,
    /** Finger focus when the pinch started; the anchor point stays here. */
    val focus: Offset,
    /** The anchor photo's rectangle in the live grid at rest. */
    val baseAnchor: Rect
) {
    /** Release animation running: further samples are ignored. Main thread only. */
    var settling: Boolean = false
    /** Where the new level opens when the fingers moved the photo; null = the unmoved target. */
    var commitTarget: TimelineScrollTarget? = null
}

private class PlainGhost(val target: TimelineScrollTarget, val cells: List<CrossCell>, val anchor: Rect)

/** Convenience for a new screen; integrations with existing navigation pass their state. */
@Composable
fun rememberAtlasGridState(): LazyGridState = rememberLazyGridState()
