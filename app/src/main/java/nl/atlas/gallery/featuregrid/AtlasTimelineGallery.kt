package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import androidx.compose.runtime.CompositionLocalProvider
import nl.atlas.gallery.featuredesign.scrolledFromTop
import nl.atlas.gallery.featuredesign.LocalAtlasHeaderCollapsed
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import nl.atlas.gallery.featureorganization.DragSelectRange
import nl.atlas.gallery.featureorganization.SelectionCheckCircle
import nl.atlas.gallery.featureorganization.groupFullySelected
import nl.atlas.gallery.featureorganization.toggleGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.featuremedia.GridThumbnailPrefetch
import nl.atlas.gallery.MediaRecord
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.coroutineContext

private const val TIMELINE_PREFS = "atlas_featuregrid_density"

/** Accessibility action label that replaces the removed "Original" button (#494). */
const val ORIGINAL_PROPORTIONS_ACTION = "Original proportions"
/** TalkBack density actions that replace the removed "View as" chip row (#514). */
const val LARGER_TILES_ACTION = "Larger tiles"
const val SMALLER_TILES_ACTION = "Smaller tiles"

private class TimelinePlan(
    val source: List<MediaRecord>,
    val publication: GridCatalogIdentity,
    val sort: TimelineSort,
    val density: GridDensity,
    val bounds: GridViewportGeometry,
    val geometry: TimelineGridGeometry
)

/**
 * #507: sideways offset (drawn px) that keeps the photo under the fingers at
 * the same screen x when the column count changes at commit. Its new layout
 * left is [newLeftPx] with side [newCellPx]; it was drawn with the focus
 * [fractionX] of the way across it; [visualScale] is the layer scale.
 */
internal fun zoomHandoffShiftPx(focusX: Float, fractionX: Float, newLeftPx: Float,
    newCellPx: Float, visualScale: Float): Float =
    visualScale * (focusX - newLeftPx - fractionX.coerceIn(0f, 1f) * newCellPx)

/** The photo under the fingers at commit, for the sideways handoff. */
private class ZoomHandoff(val mediaIndex: Int, val fractionX: Float)

private data class PendingTimelineFocus(
    val sourceEpoch: GridCatalogIdentity,
    val mediaKey: String,
    val mediaIndex: Int,
    val fractionY: Float,
    val focusYPx: Float
)

/**
 * Optional timeline route for I to integrate after native verification.
 * The existing AtlasFeatureGrid remains a fully ungrouped fallback; the host
 * alone decides which sort/context enables date headers and navigation.
 *
 * - Rebuilds header/media index and row geometry on Dispatchers.Default.
 * - No stale photos from a superseded inventory are shown while planning.
 * - A pinch keeps the focal MEDIA key through day↔month header reflow.
 * - Slider uses media count, never header count; dates are suppressed for
 *   Largest/Random or a catalogue whose chronological order is stale.
 * - No new dependencies, MediaStore writes, or image cache ownership.
 */
@Composable
fun AtlasTimelineGallery(
    items: List<MediaRecord>,
    sort: TimelineSort,
    gridState: LazyGridState,
    onOpen: (MediaRecord) -> Unit,
    modifier: Modifier = Modifier,
    viewKey: String = "photos",
    busy: Boolean = false,
    /** Pass permission/publication epoch to prevent stale grouped rows or scrubs. */
    catalogueEpoch: Long = 0L,
    /** #642: consecutive part-row days in one month share a header (person page). */
    mergeSparseDays: Boolean = false,
    /**
     * Optional Lane I host hook: receives the exact displayed timeline (or null
     * while planning, revoked or disposed). Lazy keys are positional, so a host
     * resolves a visible slot index through these slots instead of parsing
     * private URIs out of LazyGrid keys.
     */
    onDisplayedSlots: ((TimelineDisplayedSlots?) -> Unit)? = null,
    /** Optional transition to G's already existing justified original-proportion grid. */
    onOriginalProportionsRequested: ((GalleryZoomRequest) -> Unit)? = null,
    /** Host may initialise the first mounted grid after returning from ratio rows. */
    entryDensity: GridDensity? = null,
    /**
     * Host-owned batch selection: null means not selecting. While non-null a
     * tap toggles instead of opening the viewer. The grid stores no selection.
     */
    selectedMediaUris: Set<String>? = null,
    /**
     * Samsung selection (#556): a long press, then a drag without lifting,
     * a date header's check circle all report the whole new URI set here.
     * The host starts or replaces its selection holder. Null: no selection.
     */
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    onMediaToggle: ((MediaRecord) -> Unit)? = null,
    /** Selection mode keeps the host header (the selection top bar) on screen. */
    pinHeader: Boolean = false,
    /**
     * Samsung shell (#514/#506): the host's header is drawn over the top of
     * the grid and scrolls away with it; it comes back at the top or on any
     * upward scroll. The grid keeps its measured size, so nothing replans.
     */
    header: (@Composable () -> Unit)? = null,
    /** Tests and screenshots: keep the fast-scroll handle shown and grabbable. */
    fastScrollAlwaysShown: Boolean = false
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TIMELINE_PREFS, Context.MODE_PRIVATE) }
    var density by rememberSaveable(viewKey) {
        mutableStateOf(entryDensity ?: GridDensity.fromColumns(
            prefs.getInt(viewKey, GridDensity.MEDIUM.columns)
        ))
    }
    // The host supplies a one-shot large-tile return from original proportions.
    // An already restored SaveableState must not override that explicit gesture.
    LaunchedEffect(viewKey, entryDensity) {
        if (entryDensity != null && density != entryDensity) density = entryDensity
    }
    LaunchedEffect(viewKey, density) { prefs.edit().putInt(viewKey, density.columns).apply() }
    val sourceKey = GridCatalogIdentity(items, catalogueEpoch)
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val localDensity = LocalDensity.current
    val gapPx = with(localDensity) { 3.dp.roundToPx() }
    val headerHeightPx = with(localDensity) { 42.dp.roundToPx() }
    val openCurrent by rememberUpdatedState(onOpen)
    val displayedSlotsSink by rememberUpdatedState(onDisplayedSlots)
    val originalCurrent by rememberUpdatedState(onOriginalProportionsRequested)
    val selectionCurrent by rememberUpdatedState(selectedMediaUris)
    val selectionChangeCurrent by rememberUpdatedState(onSelectionChange)
    val haptics = LocalHapticFeedback.current
    val frameScope = rememberCoroutineScope()
    // One drag at a time; plain holder, the drag itself is not UI state.
    val dragHolder = remember { arrayOfNulls<DragSelectRange>(1) }
    val toggleCurrent by rememberUpdatedState(onMediaToggle)
    // Never leave a host holding slots of a timeline that is no longer mounted.
    DisposableEffect(Unit) { onDispose { displayedSlotsSink?.invoke(null) } }
    var latestPlan by remember { mutableStateOf<TimelinePlan?>(null, androidx.compose.runtime.referentialEqualityPolicy()) }
    // Tracks the layout actually selected for the grid, including an immediate
    // cache hit before the asynchronous latestPlan pointer is updated.
    var displayedPlan by remember { mutableStateOf<TimelinePlan?>(null, androidx.compose.runtime.referentialEqualityPolicy()) }
    var pending by remember { mutableStateOf<PendingTimelineFocus?>(null) }
    // #507 continuous zoom: while a pinch runs and while it settles, the grid
    // is drawn scaled around the fingers. zoomScale is relative to the tile
    // size the pinch started from (zoomStartCellPx); the layer divides by the
    // displayed tile size, so swapping to the next level never pops.
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomStartCellPx by remember { mutableIntStateOf(0) }
    var zoomFocus by remember { mutableStateOf(Offset.Zero) }
    var zoomSettleTo by remember { mutableStateOf<GridDensity?>(null) }
    val zoomJob = remember { arrayOfNulls<Job>(1) }
    var zoomShiftX by remember { mutableFloatStateOf(0f) }
    val zoomHandoff = remember { arrayOfNulls<ZoomHandoff>(1) }
    // Plain State holders: the fast-scroll layer reads them per frame in its
    // own small scope, so a drag never recomposes the whole gallery (§0).
    val draggingState = remember { mutableStateOf<Float?>(null) }
    var draggingFraction by draggingState
    val pendingScrubState = remember { mutableStateOf<TimelineScrubCommit?>(null) }
    var pendingScrub by pendingScrubState

    /**
     * Only the exact currently mounted plan may supply an anchor. If the layout
     * is changing, the host still receives a null focus and must not guess an
     * index from header-inclusive LazyGrid positions.
     */
    fun requestOriginal(focusX: Float, focusY: Float, candidate: TimelinePlan?) {
        val info = gridState.layoutInfo
        val matching = candidate?.takeIf {
            it.source === items && it.publication == sourceKey &&
                it.density == density && it.sort == sort &&
                it.geometry.timeline.slots.size == info.totalItemsCount &&
                it.bounds.matchesMeasuredViewport(
                    info.viewportSize.width, info.viewportSize.height)
        }
        val cells = matching?.let { plan ->
            info.visibleItemsInfo.mapNotNull { cell ->
                val slot = plan.geometry.timeline.slots.getOrNull(cell.index)
                    as? TimelineSlot.Media ?: return@mapNotNull null
                val media = items.getOrNull(slot.mediaIndex) ?: return@mapNotNull null
                GridVisibleCell(media.uri, slot.mediaIndex, cell.offset.x, cell.offset.y,
                    cell.size.width, cell.size.height)
            }
        }.orEmpty()
        originalCurrent?.invoke(GalleryZoomRequest(
            focalGridAnchor(cells, focusX, focusY), focusY, catalogueEpoch,
            destination = GalleryZoomStop.ORIGINAL
        ))
    }

    /**
     * Density change from TalkBack (Samsung has no visible "View as" row; the
     * sighted route is pinch). Anchors on the centre photo like before.
     */
    fun chooseDensity(next: GridDensity) {
        // The grid is measured by its own BoxWithConstraints below; choose a
        // stable visible focal cell, not an index derived from header counts.
        if (next != density) {
            val info = gridState.layoutInfo
            // Called from accessibility, so the last
            // completed timeline can outlive a rotation/resized viewport.
            // Do not capture focal coordinates from a differently sized
            // layout while its replacement geometry is still planning.
            val plan = displayedPlan?.takeIf {
                it.source === items && it.publication == sourceKey &&
                    it.density == density && it.sort == sort &&
                    it.geometry.timeline.slots.size == info.totalItemsCount &&
                    it.bounds.matchesMeasuredViewport(
                        info.viewportSize.width, info.viewportSize.height)
            }
            if (plan != null) {
                val focusX = info.viewportSize.width / 2f
                val focusY = info.viewportSize.height / 2f
                val cells = info.visibleItemsInfo.mapNotNull { cell ->
                    val slot = plan.geometry.timeline.slots.getOrNull(cell.index) as? TimelineSlot.Media
                        ?: return@mapNotNull null
                    val media = items.getOrNull(slot.mediaIndex) ?: return@mapNotNull null
                    GridVisibleCell(media.uri, slot.mediaIndex, cell.offset.x, cell.offset.y,
                        cell.size.width, cell.size.height)
                }
                val anchor = focalGridAnchor(cells, focusX, focusY)
                pending = anchor?.let { PendingTimelineFocus(sourceKey, it.mediaKey, it.itemIndex, it.fractionY, focusY) }
            } else pending = null
            density = next
        }
    }

    var shellHeaderPx by remember { mutableIntStateOf(0) }
    // #641: the header stays pinned as a slim bar; its count line folds away
    // while the grid is off its top. Flips only when the grid crosses its top.
    val headerCollapsed by remember(gridState) { derivedStateOf { gridState.scrolledFromTop() } }
    Box(modifier.then(if (header != null) Modifier.clipToBounds() else Modifier)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(localDensity) { maxWidth.roundToPx() }
            val heightPx = with(localDensity) { maxHeight.roundToPx() }
            if (widthPx < 64 || heightPx <= 0) return@BoxWithConstraints
            val bounds = remember(sourceKey, widthPx, heightPx, gapPx) {
                GridViewportGeometry(widthPx, heightPx, gapPx, gapPx, items.size)
            }
            // Seven-column thumbnails should never request the same decode
            // footprint as three-column tiles; stay near measured physical cells.
            val sidePx = bounds.cellSidePx(density.columns)
            val previewPixels = gridPreviewRequestPixels(sidePx, sidePx)
            // #555: while the fast-scroll handle is dragged, tiles show their
            // micro thumbnail only (Samsung's scrub thumbnails); on release the
            // sharp tile draws over it, never over grey.
            val scrubbing by remember { derivedStateOf { draggingState.value != null } }
            // Reuse only completed plans for this exact authorized inventory,
            // viewport, sort, locale and time zone. Remember invalidates the
            // entire bounded cache on a new catalogue/grant or layout geometry.
            val recentPlans = remember(sourceKey, sort, bounds, headerHeightPx, locale, zone) {
                RecentGridDensityPlans<TimelinePlan>(capacity = 2)
            }
            LaunchedEffect(sourceKey, sort, density, bounds, headerHeightPx, locale, zone) {
                draggingFraction = null
                val cached = recentPlans.get(density)
                if (cached != null) {
                    latestPlan = cached
                    return@LaunchedEffect
                }
                latestPlan = null // no stale plan while 150k rows are replanned
                val parent = coroutineContext
                val computed = withContext(Dispatchers.Default) {
                    val projection = ArrayList<TimelineMedia>(items.size)
                    items.forEachIndexed { i, media ->
                        if ((i and 511) == 0) parent.ensureActive()
                        projection.add(TimelineMedia(media.uri, media.takenAtMillis))
                    }
                    val index = buildGridTimelineIndex(projection, sort, density, zone, locale,
                        mergeSparseDaysBelow = if (mergeSparseDays) density.columns else 0) {
                        parent.ensureActive()
                    }
                    val geometry = planTimelineVisualRows(index, density.columns, bounds,
                        headerHeightPx) { parent.ensureActive() }
                    TimelinePlan(items, sourceKey, sort, density, bounds, geometry)
                }
                recentPlans.putCompleted(density, computed)
                latestPlan = computed
            }
            fun matchingPlan(plan: TimelinePlan): Boolean =
                plan.source === items && plan.publication == sourceKey &&
                plan.density == density && plan.sort == sort && plan.bounds == bounds
            // A repeat density can show its cached layout on the very first
            // composition, instead of flashing "Arranging timeline…" while an
            // effect waits for its first frame.
            // #507: while the next level is replanned, keep showing the level
            // the pinch left (same items, sort and viewport, only the density
            // differs) instead of flashing "Arranging timeline…".
            val ready = currentOrCachedGridPlan(
                latestPlan, recentPlans.peek(density), ::matchingPlan
            ) ?: displayedPlan?.takeIf {
                it.source === items && it.publication == sourceKey && it.sort == sort &&
                    it.bounds == bounds && it.density != density
            }
            // #507: arriving from original rows, start at their drawn photo size.
            val entryZoom = rememberZoomEntry(false, catalogueEpoch, ready != null)
            // Settle the zoom once the destination level is on screen: animate
            // to its own tile size (visual scale 1), anchored on the focus.
            LaunchedEffect(ready?.density, zoomSettleTo, bounds) {
                val target = zoomSettleTo ?: return@LaunchedEffect
                if (ready?.density != target || zoomStartCellPx <= 0) return@LaunchedEffect
                zoomSettleTo = null
                val cell = bounds.cellSidePx(target.columns)
                val end = cell.toFloat() / zoomStartCellPx
                val from = zoomScale
                // The photo under the fingers changes column with the count:
                // start the new level shifted so it stays put, then slide it
                // home while the scale settles.
                val handoff = zoomHandoff[0]
                zoomHandoff[0] = null
                val column = handoff?.let { h ->
                    runCatching { ready.geometry.columnOfMedia(h.mediaIndex) }.getOrNull()
                }
                val shiftFrom = if (handoff == null || column == null) 0f else zoomHandoffShiftPx(
                    zoomFocus.x, handoff.fractionX,
                    (bounds.horizontalInsetPx + column * (cell + bounds.gapPx)).toFloat(),
                    cell.toFloat(), from * zoomStartCellPx / cell)
                zoomShiftX = shiftFrom
                zoomJob[0]?.cancel()
                zoomJob[0] = frameScope.launch {
                    animate(0f, 1f, animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow)) { p, _ ->
                        zoomScale = from + (end - from) * p
                        zoomShiftX = shiftFrom * (1f - p)
                    }
                    // Settled: back to an unscaled grid. A new pinch cancels the
                    // job first and rebases the zoom itself.
                    zoomStartCellPx = 0
                    zoomScale = 1f
                    zoomShiftX = 0f
                }
            }
            // Toolbar and slider must use what the grid actually displays, not
            // a prior-density latestPlan while a completed cache hit is shown.
            // SideEffect only publishes after a successful composition.
            SideEffect { displayedPlan = ready }
            // Lane I hook: publish exactly the displayed slots once per ready
            // instance (null while replanning), never once per frame.
            LaunchedEffect(ready) {
                displayedSlotsSink?.invoke(ready?.let {
                    TimelineDisplayedSlots(it.source, catalogueEpoch, it.geometry.timeline)
                })
            }
            // A Slider drag is a single navigation command. An effect scoped to
            // the *current measured plan* is cancelled on permission, density,
            // sort or viewport changes. Unlike rememberCoroutineScope.launch it
            // cannot resume an old seek against a newly mounted catalogue.
            LaunchedEffect(pendingScrub, ready, sourceKey) {
                val command = pendingScrub ?: return@LaunchedEffect
                withFrameNanos { }
                // 'ready' may be an authorized completed cache hit published
                // before latestPlan has caught up. Effect keys + the command's
                // exact catalogue/layout/slot admission are the authority here;
                // checking the secondary latestPlan pointer loses valid seeks.
                if (ready != null &&
                    command.isCurrent(sourceKey, ready.geometry.timeline,
                        gridState.layoutInfo.totalItemsCount)) {
                    gridState.scrollToItem(command.slotIndex)
                }
                if (pendingScrub === command) pendingScrub = null
            }
            if (ready == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (items.isEmpty() && !busy) "No photos here yet" else "Arranging timeline…")
                }
            } else {
                // #507: a pinch commit swaps in a new grid that would first be
                // measured at the old level's index (a one-frame vertical jump).
                // Ask for the anchored position before that first measure; the
                // effect below still validates and settles it.
                val earlyFocus = pending
                if (earlyFocus != null && ready.density == density &&
                    earlyFocus.sourceEpoch == sourceKey) {
                    val index = currentFocalIndexOrNull(
                        earlyFocus.mediaIndex, earlyFocus.mediaKey, items.size) { items[it].uri }
                    if (index != null) {
                        val target = ready.geometry.targetMedia(index, earlyFocus.fractionY, earlyFocus.focusYPx)
                        SideEffect {
                            if (pending === earlyFocus) gridState.requestScrollToItem(
                                target.firstSlotIndex, target.firstSlotScrollOffsetPx)
                        }
                    }
                }
                // The pending gesture anchor is applied after the matching header
                // layout has been published. An old photo cannot map to a new key.
                LaunchedEffect(ready, pending) {
                    val focus = pending ?: return@LaunchedEffect
                    // The fallback level shown during a replan is not the
                    // destination; keep the anchor for the real layout.
                    if (ready.density != density) return@LaunchedEffect
                    // A recycled media URI on a new grant/publication is not
                    // permission to carry the prior gesture anchor forward.
                    if (focus.sourceEpoch != sourceKey) {
                        pending = null
                        return@LaunchedEffect
                    }
                    val index = currentFocalIndexOrNull(
                        focus.mediaIndex, focus.mediaKey, items.size) { items[it].uri }
                    if (index != null) {
                        val target = ready.geometry.targetMedia(index, focus.fractionY, focus.focusYPx)
                        withFrameNanos { }
                        // Do not scroll a republished or not-yet-mounted header index.
                        // A cached ready layout is real and already shown,
                        // even while latestPlan still references the old density.
                        // LaunchedEffect(ready,pending) cancels on reflow; the
                        // exact visible slot count prevents wrong-layout jumps.
                        if (pending === focus &&
                            gridState.layoutInfo.totalItemsCount == ready.geometry.timeline.slots.size) {
                            gridState.scrollToItem(target.firstSlotIndex, target.firstSlotScrollOffsetPx)
                        }
                    }
                    pending = null
                }
                // #555: warm the rows the grid is about to show.
                GridThumbnailPrefetch(gridState, ready.density.columns, previewPixels,
                    layoutKey = ready, scrubbing = scrubbing) { slot ->
                    (ready.geometry.timeline.slots.getOrNull(slot) as? TimelineSlot.Media)
                        ?.let { items.getOrNull(it.mediaIndex) }
                }
                // Current layout and grant scope the positional saved-state keys.
                // Header/media URIs must never reach LazyGrid saveable keys.
                key(sourceKey, sort, ready) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(ready.density.columns),
                    state = gridState,
                    contentPadding = PaddingValues(start = 3.dp, end = 3.dp,
                        top = with(localDensity) { shellHeaderPx.toDp() },
                        bottom = LocalAtlasBottomChrome.current),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize().testTag("atlas-timeline-grid")
                        // #494: Initial-pass parent detector; tiles can't swallow it.
                        .galleryPinchLadder(ready, density, newTracker = { slop ->
                            GalleryPinchTracker(GalleryZoomStop.forDensity(density), slop,
                                allowOriginal = originalCurrent != null)
                        }, onZoom = { zoom, focusPoint ->
                            if (zoomStartCellPx <= 0 || zoomJob[0]?.isActive == true) {
                                zoomJob[0]?.cancel()
                                zoomStartCellPx = bounds.cellSidePx(ready.density.columns)
                                zoomShiftX = 0f
                            }
                            zoomSettleTo = null
                            zoomFocus = focusPoint
                            zoomScale = zoom
                        }, onCancelled = {
                            // Same level: spring back to its own tile size.
                            zoomSettleTo = ready.density
                        }) { next, focusPoint ->
                            if (next == GalleryZoomStop.ORIGINAL) {
                                // Original rows start drawn at the size the fingers left.
                                if (zoomStartCellPx > 0) ZoomEntryHandoff.offer(true, catalogueEpoch,
                                    zoomScale * zoomStartCellPx, focusPoint)
                                zoomJob[0]?.cancel()
                                zoomSettleTo = null
                                zoomStartCellPx = 0
                                zoomScale = 1f
                                zoomShiftX = 0f
                                // The existing ratio destination owns its own lazy state
                                // and metadata. No 150k source rescan on the UI thread.
                                requestOriginal(focusPoint.x, focusPoint.y, ready)
                            } else {
                                val cells = gridState.layoutInfo.visibleItemsInfo.mapNotNull { cell ->
                                    val slot = ready.geometry.timeline.slots.getOrNull(cell.index)
                                        as? TimelineSlot.Media ?: return@mapNotNull null
                                    val media = items.getOrNull(slot.mediaIndex) ?: return@mapNotNull null
                                    GridVisibleCell(media.uri, slot.mediaIndex, cell.offset.x,
                                        cell.offset.y, cell.size.width, cell.size.height)
                                }
                                val anchor = focalGridAnchor(cells, focusPoint.x, focusPoint.y)
                                pending = anchor?.let {
                                    PendingTimelineFocus(sourceKey, it.mediaKey, it.itemIndex,
                                        it.fractionY, focusPoint.y)
                                }
                                // The focus is the layer's fixed point, so layout and
                                // drawn fractions agree there.
                                zoomHandoff[0] = anchor?.let { ZoomHandoff(it.itemIndex, it.fractionX) }
                                val nextDensity = checkNotNull(next.squareDensityOrNull())
                                zoomSettleTo = nextDensity
                                density = nextDensity
                            }
                        }
                        // #556 Samsung long-press drag selection with edge auto-scroll.
                        .gridDragSelect(
                            enabled = onSelectionChange != null,
                            gridState = gridState,
                            frameScope = frameScope,
                            isMediaSlot = { ready.geometry.timeline.slots.getOrNull(it) is TimelineSlot.Media },
                            onStart = start@{ slotIndex ->
                                val sink = selectionChangeCurrent ?: return@start false
                                val slot = ready.geometry.timeline.slots.getOrNull(slotIndex)
                                    as? TimelineSlot.Media ?: return@start false
                                val range = DragSelectRange.start(slot.mediaIndex,
                                    selectionCurrent.orEmpty()) { items.getOrNull(it)?.uri }
                                    ?: return@start false
                                dragHolder[0] = range
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sink(range.selection)
                                true
                            },
                            onExtend = { slotIndex ->
                                val range = dragHolder[0]
                                val slot = ready.geometry.timeline.slots.getOrNull(slotIndex)
                                    as? TimelineSlot.Media
                                if (range != null && slot != null) {
                                    selectionChangeCurrent?.invoke(range.extendTo(slot.mediaIndex))
                                }
                            },
                            onEnd = { dragHolder[0] = null }
                        )
                        // Non-gesture route into original proportions for TalkBack
                        // (Lukas removed the visible button, #494).
                        .semantics {
                            stateDescription = "${ready.density.columns} columns"
                            customActions = buildList {
                                if (originalCurrent != null) add(
                                    CustomAccessibilityAction(ORIGINAL_PROPORTIONS_ACTION) {
                                        val info = gridState.layoutInfo
                                        requestOriginal(info.viewportSize.width / 2f,
                                            info.viewportSize.height / 2f, ready)
                                        true
                                    })
                                GridDensity.entries.getOrNull(density.ordinal - 1)?.let { larger ->
                                    add(CustomAccessibilityAction(LARGER_TILES_ACTION) {
                                        chooseDensity(larger); true })
                                }
                                GridDensity.entries.getOrNull(density.ordinal + 1)?.let { smaller ->
                                    add(CustomAccessibilityAction(SMALLER_TILES_ACTION) {
                                        chooseDensity(smaller); true })
                                }
                            }
                        }
                        // #507: drawn scaled around the fingers while zooming. Inside the
                        // pointer modifiers, so pinch input is never scaled. Draw-phase
                        // reads only: no recomposition, layout or decode per frame.
                        .zoomEntryLayer(entryZoom) { bounds.cellSidePx(ready.density.columns).toFloat() }
                        // Only while a zoom runs: a fling never draws through
                        // an extra layer (zoomStartCellPx changes at start and
                        // end of a zoom, not per frame).
                        .then(if (zoomStartCellPx <= 0) Modifier else Modifier.graphicsLayer {
                            val start = zoomStartCellPx
                            val visual = if (start <= 0) 1f else zoomScale * start /
                                bounds.cellSidePx(ready.density.columns)
                            scaleX = visual
                            scaleY = visual
                            translationX = zoomShiftX
                            if (size.width > 0f && size.height > 0f) {
                                transformOrigin = TransformOrigin(
                                    (zoomFocus.x / size.width).coerceIn(0f, 1f),
                                    (zoomFocus.y / size.height).coerceIn(0f, 1f))
                            }
                        })
                ) {
                    itemsIndexed(ready.geometry.timeline.slots,
                        key = { index, _ -> gridLazySaveablePositionKey(index) },
                        span = { _, slot -> if (slot is TimelineSlot.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                    ) { _, slot ->
                        when (slot) {
                            is TimelineSlot.Header -> {
                                val selectingNow = selectedMediaUris != null && onSelectionChange != null
                                // T6: the circle fills by itself once every item is selected.
                                val groupChecked = selectingNow && groupFullySelected(
                                    slot.firstMediaIndex, slot.mediaCount,
                                    selectedMediaUris.orEmpty()) { items.getOrNull(it)?.uri }
                                Row(
                                    Modifier.fillMaxWidth().height(with(localDensity) { headerHeightPx.toDp() })
                                        .then(if (selectingNow) Modifier.clickable(
                                            role = Role.Checkbox,
                                            onClickLabel = if (groupChecked) "Deselect ${slot.label}"
                                                else "Select ${slot.label}"
                                        ) {
                                            val current = selectionCurrent ?: return@clickable
                                            selectionChangeCurrent?.invoke(toggleGroup(
                                                slot.firstMediaIndex, slot.mediaCount, current
                                            ) { items.getOrNull(it)?.uri })
                                        }.semantics { selected = groupChecked } else Modifier)
                                        .padding(start = 8.dp)
                                        .testTag("atlas-timeline-header-${slot.stableKey}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectingNow) {
                                        SelectionCheckCircle(groupChecked, size = 20, onPage = true)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(slot.label, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            is TimelineSlot.Media -> {
                                val media = items[slot.mediaIndex]
                                val selecting = selectedMediaUris != null && onMediaToggle != null
                                val isSelected = selecting && media.uri in selectedMediaUris.orEmpty()
                                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    // The pointer long press belongs to the grid's drag
                                    // detector; TalkBack gets the same start as an action.
                                    .then(if (onSelectionChange != null && !selecting) Modifier.semantics {
                                        onLongClick("Select ${media.name}") {
                                            selectionChangeCurrent?.invoke(
                                                selectionCurrent.orEmpty() + media.uri)
                                            true
                                        }
                                    } else Modifier)
                                    .clickable(
                                        onClickLabel = if (selecting) "Select ${media.name}"
                                            else "Open ${media.name}",
                                        onClick = {
                                            // Read live state at click time: a stale frame
                                            // must never open the viewer mid-selection.
                                            val toggle = toggleCurrent
                                            if (selectionCurrent != null && toggle != null) toggle(media)
                                            else openCurrent(media)
                                        }
                                    )
                                    .then(if (selecting) Modifier.semantics { selected = isSelected }
                                        else Modifier)
                                    .testTag("atlas-timeline-media-${media.id}")) {
                                    MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Crop, previewPixels,
                                        gridTile = true, sharp = !scrubbing)
                                    if (media.isVideo) Text("▶", Modifier.align(Alignment.BottomEnd).padding(6.dp))
                                    // Samsung: every tile shows its circle while selecting.
                                    if (selecting) SelectionCheckCircle(isSelected,
                                        Modifier.align(Alignment.TopStart).padding(5.dp))
                                }
                            }
                        }
                    }
                }
                } // Current authorized timeline's remembered-item scope.
            }
        }
        if (header != null) {
            Box(Modifier.fillMaxWidth()
                .onSizeChanged { shellHeaderPx = it.height }
                .testTag("atlas-scrolling-header")) {
                CompositionLocalProvider(
                    LocalAtlasHeaderCollapsed provides (headerCollapsed && !pinHeader)) { header() }
            }
        }
        if (items.size > 1) {
            val planCurrent by rememberUpdatedState(displayedPlan)
            TimelineFastScrollLayer(
                items = items,
                sort = sort,
                gridState = gridState,
                sourceKey = sourceKey,
                timelineFor = { info ->
                    planCurrent?.takeIf {
                        it.source === items && it.publication == sourceKey &&
                            it.density == density && it.sort == sort &&
                            it.geometry.timeline.slots.size == info.totalItemsCount &&
                            it.bounds.matchesMeasuredViewport(
                                info.viewportSize.width, info.viewportSize.height)
                    }?.geometry?.timeline
                },
                draggingState = draggingState,
                pendingScrubState = pendingScrubState,
                alwaysShown = fastScrollAlwaysShown,
                topInset = with(localDensity) { shellHeaderPx.toDp() }
            )
        }
    }
}

/**
 * Fast-scroll host in its own composition scope: it alone reads the grid's
 * per-frame scroll state, through derived values that change per photo or
 * per bucket, not per pixel (§0 frame budget).
 */
@Composable
private fun TimelineFastScrollLayer(
    items: List<MediaRecord>,
    sort: TimelineSort,
    gridState: LazyGridState,
    sourceKey: GridCatalogIdentity,
    timelineFor: (LazyGridLayoutInfo) -> GridTimelineIndex?,
    draggingState: MutableState<Float?>,
    pendingScrubState: MutableState<TimelineScrubCommit?>,
    alwaysShown: Boolean,
    topInset: androidx.compose.ui.unit.Dp
) {
    val timelineForCurrent by rememberUpdatedState(timelineFor)
    val timeline by remember { derivedStateOf { timelineForCurrent(gridState.layoutInfo) } }
    val currentFraction by remember {
        derivedStateOf {
            timeline?.thumbFraction(gridState.firstVisibleItemIndex, !gridState.canScrollForward) ?: 0f
        }
    }
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
    var draggingFraction by draggingState
    var pendingScrub by pendingScrubState
    val tl = timeline
    // Hold the released thumb/bubble while the deferred seek is in flight;
    // otherwise clearing draggingFraction on release flashes the old position.
    // A stale grant, regroup, or unmounted grid is never preview-authorized.
    val shownFraction = draggingFraction ?: pendingScrub?.heldPreviewFractionOrNull(
        sourceKey, tl, gridState.layoutInfo.totalItemsCount) ?: currentFraction
    val destination = tl?.scrubTarget(shownFraction)
    // The bubble follows the ACTIVE sort: dates, size buckets or position.
    val label = destination?.let { target ->
        fastScrollBubbleLabel(sort, target.dateLabel,
            items.getOrNull(target.mediaIndex)?.sizeBytes ?: 0L, target.mediaIndex, items.size)
    }
    TimelineFastScroller(
        fraction = shownFraction,
        label = label,
        enabled = tl != null,
        gridScrolling = scrolling,
        alwaysShown = alwaysShown,
        topInset = topInset,
        onDrag = {
            // A fresh drag supersedes any unfinished jump.
            pendingScrub = null
            draggingFraction = it
            // Follow the thumb live (#493); requestScrollToItem is
            // measure-safe and jumps straight there on long moves.
            tl?.liveScrubSlotOrNull(it, gridState.layoutInfo.totalItemsCount)
                ?.let { slot -> gridState.requestScrollToItem(slot) }
        },
        onRelease = {
            val requested = draggingFraction ?: currentFraction
            val target = tl?.scrubTarget(requested)
            if (target != null && tl != null) {
                pendingScrub = TimelineScrubCommit(
                    sourceKey, tl, target.slotIndex, tl.slots.size,
                    requestedFraction = requested
                )
            }
            draggingFraction = null
        }
    )
}
