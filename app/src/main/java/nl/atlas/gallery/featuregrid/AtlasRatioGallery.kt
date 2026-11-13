package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.MediaRecord
import kotlin.coroutines.coroutineContext

/**
 * Original-proportion preview, deliberately independent of Lane M's unmerged record API.
 * I passes `it.displayAspectRatioOrNull()` after M is integrated; in the meantime
 * `aspectRatioOf` can be synthetic-only. Every image is centre-cropped into its
 * justified cell (#504), so cells never show letterbox bars. A null ratio (MediaStore
 * WIDTH/HEIGHT missing) starts as a cropped neutral cell; the decoded preview then
 * reports its oriented size and the rows are re-planned around it (#527), keeping
 * the photo at the top of the screen in place. The on-device picture bytes never
 * leave MediaPreview.
 *
 * The host reaches it by pinching out past the largest square stop (#494; no button)
 * and owns switching square ↔ ratio mode; RatioLayout.scrollTarget restores a focal image.
 * Only enable on chronological lists until the host defines grouping for LARGEST/RANDOM.
 */
/** Accessibility action label for leaving original rows without a pinch. */
const val SQUARE_GRID_ACTION = "Square grid"

@Composable
fun AtlasRatioGallery(
    items: List<MediaRecord>,
    aspectRatioOf: (MediaRecord) -> Float?,
    onOpen: (MediaRecord) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onLayoutReady: (RatioLayout) -> Unit = {},
    /** The host publication epoch, including grant changes on a reused list. */
    catalogueEpoch: Long = 0L,
    /** Pinch inward from original rows to the adjacent large square grid stop. */
    onCompactGridRequested: ((GalleryZoomRequest) -> Unit)? = null
) {
    val currentOpen by rememberUpdatedState(onOpen)
    val currentAspect by rememberUpdatedState(aspectRatioOf)
    val currentLayoutReady by rememberUpdatedState(onLayoutReady)
    val compactCurrent by rememberUpdatedState(onCompactGridRequested)
    var pinchZoom by remember { mutableFloatStateOf(1f) }
    var pinchFocus by remember { mutableStateOf(Offset.Zero) }
    // Layer only while pinching or springing back; set at start and end only.
    var pinching by remember { mutableStateOf(false) }
    val pinchJob = remember { arrayOfNulls<Job>(1) }
    val pinchScope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val gapPx = with(density) { RATIO_GAP_DP.dp.roundToPx() }
        // Prevent a narrow multi-window/fold layout from reaching
        // RatioLayoutOptions with physically impossible space for a photo.
        if (!canPlanRatioViewport(widthPx, heightPx, 0, gapPx))
            return@BoxWithConstraints
        val options = remember(widthPx, heightPx, density.density) {
            samsungRatioLayoutOptions(widthPx, heightPx, density.density)
        }
        // Spacing in the composition comes from the same physical-pixel plan.
        val gapDp = with(density) { options.gapPx.toDp() }
        // Never compare 150k MediaRecord values on every recomposition; new
        // immutable catalogue generations invalidate layout even if value-equal.
        val catalogEpoch = GridCatalogIdentity(items, catalogueEpoch)
        var plan by remember(catalogEpoch, options) { mutableStateOf<RatioLayout?>(null) }
        // #527: ratios learned from decoded previews, for media whose provider
        // WIDTH/HEIGHT is missing. Scoped to this publication: a new catalogue
        // or grant epoch starts empty, so nothing leaks across revisions.
        val learned = remember(catalogEpoch) { mutableStateMapOf<String, Float>() }
        var learnedGeneration by remember(catalogEpoch) { mutableIntStateOf(0) }
        LaunchedEffect(catalogEpoch) {
            // Batch a screenful of decodes into one re-plan, not one per tile.
            snapshotFlow { learned.size }.collectLatest { size ->
                if (size == 0) return@collectLatest
                delay(RATIO_RELEARN_BATCH_MS)
                learnedGeneration = size
            }
        }
        LaunchedEffect(catalogEpoch, options, learnedGeneration) {
            // Heavy 150k catalogue planning must never block gesture/UI composition.
            val capturedAspect = currentAspect
            val learnedNow = if (learnedGeneration == 0) emptyMap() else learned.toMap()
            val parentContext = coroutineContext
            val computed = withContext(Dispatchers.Default) {
                val projection = ArrayList<RatioMedia>(items.size)
                items.forEachIndexed { index, media ->
                    if ((index and 511) == 0) parentContext.ensureActive()
                    projection.add(RatioMedia(media.uri,
                        capturedAspect(media) ?: learnedNow[media.uri]))
                }
                planRatioRows(projection, options) { parentContext.ensureActive() }
            }
            val previous = plan
            // A re-plan may move rows; keep the photo at the top of the screen
            // where it was instead of jumping (first plan: host restores focus).
            val anchor = if (previous == null) null else {
                val firstRow = previous.rows.getOrNull(state.firstVisibleItemIndex)
                firstRow?.let { it.firstMediaIndex to state.firstVisibleItemScrollOffset }
            }
            plan = computed
            currentLayoutReady(computed)
            if (anchor != null && anchor.first < computed.mediaCount) {
                state.scrollToItem(computed.rowIndexOf(anchor.first), anchor.second)
            }
        }
        val ready = plan
        // #507: arriving from the square grid, start at the photo size the
        // fingers left there; while pinching here, the rows follow the fingers.
        val entryZoom = rememberZoomEntry(true, catalogueEpoch, ready != null)
        if (ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Arranging original proportions…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // Observe only the active rows while pinching. The host checks the
            // request's permission epoch before entering the square timeline.
            fun requestCompact(destination: GalleryZoomStop, focusX: Float, focusY: Float) {
                val visible = state.layoutInfo.visibleItemsInfo.map {
                    RatioVisibleRow(it.index, it.offset)
                }
                val anchor = focalRatioAnchor(
                    ready, visible, focusX, focusY
                ) { index -> items.getOrNull(index)?.uri }
                compactCurrent?.invoke(GalleryZoomRequest(
                    anchor, focusY, catalogueEpoch, destination = destination
                ))
            }
            fun springBack() {
                pinchJob[0]?.cancel()
                pinchJob[0] = pinchScope.launch {
                    animate(pinchZoom, 1f, animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow)) { v, _ -> pinchZoom = v }
                    pinching = false
                }
            }
            // #494: original rows are the most zoomed-in stop of the same ladder;
            // pinching in walks back down it (LARGE, or further on a big pinch).
            val zoomOutModifier = if (onCompactGridRequested == null) Modifier else
                Modifier.galleryPinchLadder(ready, state, newTracker = { slop ->
                    GalleryPinchTracker(GalleryZoomStop.ORIGINAL, slop)
                }, onZoom = { zoom, focus ->
                    pinchJob[0]?.cancel()
                    pinching = true
                    pinchFocus = focus
                    pinchZoom = zoom
                }, onCancelled = { springBack() }) { next, focus ->
                    if (next != GalleryZoomStop.ORIGINAL) {
                        // The square grid starts drawn at the size left here.
                        ZoomEntryHandoff.offer(false, catalogueEpoch,
                            options.targetRowHeightPx * pinchZoom, focus)
                        requestCompact(next, focus.x, focus.y)
                    }
                    // Back to rest if the host keeps original rows on screen.
                    springBack()
                }.semantics {
                    customActions = listOf(CustomAccessibilityAction(SQUARE_GRID_ACTION) {
                        val viewport = state.layoutInfo.viewportSize
                        requestCompact(GalleryZoomStop.LARGE,
                            viewport.width / 2f, viewport.height / 2f)
                        true
                    })
                }
            // Layout and grant changes remount positional remembered cells;
            // never serialize a raw MediaStore URI as a lazy saveable key.
            key(catalogEpoch, options, ready) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().then(zoomOutModifier)
                    .testTag("atlas-ratio-gallery")
                    .zoomEntryLayer(entryZoom) { options.targetRowHeightPx.toFloat() }
                    // Draw-phase reads only; pointer input above stays unscaled.
                    // Clip outside the layer: it bounds the scaled rows, so
                    // they never spill over the header above.
                    .then(if (!pinching) Modifier else Modifier.clipToBounds().graphicsLayer {
                        scaleX = pinchZoom
                        scaleY = pinchZoom
                        if (size.width > 0f && size.height > 0f) {
                            transformOrigin = TransformOrigin(
                                (pinchFocus.x / size.width).coerceIn(0f, 1f),
                                (pinchFocus.y / size.height).coerceIn(0f, 1f))
                        }
                    }),
                state = state,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = LocalAtlasBottomChrome.current),
                verticalArrangement = Arrangement.spacedBy(gapDp)
            ) {
                itemsIndexed(ready.rows, key = { index, _ -> gridLazySaveablePositionKey(index) }) { _, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gapDp)
                    ) {
                        for (inRow in 0 until row.mediaCount) {
                            val media = items[row.firstMediaIndex + inRow]
                            val cellWidth = with(density) { row.cellWidthsPx[inRow].toDp() }
                            val cellHeight = with(density) { row.heightPx.toDp() }
                            Box(
                                // #504: square-cornered, no placeholder box; the
                                // photo fills its cell edge to edge (Samsung look).
                                Modifier.width(cellWidth).height(cellHeight)
                                    .clipToBounds()
                                    .clickable(onClickLabel = "Open ${media.name}") { currentOpen(media) }
                                    .testTag("atlas-ratio-media-${media.id}-${if (media.isVideo) "video" else "photo"}")
                            ) {
                                // Physical tile bounds, rather than a fixed 900 px decode,
                                // determine preview size. MediaPreview still owns authorized
                                // loading and cache revision/permission epoch.
                                val requestPixels = gridPreviewRequestPixels(
                                    row.cellWidthsPx[inRow], row.heightPx
                                )
                                // Only cells planned without a provider ratio ask
                                // for the decoded size; the rest stay callback-free.
                                val needsRatio = !learned.containsKey(media.uri) &&
                                    currentAspect(media) == null
                                MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Crop,
                                    requestPixels,
                                    onImageSize = if (!needsRatio) null else { w, h ->
                                        learnedRatioOrNull(w, h)?.let { learned[media.uri] = it }
                                    }, gridTile = true)
                                if (media.isVideo) Text("▶", Modifier.align(Alignment.BottomEnd).padding(6.dp))
                            }
                        }
                    }
                }
            }
            } // Scoped to this authorized ratio layout.
        }
    }
}

/** Debounce for re-planning after decoded previews report missing ratios. */
internal const val RATIO_RELEARN_BATCH_MS = 150L

/**
 * A decoded preview's size is already EXIF-oriented by the image loader, so
 * no MediaStore ORIENTATION is applied here. Nonsense sizes learn nothing.
 */
internal fun learnedRatioOrNull(width: Int, height: Int): Float? =
    if (width <= 0 || height <= 0) null
    else (width.toFloat() / height.toFloat()).takeIf { it.isFinite() }
