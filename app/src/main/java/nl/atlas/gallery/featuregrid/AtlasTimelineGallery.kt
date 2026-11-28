package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import nl.atlas.gallery.featuredesign.AtlasEmptyState
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
import androidx.compose.ui.zIndex
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
import nl.atlas.gallery.SharedPhotoOrigins
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    val geometry: TimelineGridGeometry,
    val incrementalScrollTarget: TimelineScrollTarget? = null
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

/**
 * FLOW-1 (#711): one two-layer pinch, from its first sample until it has
 * settled. Screen coordinates are the grid composable's own.
 */
private class CrossPinch(
    /** The level (density ordinal) the live grid shows; the pinch is measured from it. */
    val baseLevel: Int,
    val anchorIndex: Int,
    val anchorKey: String,
    val fractionX: Float,
    val fractionY: Float,
    /** Finger focus when the pinch started; the anchor point stays here. */
    val focus: Offset,
    /** The anchor photo's rectangle in the live grid at rest. */
    val baseAnchor: Rect,
    val startPaddingPx: Int,
    val topPaddingPx: Int
) {
    /** Release animation running: further samples are ignored. Main thread only. */
    var settling: Boolean = false
    /** Where the new level opens when the fingers moved the photo; null = the unmoved target. */
    var commitTarget: TimelineScrollTarget? = null
}

/** Another level of a [CrossPinch], laid out as it would be scrolled with the anchor under the focus. */
private class CrossGhost(
    val plan: TimelinePlan,
    val target: TimelineScrollTarget,
    val cells: List<CrossCell>,
    val anchor: Rect
)

private const val CROSS_MAX_LEVEL = 4 // GridDensity.YEAR.ordinal
private const val CROSS_IDLE_PREFETCH_DELAY_MS = 600L

private data class IncrementalScrollFocus(
    val mediaKey: String,
    val fractionY: Float,
    val focusYPx: Float
)

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
    /** Only for a same-grant observer publication; never for a regrant. */
    preserveScrollOnIncremental: Boolean = false,
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
    fastScrollAlwaysShown: Boolean = false,
    mediaSort: nl.atlas.gallery.MediaSort? = null,
    /** Launch the exact source-ordered range behind a displayed month header. */
    onDeclutterMonth: ((TimelineSlot.Header, List<MediaRecord>) -> Unit)? = null
) {
    val context = LocalContext.current
    // #435: application context reuses the file StartupPreferenceWarmup loaded off Main.
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(TIMELINE_PREFS, Context.MODE_PRIVATE)
    }
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
    // Capture the actual visible photo, not a raw LazyGrid index: inserting
    // headers or rows above it otherwise jumps the viewport. The prior plan
    // is retained only as a non-rendered anchor while the new plan computes.
     val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val localDensity = LocalDensity.current
    val gapPx = with(localDensity) { 3.dp.roundToPx() }
    // Month actions need a full 48 dp target; planning and painted rows share this height.
    val headerHeightPx = with(localDensity) { 48.dp.roundToPx() }
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
    // Async sorting briefly supplies an empty list before a new publication's
    // sorted/hidden-photo projection lands. Carry the captured anchor over
    // that transient state without ever rendering an obsolete media plan.
    val retainedIncrementalFocus = remember { arrayOfNulls<IncrementalScrollFocus>(1) }
    val incrementalFocus = remember(sourceKey) {
        if (!preserveScrollOnIncremental) {
            retainedIncrementalFocus[0] = null
            null
        } else retainedIncrementalFocus[0] ?: run {
            val former = displayedPlan
            val layout = gridState.layoutInfo
            if (former == null || former.source === items ||
                former.density != density || former.sort != sort ||
                !gridState.canScrollBackward ||
                former.geometry.timeline.slots.size != layout.totalItemsCount ||
                !former.bounds.matchesMeasuredViewport(
                    layout.viewportSize.width, layout.viewportSize.height)) null
            else layout.visibleItemsInfo.firstNotNullOfOrNull { visible ->
                val mediaSlot = former.geometry.timeline.slots.getOrNull(visible.index)
                    as? TimelineSlot.Media ?: return@firstNotNullOfOrNull null
                val oldItem = former.source.getOrNull(mediaSlot.mediaIndex)
                    ?: return@firstNotNullOfOrNull null
                IncrementalScrollFocus(
                    oldItem.uri, .5f, visible.offset.y + visible.size.height / 2f
                ).also { retainedIncrementalFocus[0] = it }
            }
        }
    }
    var lastReanchoredPublication by remember {
        mutableStateOf<GridCatalogIdentity?>(null)
    }
    var pending by remember { mutableStateOf<PendingTimelineFocus?>(null) }
    // FLOW-1 (#711) two-layer pinch (Samsung GRID:G2-G7). While a pinch runs
    // and settles, the live grid and the neighbouring level(s) are drawn
    // together, crossfading around the photo under the fingers. The position
    // is per-frame state read only in draw-phase graphicsLayer blocks and in
    // a derived level pair, so a gesture frame never recomposes the grid.
    var cross by remember { mutableStateOf<CrossPinch?>(null) }
    val crossPosition = remember { mutableFloatStateOf(0f) }
    // How far the fingers' centre has moved since the pinch began: the photo
    // under them follows it. Read only while drawing (no recomposition).
    val crossShift = remember { mutableStateOf(Offset.Zero) }
    val crossJob = remember { arrayOfNulls<Job>(1) }
    // Bumped when a neighbouring level's plan lands in the cache.
    var plansVersion by remember { mutableIntStateOf(0) }
    // A commit whose level had no plan yet: keep showing the old level meanwhile.
    var densityHandoff by remember { mutableStateOf(false) }
    // Exact scroll for the level a pinch just committed, applied before its first measure.
    var commitScroll by remember { mutableStateOf<Pair<GridDensity, TimelineScrollTarget>?>(null) }
    val reduceMotion = remember(context) { nl.atlas.gallery.featuredesign.AtlasMotion.animationsDisabled(context) }
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
    var crossChoose: ((GridDensity) -> Boolean)? = null
    fun chooseDensity(next: GridDensity) {
        // FLOW-1: the same animated, anchored commit as a pinch, on the centre photo.
        if (next != density && crossChoose?.invoke(next) == true) return
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
            val recentPlans = remember(sourceKey, sort, mediaSort, bounds, headerHeightPx, locale, zone) {
                // FLOW-1: the shown level plus the two a pinch can enter.
                RecentGridDensityPlans<TimelinePlan>(capacity = 3)
            }
            // One level's complete plan. Neighbouring levels (FLOW-1) are planned
            // the same way, without the incremental re-anchor target.
            suspend fun buildPlan(level: GridDensity, focus: IncrementalScrollFocus?): TimelinePlan {
                val parent = coroutineContext
                return withContext(Dispatchers.Default) {
                    // PERF-6 (#127): no TimelineMedia projection and no URI
                    // string per row. Slots read keys from `items` on demand.
                    val anchorKey = focus?.mediaKey
                    val anchorHash = anchorKey?.hashCode() ?: 0
                    var incrementalAnchorIndex = -1
                    if (anchorKey != null) for (i in items.indices) {
                        if ((i and 511) == 0) parent.ensureActive()
                        val media = items[i]
                        if (media.uriHashCodeWithoutAllocation() == anchorHash && media.uri == anchorKey) {
                            incrementalAnchorIndex = i
                        }
                    }
                    val byModified = mediaSort?.key == nl.atlas.gallery.MediaSortKey.DATE_MODIFIED
                    val index = buildGridTimelineIndex(items.size, { i ->
                            val media = items[i]
                            if (byModified && media.modifiedAtSeconds > 0L &&
                                media.modifiedAtSeconds <= Long.MAX_VALUE / 1000L
                            ) media.modifiedAtSeconds * 1000L else media.takenAtMillis
                        },
                        { items[it].uri }, sort, level, zone, locale,
                        mergeSparseDaysBelow = if (mergeSparseDays) level.columns else 0) {
                        parent.ensureActive()
                    }
                    val geometry = planTimelineVisualRows(index, level.columns, bounds,
                        headerHeightPx) { parent.ensureActive() }
                    val scrollTarget = if (incrementalAnchorIndex >= 0)
                        geometry.targetMedia(incrementalAnchorIndex,
                            focus!!.fractionY, focus.focusYPx)
                    else null
                    TimelinePlan(items, sourceKey, sort, level, bounds, geometry, scrollTarget)
                }
            }
            LaunchedEffect(sourceKey, sort, mediaSort, density, bounds, headerHeightPx, locale, zone) {
                draggingFraction = null
                val cached = recentPlans.get(density)
                if (cached != null) {
                    latestPlan = cached
                    return@LaunchedEffect
                }
                latestPlan = null // no stale plan while 150k rows are replanned
                val computed = buildPlan(density, incrementalFocus)
                recentPlans.putCompleted(density, computed)
                latestPlan = computed
            }
            fun matchingPlanFor(plan: TimelinePlan, level: GridDensity): Boolean =
                plan.source === items && plan.publication == sourceKey &&
                plan.density == level && plan.sort == sort && plan.bounds == bounds
            fun matchingPlan(plan: TimelinePlan): Boolean = matchingPlanFor(plan, density)
            // A repeat density can show its cached layout on the very first
            // composition, instead of flashing "Arranging timeline…" while an
            // effect waits for its first frame.
            // #507: while the next level is replanned, keep showing the level
            // the pinch left (same items, sort and viewport, only the density
            // differs) instead of flashing "Arranging timeline…".
            val ready = currentOrCachedGridPlan(
                latestPlan, recentPlans.peek(density), ::matchingPlan
            ) ?: displayedPlan?.takeIf {
                // During a density handoff, keep only the preceding mounted
                // plan. An unrelated state recomposition must not resurrect
                // an old density once its gesture has settled.
                densityHandoff &&
                    it.source === items && it.publication == sourceKey && it.sort == sort &&
                    it.bounds == bounds && it.density != density
            }
            if (densityHandoff && ready?.density == density) SideEffect { densityHandoff = false }
            // #507: arriving from original rows, start at their drawn photo size.
            val entryZoom = rememberZoomEntry(false, catalogueEpoch, ready != null)
            // FLOW-1: the levels the pinch is between. Changes only when the
            // gesture crosses a level, never per frame.
            val crossLevels by remember {
                derivedStateOf {
                    if (cross == null) null else crossFrame(crossPosition.floatValue, CROSS_MAX_LEVEL)
                        .let { it.lower to it.upper }
                }
            }
            // FLOW-1: plan the levels a pinch can reach before the fingers move:
            // one either side of the shown level while idle, and the pair a
            // running pinch is between. Completed plans only (bounded cache).
            val idleLevel = ready?.density?.takeIf { ready.density == density }
            LaunchedEffect(sourceKey, sort, mediaSort, bounds, headerHeightPx, locale, zone,
                idleLevel, crossLevels) {
                val base = idleLevel ?: return@LaunchedEffect
                val pair = crossLevels
                val wanted = (if (pair != null) listOf(pair.first, pair.second)
                    else listOf(base.ordinal - 1, base.ordinal + 1))
                    .filter { it in 0..CROSS_MAX_LEVEL && it != base.ordinal }.distinct()
                for (ordinal in wanted) {
                    val level = GridDensity.entries[ordinal]
                    if (recentPlans.peek(level) != null) continue
                    // While idle, let the shown level settle (first frames, start-up
                    // work) before planning; a running pinch needs its pair now.
                    if (pair == null) kotlinx.coroutines.delay(CROSS_IDLE_PREFETCH_DELAY_MS)
                    val plan = buildPlan(level, null)
                    // Never evict the shown level or a neighbour this loop already found.
                    val protect = (listOf(base.ordinal) + wanted).mapTo(HashSet()) { GridDensity.entries[it] }
                    recentPlans.putCompleted(level, plan, protect)
                    plansVersion++
                }
            }
            // Test hook (FLOW-1 evidence): both neighbouring levels are planned, so a
            // pinch from here draws the entered level from a real plan.
            val neighboursPlanned = ready != null && plansVersion >= 0 &&
                listOf(ready.density.ordinal - 1, ready.density.ordinal + 1)
                    .filter { it in 0..CROSS_MAX_LEVEL }
                    .all { recentPlans.peek(GridDensity.entries[it]) != null }
            // Toolbar and slider must use what the grid actually displays, not
            // a prior-density latestPlan while a completed cache hit is shown.
            // SideEffect only publishes after a successful composition.
            SideEffect {
                // Keep the previous plan available only for capturing a new
                // source's anchor. It is NEVER rendered after source change.
                if (ready != null) displayedPlan = ready
                if (ready != null && ready.source.isNotEmpty())
                    retainedIncrementalFocus[0] = null
                val target = ready?.incrementalScrollTarget
                if (target != null && ready.publication == sourceKey &&
                    lastReanchoredPublication != sourceKey) {
                    lastReanchoredPublication = sourceKey
                    gridState.requestScrollToItem(
                        target.firstSlotIndex, target.firstSlotScrollOffsetPx
                    )
                }
            }
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
            // DESIGN-6 #136: an empty library plans an empty grid too; show the
            // quiet line there, centred in the same box, instead of nothing.
            if (ready == null || items.isEmpty()) {
                // The shared quiet state centres itself above the nav pill;
                // while busy the box stays empty (state 7: no spinner wall).
                Box(Modifier.fillMaxSize()) {
                    if (items.isEmpty() && !busy) {
                        AtlasEmptyState(title = "No photos yet",
                            titleTag = "atlas-timeline-empty")
                    }
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
                // FLOW-1 (#711): geometry shared by the live grid and its pinch layers.
                val startPadPx = gapPx
                val contentWidthPx = widthPx - 2 * startPadPx
                fun levelPixels(level: Int): Int {
                    val side = bounds.cellSidePx(GridDensity.entries[level.coerceIn(0, CROSS_MAX_LEVEL)].columns)
                    return gridPreviewRequestPixels(side, side)
                }
                // A tile of this level whose own size is still decoding shows the
                // copy a neighbouring level already decoded: no grey blink at a
                // pinch commit, and none when scrolling back after one.
                val tileFallback = remember(bounds, ready.density) {
                    listOf(ready.density.ordinal - 1, ready.density.ordinal + 1)
                        .filter { it in 0..CROSS_MAX_LEVEL }.map(::levelPixels)
                        .filter { it != previewPixels }.distinct().toIntArray()
                }
                fun ghostFor(c: CrossPinch, level: Int, focusY: Float = c.focus.y): CrossGhost? {
                    val d = GridDensity.entries.getOrNull(level) ?: return null
                    val plan = recentPlans.peek(d)?.takeIf { matchingPlanFor(it, d) } ?: return null
                    if (c.anchorIndex !in items.indices) return null
                    val g = plan.geometry
                    val target = runCatching {
                        g.targetMedia(c.anchorIndex, c.fractionY, focusY - c.topPaddingPx)
                    }.getOrNull() ?: return null
                    val scrollTop = g.scrollTopFor(target)
                    val columns = composeGridColumnSlots(contentWidthPx, gapPx, d.columns)
                    val cells = crossVisibleCells(g, columns, scrollTop, c.startPaddingPx,
                        c.topPaddingPx, contentWidthPx, heightPx)
                    val anchor = crossAnchorRect(g, columns, c.anchorIndex, scrollTop,
                        c.startPaddingPx, c.topPaddingPx)
                    return CrossGhost(plan, target, cells, anchor)
                }
                val crossNow = cross
                val pair = crossLevels
                val ghosts: Map<Int, CrossGhost> = remember(crossNow, pair, plansVersion, ready) {
                    PinchCrossTiming.measure("geometry") { if (crossNow == null || pair == null) emptyMap() else buildMap {
                        for (level in listOf(pair.first, pair.second).distinct()) {
                            if (level == crossNow.baseLevel) continue
                            ghostFor(crossNow, level)?.let { put(level, it) }
                        }
                    } }
                }
                fun restRect(c: CrossPinch, level: Int): Rect =
                    if (level == c.baseLevel) c.baseAnchor
                    else ghosts[level]?.anchor ?: estimatedAnchorRect(c.focus, c.fractionX, c.fractionY,
                        bounds.cellSidePx(GridDensity.entries[level.coerceIn(0, CROSS_MAX_LEVEL)].columns).toFloat())
                fun drawable(c: CrossPinch, level: Int) = level == c.baseLevel || ghosts.containsKey(level)
                // Opacity and draw order of [level] at position [v]; null = not drawn.
                // A level with no plan yet is skipped and its partner shows fully,
                // so the screen never shows an empty band.
                fun lookAt(c: CrossPinch, level: Int, v: Float): CrossLayerLook? {
                    val frame = crossFrame(v, CROSS_MAX_LEVEL)
                    val lowerOk = drawable(c, frame.lower)
                    val upperOk = drawable(c, frame.upper)
                    return when {
                        lowerOk && upperOk -> crossLayerLook(level, frame) {
                            nl.atlas.gallery.featuredesign.AtlasMotion.SineInOut80.transform(it)
                        }
                        lowerOk || upperOk ->
                            if (level == (if (lowerOk) frame.lower else frame.upper)) CrossLayerLook(1f, false) else null
                        else -> if (level == c.baseLevel) CrossLayerLook(1f, false) else null
                    }
                }
                fun poseAt(c: CrossPinch, level: Int, v: Float): LayerPose {
                    val anchorNow = crossAnchorAt(crossFrame(v, CROSS_MAX_LEVEL), { restRect(c, it) }, c.focus)
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
                fun beginCross(focus: Offset): CrossPinch? {
                    val info = gridState.layoutInfo
                    if (ready.density != density ||
                        info.totalItemsCount != ready.geometry.timeline.slots.size) return null
                    val topPad = info.beforeContentPadding
                    val cells = info.visibleItemsInfo.mapNotNull { cell ->
                        val slot = ready.geometry.timeline.slots.getOrNull(cell.index)
                            as? TimelineSlot.Media ?: return@mapNotNull null
                        val media = items.getOrNull(slot.mediaIndex) ?: return@mapNotNull null
                        // Item offsets exclude the content padding; pointers do not.
                        GridVisibleCell(media.uri, slot.mediaIndex, cell.offset.x + startPadPx,
                            cell.offset.y + topPad, cell.size.width, cell.size.height)
                    }
                    val anchor = focalGridAnchor(cells, focus.x, focus.y) ?: return null
                    val cell = cells.first { it.itemIndex == anchor.itemIndex }
                    return CrossPinch(ready.density.ordinal, anchor.itemIndex, anchor.mediaKey,
                        anchor.fractionX, anchor.fractionY, focus,
                        Rect(cell.x.toFloat(), cell.y.toFloat(), (cell.x + cell.width).toFloat(),
                            (cell.y + cell.height).toFloat()), startPadPx, topPad)
                }
                fun finishCross(c: CrossPinch, target: Int) {
                    if (target != c.baseLevel) {
                        val level = GridDensity.entries[target]
                        val ghost = ghostFor(c, target)
                        if (ghost != null) {
                            // The new grid opens exactly where its pinch layer was drawn,
                            // including where the fingers moved it.
                            commitScroll = level to (c.commitTarget ?: ghost.target)
                        } else {
                            pending = PendingTimelineFocus(sourceKey, c.anchorKey, c.anchorIndex,
                                c.fractionY, c.focus.y - c.topPaddingPx)
                            densityHandoff = true
                        }
                        density = level
                    }
                    cross = null
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
                        nl.atlas.gallery.featuredesign.AtlasMotion.PINCH_SETTLE_MS,
                        nl.atlas.gallery.featuredesign.AtlasMotion.PINCH_SETTLE_MIN_MS).let {
                        if (it == 0 && shiftFrom != shiftTo)
                            nl.atlas.gallery.featuredesign.AtlasMotion.PINCH_SETTLE_MIN_MS else it
                    }
                    crossJob[0]?.cancel()
                    crossJob[0] = frameScope.launch {
                        if (ms > 0) animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(ms,
                            easing = nl.atlas.gallery.featuredesign.AtlasMotion.SineInOut80)) { t, _ ->
                            crossPosition.floatValue = from + (to - from) * t
                            crossShift.value = androidx.compose.ui.geometry.lerp(shiftFrom, shiftTo, t)
                        }
                        crossPosition.floatValue = to
                        crossShift.value = shiftTo
                        if (cross === c) { finishCross(c, target); crossShift.value = Offset.Zero }
                    }
                }
                // TalkBack "Larger tiles" / "Smaller tiles": the same anchored,
                // animated commit as a pinch, on the photo in the middle.
                crossChoose = choose@{ next ->
                    if (cross != null) return@choose false
                    val info = gridState.layoutInfo
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
                if (commit != null && ready.density == commit.first) {
                    SideEffect {
                        if (commitScroll === commit) {
                            gridState.requestScrollToItem(commit.second.firstSlotIndex,
                                commit.second.firstSlotScrollOffsetPx)
                            commitScroll = null
                        }
                    }
                }
                // #555: warm the rows the grid is about to show.
                GridThumbnailPrefetch(gridState, ready.density.columns, previewPixels,
                    layoutKey = ready, scrubbing = scrubbing) { slot ->
                    (ready.geometry.timeline.slots.getOrNull(slot) as? TimelineSlot.Media)
                        ?.let { items.getOrNull(it.mediaIndex) }
                }
                // Current layout and grant scope the positional saved-state keys.
                // Header/media URIs must never reach LazyGrid saveable keys.
                CompositionLocalProvider(LocalGridTileFallbackPixels provides tileFallback) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                key(sourceKey, sort, ready) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(ready.density.columns),
                    state = gridState,
                    contentPadding = PaddingValues(start = 3.dp, end = 3.dp,
                        top = with(localDensity) { shellHeaderPx.toDp() },
                        bottom = LocalAtlasBottomChrome.current),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.zIndex(if (liveOnTop) 1f else 0f)
                        .fillMaxSize().testTag("atlas-timeline-grid")
                        // #494: Initial-pass parent detector; tiles can't swallow it.
                        .galleryPinchLadder(ready, density, newTracker = { slop ->
                            GalleryPinchTracker(GalleryZoomStop.forDensity(density), slop,
                                allowOriginal = originalCurrent != null)
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
                                if (kotlin.math.floor(v) != before)
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }, onCancelled = {
                            // Same level: settle back onto it.
                            cross?.let { settleCross(it.baseLevel) }
                        }) { next, focusPoint ->
                            if (next == GalleryZoomStop.ORIGINAL) {
                                // Original rows start drawn at the size the fingers left.
                                val c = cross
                                if (c != null) {
                                    val drawn = crossAnchorAt(crossFrame(crossPosition.floatValue,
                                        CROSS_MAX_LEVEL), { restRect(c, it) }, c.focus).width
                                    ZoomEntryHandoff.offer(true, catalogueEpoch, drawn, focusPoint)
                                }
                                crossJob[0]?.cancel()
                                cross = null
                                crossShift.value = Offset.Zero
                                // The existing ratio destination owns its own lazy state
                                // and metadata. No 150k source rescan on the UI thread.
                                requestOriginal(focusPoint.x, focusPoint.y, ready)
                            } else {
                                val target = checkNotNull(next.squareDensityOrNull())
                                // No photo to anchor on (empty grid): just change level.
                                if (cross != null) settleCross(target.ordinal) else density = target
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
                            crossNeighboursPlanned = neighboursPlanned
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
                        // FLOW-1: only while a pinch runs or settles (a fling never
                        // draws through an extra layer). Draw-phase reads only.
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
                    itemsIndexed(ready.geometry.timeline.slots,
                        key = { index, _ -> gridLazySaveablePositionKey(index) },
                        span = { _, slot -> if (slot is TimelineSlot.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                    ) { _, slot ->
                        when (slot) {
                            is TimelineSlot.Header -> {
                                val selectingNow = selectedMediaUris != null && onSelectionChange != null
                                val reviewableMonth = !selectingNow && onDeclutterMonth != null &&
                                    slot.stableKey.startsWith("header:MONTH:")
                                // T6: the circle fills by itself once every item is selected.
                                val groupChecked = selectingNow && groupFullySelected(
                                    slot.firstMediaIndex, slot.mediaCount,
                                    selectedMediaUris.orEmpty()) { items.getOrNull(it)?.uri }
                                Row(
                                    Modifier.fillMaxWidth().height(with(localDensity) { headerHeightPx.toDp() })
                                        .then(when {
                                            selectingNow -> Modifier.clickable(
                                                role = Role.Checkbox,
                                                onClickLabel = if (groupChecked) "Deselect ${slot.label}"
                                                    else "Select ${slot.label}"
                                            ) {
                                                val current = selectionCurrent ?: return@clickable
                                                selectionChangeCurrent?.invoke(toggleGroup(
                                                    slot.firstMediaIndex, slot.mediaCount, current
                                                ) { items.getOrNull(it)?.uri })
                                            }.semantics { selected = groupChecked }
                                            reviewableMonth -> Modifier.clickable(
                                                role = Role.Button,
                                                onClickLabel = "Review ${slot.label} with Declutter"
                                            ) {
                                                val end = slot.firstMediaIndex + slot.mediaCount
                                                if (slot.firstMediaIndex >= 0 && slot.mediaCount > 0 &&
                                                    end <= items.size) {
                                                    onDeclutterMonth?.invoke(
                                                        slot, items.subList(slot.firstMediaIndex, end))
                                                }
                                            }
                                            else -> Modifier
                                        })
                                        .padding(start = 8.dp)
                                        .testTag("atlas-timeline-header-${slot.stableKey}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectingNow) {
                                        SelectionCheckCircle(groupChecked, size = 20, onPage = true)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(slot.label, modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall)
                                    if (reviewableMonth) {
                                        Text("Review", modifier = Modifier.padding(horizontal = 12.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            is TimelineSlot.Media -> {
                                val media = items[slot.mediaIndex]
                                val selecting = selectedMediaUris != null && onMediaToggle != null
                                val isSelected = selecting && media.uri in selectedMediaUris.orEmpty()
                                val sourceBounds = remember(media.uri) { arrayOf<Rect?>(null) }
                                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .onGloballyPositioned { sourceBounds[0] = it.boundsInWindow() }
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
                                            else {
                                                SharedPhotoOrigins.offer(media, catalogueEpoch, sourceBounds[0])
                                                openCurrent(media)
                                            }
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
                // FLOW-1: the level(s) the pinch is flowing into, as still layers.
                if (crossNow != null) for ((level, ghost) in ghosts) key(level) {
                    val onTop by remember(crossNow, ghosts) {
                        derivedStateOf { lookAt(crossNow, level, crossPosition.floatValue)?.onTop == true }
                    }
                    val candidates = remember(level, crossNow.baseLevel, bounds) {
                        listOf(levelPixels(level), levelPixels(crossNow.baseLevel)).distinct().toIntArray()
                    }
                    val ghostSlots = ghost.plan.geometry.timeline.slots
                    val mediaAt = remember(ghostSlots, items) { { slot: Int ->
                        (ghostSlots.getOrNull(slot) as? TimelineSlot.Media)?.let { items.getOrNull(it.mediaIndex) }
                    } }
                    val headerAt = remember(ghostSlots) { { slot: Int ->
                        ghostSlots.getOrNull(slot) as? TimelineSlot.Header
                    } }
                    PinchCrossLayer(ghost.cells, mediaAt, headerAt, candidates,
                        selectedMediaUris, selectedMediaUris != null && onMediaToggle != null,
                        mediaUriAt = { items.getOrNull(it)?.uri },
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
                mediaSort = mediaSort,
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
    mediaSort: nl.atlas.gallery.MediaSort?,
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
        val media = items.getOrNull(target.mediaIndex)
        if (mediaSort != null) fastScrollBubbleLabel(mediaSort, target.dateLabel,
            media?.sizeBytes ?: 0L, media?.name.orEmpty(), target.mediaIndex, items.size)
        else fastScrollBubbleLabel(sort, target.dateLabel,
            media?.sizeBytes ?: 0L, target.mediaIndex, items.size)
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
