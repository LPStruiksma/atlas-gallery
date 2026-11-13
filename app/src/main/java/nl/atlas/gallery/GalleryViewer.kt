package nl.atlas.gallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.MediaStore
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.request.CachePolicy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.max

/**
 * Filmstrip gestures are the primary navigation mechanism here.
 * LazyRow renders only nearby thumbnails. While the user drags it, the pager
 * jumps (without animation) to the thumbnail nearest the center highlight.
 * A main-image swipe likewise re-centers the thumbnail strip.
 * No full-resolution decoding is requested while actively scrubbing.
 */
/**
 * The viewer owns navigation state only for ONE exact ordered media snapshot.
 * Reusing an existing PagerState for a new (possibly smaller or reordered)
 * list can retain an obsolete index and even index past the new list's end.
 * Re-key the entire viewer subtree on the immutable album-list reference,
 * not on expensive structural equality or a filename which may move.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
fun GalleryViewer(
    items: List<MediaRecord>, startUri: String,
    timingProbe: ViewerTimingProbe? = null,
    onEdit: ((MediaRecord) -> Boolean)? = null,
    // Optional immutable catalogue-scoped return anchor. Keep onBack LAST
    // so all existing GalleryViewer(items, uri) { ... } callers retain meaning.
    onExitSettledUri: ((String) -> Unit)? = null,
    // VS-F2b (#468): search-by-face hooks. [faceSearch] answers (off main)
    // whether a photo has indexed faces; only then "Find this person" shows.
    faceSearch: ViewerFaceSearch? = null,
    // Matched face boxes per URI, drawn over the photo (search results only).
    faceHighlights: Map<String, List<IndexedFaceBox>> = emptyMap(),
    // VS-S2b (#468): "Find similar" from any photo or video frame; null hides it.
    findSimilar: ViewerFindSimilar? = null,
    // Where each video starts playing (Find similar opens a hit at its moment).
    videoStartPositions: Map<String, Long> = emptyMap(),
    // #509: called after Android's Trash dialog confirmed this item; the host
    // drops it from the list and moves to a neighbour. Null hides Delete.
    onTrashed: ((MediaRecord) -> Unit)? = null,
    // #553: the People row in swipe-up Details; null hides it.
    photoPeople: ViewerPhotoPeople? = null,
    onBack: () -> Unit
) {
    // A new immutable album OR an explicit different requested entry URI
    // must start a fresh pager. Routine thumbnail navigation changes neither.
    // Favourite results outlive a remount (a Trash rebuilds the list), keyed by URI,
    // until the next library refresh brings fresh isFavorite values.
    val favouriteOverrides = remember { mutableStateMapOf<String, Boolean>() }
    key(CatalogSourceKey(items), startUri) {
        GalleryViewerSnapshot(items, startUri, timingProbe, onEdit, onExitSettledUri,
            faceSearch, faceHighlights, findSimilar, videoStartPositions, onTrashed,
            favouriteOverrides, photoPeople, onBack)
    }
}

@androidx.compose.runtime.NonSkippableComposable
@Composable
private fun GalleryViewerSnapshot(
    items: List<MediaRecord>, startUri: String,
    timingProbe: ViewerTimingProbe?,
    onEdit: ((MediaRecord) -> Boolean)?,
    onExitSettledUri: ((String) -> Unit)?,
    faceSearch: ViewerFaceSearch?,
    faceHighlights: Map<String, List<IndexedFaceBox>>,
    findSimilar: ViewerFindSimilar?,
    videoStartPositions: Map<String, Long>,
    onTrashed: ((MediaRecord) -> Unit)?,
    favouriteOverrides: MutableMap<String, Boolean>,
    photoPeople: ViewerPhotoPeople?,
    onBack: () -> Unit
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Entry URI lookup is O(150k) on late/missing items. Do it exactly
    // once per catalogue reference and requested URI, never on recomposition.
    // The enclosing viewer is already rekeyed by these same inputs.
    val entry = remember(CatalogSourceKey(items), startUri) {
        ViewerInitialPageSnapshot(items, startUri)
    }
    val start = entry.initialPage
    val pagerState = rememberPagerState(initialPage = start, pageCount = { items.size })
    val latestExitCallback by rememberUpdatedState(onExitSettledUri)
    val latestBackCallback by rememberUpdatedState(onBack)
    val latestFaceSearch by rememberUpdatedState(faceSearch)
    // Catalog generations compare by reference: rememberUpdatedState uses
    // structural equality and would traverse (or throw on) a large list.
    val latestItemsState = remember { mutableStateOf(items, referentialEqualityPolicy()) }
    SideEffect { latestItemsState.value = items }
    val latestPagerState by rememberUpdatedState(pagerState)
    // Only the last settled photo is a safe return anchor. Stale intermediate
    // filmstrip/pager frames must not yank the underlying grid to a wrong URI.
    fun returnToGallery() {
        val settled = pagerState.settledPage.coerceIn(items.indices)
        latestExitCallback?.invoke(items[settled].uri)
        latestBackCallback()
    }
    // Toolbar and Android Back must execute the SAME callback, even when
    // immersive chrome is hidden. No upstream Activity code modified.
    BackHandler(onBack = ::returnToGallery)
    val stripState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var editorReturn by remember { mutableStateOf(EditorReturnState()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                editorReturn = editorReturn.resumed()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // #509 feel (Lukas 2026-11-10 00:39Z: Samsung's scrub "has weight"):
    // Samsung flings its strip as a plain RecyclerView (velocity x1.0)
    // with a linear snap at the end. Same here: the full
    // platform spline decay carries the momentum, then a soft, critically
    // damped settle onto the centre tile instead of a quick snap.
    val stripDecay = rememberSplineBasedDecay<Float>()
    val snapFling = remember(stripState, stripDecay) {
        snapFlingBehavior(
            SnapLayoutInfoProvider(stripState, SnapPosition.Center),
            stripDecay,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        )
    }
    // #509: Samsung-style thin strip. Uniform tile stride keeps the
    // pager->strip fraction maths exact; the centre tile is only drawn larger.
    val stripStridePx = with(LocalDensity.current) { (STRIP_TILE_WIDTH + STRIP_TILE_GAP).roundToPx() }
    val videoPositions = remember(CatalogSourceKey(items)) { ViewerPlaybackPositions() }
    // The playing video's live position, read on main when "Find similar" is tapped.
    val livePlayback = remember(CatalogSourceKey(items)) { HashMap<String, () -> Long>() }
    val latestFindSimilar by rememberUpdatedState(findSimilar)
    // Count concurrent programmatic LazyRow scrolls instead of letting a
    // canceled effect's finally clear a still-active sibling's Boolean.
    var controlledScrollDepth by remember { mutableIntStateOf(0) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    val latestZoom by rememberUpdatedState(currentZoom)
    // One cancelable explicit selection channel for thumbnail taps and the
    // end of a filmstrip scrub. A new immutable album snapshot resets it.
    var selectionRequest by remember(CatalogSourceKey(items)) {
        mutableStateOf<ViewerJumpIntent?>(null)
    }
    // Track only the last *settled* preview focus, not every intermediate
    // scrub position. Directional prefetch remains useful after a long jump.
    var lastPrefetchFocus by remember(CatalogSourceKey(items)) { mutableIntStateOf(start) }
    // A single tap on a photo hides all chrome (and the system bars), without
    // changing the selected item, pager, zoom or the caller's Back stack.
    var chromeVisible by remember { mutableStateOf(true) }
    // Details is opened deliberately; no metadata provider work in the pager.
    var detailsMedia by remember { mutableStateOf<MediaRecord?>(null) }
    // Pending consent requests survive activity recreation while Android's dialog is open.
    var pendingFavouriteUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFavouriteValue by rememberSaveable { mutableStateOf(false) }
    val favouriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingFavouriteUri
        pendingFavouriteUri = null
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null)
            favouriteOverrides[uri] = pendingFavouriteValue
    }
    var pendingTrashUri by rememberSaveable { mutableStateOf<String?>(null) }
    val latestTrashed by rememberUpdatedState(onTrashed)
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingTrashUri
        pendingTrashUri = null
        val gone = uri?.let { u -> items.firstOrNull { it.uri == u } }
        if (result.resultCode == android.app.Activity.RESULT_OK && gone != null)
            latestTrashed?.invoke(gone)
    }

    // #509 scrub sync. While the user drags/flings the filmstrip the pager is
    // NOT moved page by page (that lagged several photos behind on a fast
    // scrub and then jumped on release). Instead a lightweight overlay shows
    // the item under the strip's centre on every frame, from the strip's own
    // already-decoded thumbnail, sharpening once the finger pauses. On release
    // the pager lands on that same item once, and the overlay is held until
    // it has, so neither the strip nor the photo jumps.
    val liveScrubCentre by remember(CatalogSourceKey(items)) {
        derivedStateOf {
            if (!stripState.isScrollInProgress || isViewerScrollOwned(controlledScrollDepth)) null
            else viewerStripCentreIndex(stripState)?.takeIf { it in items.indices }
        }
    }
    var heldScrubIndex by remember(CatalogSourceKey(items)) { mutableStateOf<Int?>(null) }
    LaunchedEffect(CatalogSourceKey(items)) {
        snapshotFlow { liveScrubCentre }.collect { if (it != null) heldScrubIndex = it }
    }
    // Read ONLY inside small scopes (overlay, tiles, semantics). Reading them
    // here would recompose the whole viewer, pager included, on every scrub
    // frame, which is what made the strip feel weightless and jittery.
    val scrubOverlayState = remember(CatalogSourceKey(items)) {
        derivedStateOf {
            viewerScrubOverlayIndex(liveScrubCentre, heldScrubIndex,
                pagerState.currentPage, pagerState.isScrollInProgress)
        }
    }
    LaunchedEffect(CatalogSourceKey(items)) {
        snapshotFlow { Pair(scrubOverlayState.value, liveScrubCentre == null) }
            .collectLatest { (overlay, released) ->
                if (overlay == null && heldScrubIndex != null) heldScrubIndex = null
                // Safety net: never leave a stale overlay over a page that can't land.
                if (overlay != null && released) {
                    delay(1_000)
                    heldScrubIndex = null
                }
            }
    }
    // The item the user is looking at: the scrub target while scrubbing.
    val displayedIndexState = remember(CatalogSourceKey(items)) {
        derivedStateOf { (scrubOverlayState.value ?: pagerState.currentPage).coerceIn(items.indices) }
    }
    // A light tick each time a new photo reaches the centre during a scrub,
    // like Samsung's filmstrip.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(CatalogSourceKey(items)) {
        var previousCentre: Int? = null
        snapshotFlow { liveScrubCentre }.collect { centre ->
            if (centre != null && viewerShouldTickScrub(previousCentre, centre)) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            previousCentre = centre
        }
    }

    // One cancelable explicit navigation consumer. Null signals are retained
    // to cancel obsolete selection if a newer filmstrip swipe starts, rather
    // than leaving an old tap job running after that gesture.
    LaunchedEffect(pagerState, stripState, CatalogSourceKey(items)) {
        snapshotFlow { selectionRequest }.collectLatest { request ->
            if (request == null || request.index !in items.indices)
                return@collectLatest
            controlledScrollDepth = acquireViewerScrollLease(controlledScrollDepth)
            try {
                if (pagerState.currentPage != request.index) {
                    timingProbe?.selectionRequested(request.index)
                    pagerState.scrollToPage(request.index)
                }
                stripState.scrollToItem(request.index)
            } finally {
                controlledScrollDepth = releaseViewerScrollLease(controlledScrollDepth)
                // A finished tap must not suppress subsequent pager-driven strip motion.
                // Cancellation of an older request cannot clear a newer one.
                if (selectionRequest == request) selectionRequest = null
            }
        }
    }
    LaunchedEffect(stripState, CatalogSourceKey(items)) {
        snapshotFlow {
            stripState.isScrollInProgress && !isViewerScrollOwned(controlledScrollDepth)
        }.distinctUntilChanged().collect { userScrubbing ->
            if (userScrubbing) selectionRequest = null
        }
    }

    // Continuous pager -> strip coupling. The strip tracks fractional pager
    // motion, but NEVER forcibly re-centres an idle strip: doing so would
    // overwrite a user's newly finished filmstrip fling before its final
    // centered-thumbnail commit. Only a real pager swipe may settle here.
    LaunchedEffect(pagerState, stripState, CatalogSourceKey(items), stripStridePx) {
        var pagerWasMoving = false
        snapshotFlow {
            val stripGesture = stripState.isScrollInProgress &&
                !isViewerScrollOwned(controlledScrollDepth)
            when {
                stripGesture || selectionRequest != null || heldScrubIndex != null ->
                    Pair(false, null)
                pagerState.isScrollInProgress ->
                    Pair(true, viewerStripPosition(
                        pagerState.currentPage, pagerState.currentPageOffsetFraction,
                        items.size, stripStridePx))
                else ->
                    Pair(false, viewerStripPosition(
                        pagerState.currentPage, 0f, items.size, stripStridePx))
            }
        }.distinctUntilChanged().collectLatest { (moving, target) ->
            if (target == null) {
                pagerWasMoving = false
                return@collectLatest
            }
            if (!moving && !pagerWasMoving) return@collectLatest
            pagerWasMoving = moving
            // Measure-safe positional request (see #446): never suspend
            // scrollToItem() from a collector resumed inside pager measure.
            stripState.requestScrollToItem(target.index, target.scrollOffsetPx)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        timingProbe?.pagerSelected(pagerState.currentPage)
        currentZoom = 1f
        // Do NOT recenter the strip here: a finished thumbnail fling commits
        // its own centred item; a pager swipe is reconciled above.
    }
    // Once a strip fling ends, commit the photo actually under the centre,
    // exactly the one the scrub overlay was showing. collectLatest cancels
    // the final alignment if another drag begins immediately.
    LaunchedEffect(stripState, CatalogSourceKey(items)) {
        var wasUserScrubbing = false
        snapshotFlow {
            stripState.isScrollInProgress && !isViewerScrollOwned(controlledScrollDepth)
        }.distinctUntilChanged().collectLatest { scrolling ->
            if (scrolling) {
                wasUserScrubbing = true
            } else if (mayCommitFinishedStripGesture(
                    wasUserScrubbing, false, isViewerScrollOwned(controlledScrollDepth),
                    selectionRequest
                )
            ) {
                wasUserScrubbing = false
                val index = viewerStripCentreIndex(stripState)
                if (index != null && index in items.indices) {
                    heldScrubIndex = index
                    selectionRequest = nextViewerJumpIntent(
                        selectionRequest, index, items.size)
                }
            } else {
                wasUserScrubbing = false
            }
        }
    }

    // Prewarm a small number of screen-sized neighbors only after navigation
    // settles. The effect is cancelled as soon as scrubbing resumes, avoiding
    // a backlog of expensive image decodes and unbounded memory growth.
    LaunchedEffect(CatalogSourceKey(items), editorReturn, pagerState.currentPage, stripState.isScrollInProgress, pagerState.isScrollInProgress) {
        if (!stripState.isScrollInProgress && !pagerState.isScrollInProgress) {
            delay(120)
            val loader = SingletonImageLoader.get(context)
            val focus = pagerState.currentPage
            val neighbors = nearbyPreviewIndicesForMotion(
                focus, lastPrefetchFocus, items.size
            )
            lastPrefetchFocus = focus
            // One stale/corrupt neighboring URI must not prevent the other
            // bounded posters from warming; navigation cancels the coroutine.
            prewarmDeclutterPreviewIndices(neighbors) { index ->
                val media = items[index]
                if (!media.isVideo) {
                    val refreshThis = editorReturn.mustReload(media.uri)
                    loader.execute(ImageRequest.Builder(context)
                        .data(media.uri)
                        .apply {
                            if (refreshThis) {
                                memoryCacheKey(editedPhotoMemoryKey(
                                    media.uri, editorReturn.revision, 800))
                                diskCachePolicy(CachePolicy.DISABLED)
                            }
                        }
                        .size(800, 800)
                        .crossfade(false)
                        .build())
                }
            }
        }
    }

    // A LazyRow removed for immersive viewing must re-center when restored.
    // Its remembered state survives temporary removal from composition.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible && !stripState.isScrollInProgress) {
            controlledScrollDepth = acquireViewerScrollLease(controlledScrollDepth)
            try { stripState.scrollToItem(pagerState.currentPage) }
            finally { controlledScrollDepth = releaseViewerScrollLease(controlledScrollDepth) }
        }
    }
    // Samsung hides the status and navigation bars together with its chrome.
    ViewerSystemBars(visible = chromeVisible)

    // Live drag transforms are animated back on cancellation and read only by
    // their owning layers, not by the full viewer on each pointer frame.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val showDragPreview by remember { derivedStateOf { dragOffset.floatValue < 0f } }
    val dragAnimation = remember { Animatable(0f) }
    var dragAnimationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dragScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { VERTICAL_SWIPE_DP.dp.toPx() }
    val flingThresholdPx = with(density) { VERTICAL_FLING_DP.dp.toPx() }
    val touchSlopPx = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    fun cancelViewerDrag() {
        dragAnimationJob?.cancel()
        dragAnimationJob = dragScope.launch {
            dragAnimation.snapTo(dragOffset.floatValue)
            dragAnimation.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy)) {
                dragOffset.floatValue = value
            }
        }
    }
    fun finishViewerDrag(velocityY: Float, gateOpen: Boolean) {
        when (viewerVerticalRelease(dragOffset.floatValue, velocityY, dragThresholdPx,
                flingThresholdPx, latestZoom, gateOpen)) {
            ViewerVerticalSwipe.CLOSE -> {
                returnToGallery()
            }
            ViewerVerticalSwipe.DETAILS -> {
                val index = viewerVisibleActionIndex(scrubOverlayState.value,
                    latestPagerState.currentPage, latestItemsState.value.size)
                detailsMedia = latestItemsState.value[index]
                dragOffset.floatValue = 0f
            }
            ViewerVerticalSwipe.NONE -> cancelViewerDrag()
        }
    }

    // #509 layout: the photo is full-bleed on black and the chrome floats
    // over it (back pill top-left, action pill top-right, thin filmstrip and
    // an action pill at the bottom). No title, counter text, labels or rails.
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("atlas-viewer")) {
        // The photo follows a down-drag and shrinks against a dimming black
        // backdrop. For an up-drag the image scales above the rising details cue.
        // Gesture input precedes the moving layer. Coordinates must remain
        // in a stationary viewport while the photo follows the finger.
        Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // Read the clock from the first down, not from slop crossing.
                    // Main-pass ownership keeps the viewer gesture ahead of pager
                    // navigation but leaves horizontal scrolling and zoom alone.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false,
                            pass = PointerEventPass.Main)
                        var travelX = 0f
                        var travelY = 0f
                        var previous = down.position
                        var reversedUp = false
                        var downExitEligible = false
                        var lastElapsedMs = 0L
                        var upEligible = false
                        var tracking = false
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        dragAnimationJob?.cancel()
                        dragOffset.floatValue = 0f

                        var released = false
                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (event.changes.size != 1 || latestZoom > 1.01f) {
                                cancelViewerDrag()
                                break
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                released = true
                                break
                            }
                            if (!change.pressed) {
                                cancelViewerDrag()
                                break
                            }
                            val delta = change.position - previous
                            previous = change.position
                            travelX += delta.x
                            travelY += delta.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // V22: only an *early* upward move cancels the exit;
                            // once the photo follows the finger, release decides.
                            if (!tracking) reversedUp = viewerEarlyUpwardReversal(
                                reversedUp, delta.y, touchSlopPx)
                            val elapsed = change.uptimeMillis - down.uptimeMillis
                            lastElapsedMs = elapsed
                            // The V22 ratio applies to the *whole* movement, not an
                            // early vertical segment that later turns diagonal.
                            downExitEligible = viewerMayStartDownExit(
                                travelY, travelX, elapsed, reversedUp, touchSlopPx)
                            upEligible = travelY < -touchSlopPx &&
                                abs(travelY) > abs(travelX) * 1.4f
                            val verticallyDominant = abs(travelY) > abs(travelX) * 1.4f
                            if (verticallyDominant && abs(travelY) > touchSlopPx) {
                                tracking = true
                            }
                            if (tracking) {
                                dragOffset.floatValue = travelY
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (released && tracking) {
                            finishViewerDrag(tracker.calculateVelocity().y,
                                if (dragOffset.floatValue > 0f)
                                    downExitEligible && viewerMayStartDownExit(
                                        travelY, travelX, lastElapsedMs,
                                        reversedUp, touchSlopPx) else upEligible)
                        } else if (released) {
                            cancelViewerDrag()
                        }
                    }
                }
            .graphicsLayer {
            val offset = dragOffset.floatValue
            val fraction = (abs(offset) / (size.height * .65f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            // Up: the photo lifts with the rising panel (as in Samsung Gallery).
            translationY = if (offset > 0f) offset else offset * .35f
            val scale = if (offset > 0f) 1f - .22f * fraction else 1f + .06f * fraction
            scaleX = scale; scaleY = scale
        }.testTag("atlas-viewer-drag-photo")) {
        HorizontalPager(
            state = pagerState,
            // Item keys can enter Compose saveable state. Never use
            // a private content URI as that key; the enclosing viewer is
            // already scoped by exact catalogue reference and entry (Lane V de53352).
            key = { index -> index },
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            userScrollEnabled = currentZoom <= 1.01f && !stripState.isScrollInProgress,
            modifier = Modifier.fillMaxSize().testTag("atlas-main-pager")
                // #509 (Lukas 2026-11-10 00:02Z): like Samsung, swipe up on an
                // unzoomed photo opens its details; swipe down closes the
                // viewer (parity research VIEW:V22, V27). A zoomed photo's own pan
                // consumes these moves first, so they never fight panning.

        ) { page ->
            val media = items[page]
            val focused = page == pagerState.currentPage
            val rapidlyChanging = stripState.isScrollInProgress || pagerState.isScrollInProgress
            if (media.isVideo) {
                ViewerVideoPage(
                    // Reading Details is not playback. Unmount Media3 to
                    // release audio focus and save the ephemeral resume point;
                    // returning to the video leaves playback paused.
                    media, focused && detailsMedia == null,
                    rapidlyChanging, chromeVisible,
                    resumePositionMs = videoPositions.find(media.uri)
                        ?: videoStartPositions[media.uri] ?: 0L,
                    onSavePosition = { videoPositions.save(media.uri, it) },
                    onLivePosition = { reader ->
                        if (reader == null) livePlayback.remove(media.uri)
                        else livePlayback[media.uri] = reader
                    },
                    onToggleChrome = { chromeVisible = !chromeVisible }
                )
            } else {
                PhotoPage(media, page, items.size, focused, focused && !rapidlyChanging,
                    chromeVisible = chromeVisible,
                    observePreviewLoads = timingProbe != null,
                    faceBoxes = faceHighlights[media.uri].orEmpty(),
                    forceFresh = editorReturn.mustReload(media.uri),
                    refreshRevision = editorReturn.revision,
                    onZoomChanged = { level ->
                        if (focused) currentZoom = level
                    },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onEdgePageRequested = { target ->
                        if (focused && target in items.indices) {
                            selectionRequest = nextViewerJumpIntent(
                                selectionRequest, target, items.size)
                        }
                    },
                    onPreviewReady = if (focused && timingProbe != null) {
                        { timingProbe.firstPreviewReady(page) }
                    } else null)
            }
        }
        }
        ViewerScrubLayer(items, scrubOverlayState, { liveScrubCentre == null }, editorReturn)
        // Screen readers and tests still get "N of M"; nothing is drawn.
        if (chromeVisible) Box(Modifier.size(0.dp).testTag("atlas-photo-counter")
            .semantics {
                text = AnnotatedString("${displayedIndexState.value + 1} of ${items.size}")
            })
        // Pills follow the settled photo, so a scrub doesn't re-run the face
        // lookup or rebuild the pills on every frame. Details resolves the
        // photo the held scrub overlay shows at tap time instead.
        val current = items[pagerState.currentPage.coerceIn(items.indices)]
        AnimatedVisibility(chromeVisible, Modifier.align(Alignment.TopCenter),
            enter = CHROME_ENTER, exit = CHROME_EXIT) { Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewerPill {
                IconButton(onClick = ::returnToGallery, modifier = Modifier.testTag("atlas-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back to gallery",
                        tint = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            val showFindPerson = faceSearch != null && !current.isVideo && run {
                // Off main, once per shown photo (not per recomposition): only a
                // photo with indexed faces offers the action. The host
                // re-checks its grant when the picker opens.
                val hasFaces by produceState(false, current.uri) {
                    value = try { latestFaceSearch?.hasFaces?.invoke(current) == true }
                        catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped }
                        catch (_: Exception) { false }
                }
                hasFaces
            }
            ViewerPill {
                if (showFindPerson) IconButton(
                    onClick = { latestFaceSearch?.onFindPerson?.invoke(current) },
                    modifier = Modifier.testTag("atlas-viewer-find-person")
                ) {
                    Icon(Icons.Outlined.Face, "Find this person", tint = Color.White)
                }
                if (findSimilar != null) IconButton(
                    onClick = {
                        // A video searches from the frame on screen: the live
                        // player position, else where it was left, else its start.
                        val frameMs = if (!current.isVideo) null else
                            livePlayback[current.uri]?.invoke()
                                ?: videoPositions.find(current.uri)
                                ?: videoStartPositions[current.uri] ?: 0L
                        latestFindSimilar?.onFindSimilar?.invoke(current, frameMs?.coerceAtLeast(0L))
                    },
                    modifier = Modifier.testTag("atlas-viewer-find-similar")
                ) {
                    Icon(Icons.Outlined.ImageSearch, "Find similar", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        detailsMedia = items[viewerVisibleActionIndex(scrubOverlayState.value,
                            pagerState.currentPage, items.size)]
                    },
                    modifier = Modifier.testTag("atlas-viewer-details-open")
                ) {
                    Icon(Icons.Outlined.Info, "Photo or video details", tint = Color.White)
                }
            }
        } }
        AnimatedVisibility(chromeVisible, Modifier.align(Alignment.BottomCenter),
            enter = CHROME_ENTER, exit = CHROME_EXIT) { Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
                val contentPad = (maxWidth - STRIP_TILE_WIDTH) / 2
                LazyRow(
                    state = stripState,
                    flingBehavior = snapFling,
                    contentPadding = PaddingValues(horizontal = contentPad),
                    horizontalArrangement = Arrangement.spacedBy(STRIP_TILE_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().testTag("atlas-filmstrip")
                ) {
                    // Positional keys avoid writing private MediaStore URIs to
                    // LazyRow saveable-state keys. A different catalogue
                    // remounts the parent viewer and cannot reuse old slots.
                    itemsIndexed(items, key = { index, _ -> index }) { index, media ->
                        val selected by remember(index, displayedIndexState) {
                            derivedStateOf { displayedIndexState.value == index }
                        }
                        Box(Modifier.size(STRIP_TILE_WIDTH, STRIP_TILE_HEIGHT)
                            .testTag("atlas-thumbnail-$index")
                            .zIndex(if (selected) 1f else 0f)
                            .graphicsLayer {
                                val grow = if (selected) STRIP_CENTRE_SCALE else 1f
                                scaleX = grow; scaleY = grow
                            }
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF2A2A2A))
                            .semantics(mergeDescendants = true) {
                                this.selected = selected
                                contentDescription = "${if (media.isVideo) "Video" else "Photo"} " +
                                    "${index + 1} of ${items.size}: ${media.name}"
                                stateDescription = if (selected) "Currently displayed" else "Not selected"
                            }
                            .clickable(role = Role.Button) {
                                selectionRequest = nextViewerJumpIntent(
                                    selectionRequest, index, items.size)
                            }) {
                            MediaPreview(
                                // The parent already announces Photo/Video N of M
                                // and filename. Suppress the duplicated Coil
                                // bitmap announcement for one clear focus target.
                                media, Modifier.fillMaxSize().clearAndSetSemantics {},
                                ContentScale.Crop, STRIP_THUMB_PIXELS,
                                forceFresh = editorReturn.mustReload(media.uri),
                                refreshRevision = editorReturn.revision
                            )
                            if (media.isVideo) Icon(Icons.Filled.PlayArrow, null,
                                Modifier.align(Alignment.Center).size(14.dp), tint = Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val showEdit = onEdit != null && PhotoEditorHandoff.eligible(current)
            val showShare = ViewerShareHandoff.eligible(current)
            val showFavourite = viewerCanChangeInMediaStore(current.uri, Build.VERSION.SDK_INT)
            val showTrash = showFavourite && onTrashed != null
            // Samsung order: favourite · edit · share · delete.
            if (showFavourite || showEdit || showShare || showTrash)
                ViewerPill(Modifier.padding(bottom = 10.dp)) {
                if (showFavourite) {
                    val favourite = favouriteOverrides[current.uri] ?: current.isFavorite
                    IconButton(
                        onClick = {
                            // Android's own consent dialog; nothing changes on cancel.
                            try {
                                val request = MediaStore.createFavoriteRequest(
                                    context.contentResolver, listOf(Uri.parse(current.uri)), !favourite)
                                pendingFavouriteUri = current.uri
                                pendingFavouriteValue = !favourite
                                favouriteLauncher.launch(
                                    IntentSenderRequest.Builder(request.intentSender).build())
                            } catch (_: Exception) {
                                pendingFavouriteUri = null
                                Toast.makeText(context, "Couldn't change favourite",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 10.dp).testTag("atlas-viewer-favourite")
                            .semantics { selected = favourite }
                    ) {
                        Icon(if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            if (favourite) "Remove from favourites" else "Add to favourites",
                            tint = if (favourite) Color(0xFFFF4D5E) else Color.White)
                    }
                }
                if (showEdit) IconButton(
                    onClick = {
                        val launched = onEdit!!(current)
                        editorReturn = editorReturn.launched(current.uri, launched)
                    },
                    modifier = Modifier.padding(horizontal = 10.dp).testTag("atlas-edit-photo")
                ) {
                    Icon(Icons.Outlined.Edit, "Edit in Samsung Gallery", tint = Color.White)
                }
                // A selected MediaStore photo or video can be shared without copying
                // files or granting album-wide access. The OS owns target selection.
                if (showShare) IconButton(
                    onClick = {
                        if (ViewerShareHandoff.launch(context, current) !=
                            ViewerShareHandoff.Result.STARTED
                        ) {
                            Toast.makeText(context, "Sharing unavailable for this item",
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 10.dp).testTag("atlas-share-media")
                ) {
                    Icon(Icons.Outlined.Share, "Share selected photo or video",
                        tint = Color.White)
                }
                // Trash is always Android's own consent dialog (createTrashRequest);
                // ATLAS never deletes directly. Only RESULT_OK changes anything.
                if (showTrash) IconButton(
                    onClick = {
                        try {
                            val request = MediaStore.createTrashRequest(
                                context.contentResolver, listOf(Uri.parse(current.uri)), true)
                            pendingTrashUri = current.uri
                            trashLauncher.launch(
                                IntentSenderRequest.Builder(request.intentSender).build())
                        } catch (_: Exception) {
                            pendingTrashUri = null
                            Toast.makeText(context, "Couldn't move this item to Trash",
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 10.dp).testTag("atlas-viewer-trash")
                ) {
                    Icon(Icons.Outlined.Delete, "Move to Trash", tint = Color.White)
                }
            } else Spacer(Modifier.height(10.dp))
        } }
        // Present actual metadata (not an empty cue) during the held up-drag.
        // The settled ModalBottomSheet still mounts only on release, so no
        // MediaStore provider query runs merely because a gesture began.
        // Drawn above the filmstrip and pills and kept above the navigation
        // bar, so the rising panel is visible from the first pixels of a drag.
        if (showDragPreview && detailsMedia == null) Box(Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .fillMaxWidth().height(240.dp)
            .graphicsLayer {
                val offset = dragOffset.floatValue.coerceAtMost(0f)
                translationY = 240.dp.toPx() + offset.coerceAtLeast(-240.dp.toPx())
                alpha = if (offset < 0f) 0.6f + 0.4f *
                    (-offset / 240.dp.toPx()).coerceIn(0f, 1f) else 0f
            }.background(Color(0xFF171A20))
            .testTag("atlas-details-drag-preview")) {
            // Resolved here, not in the viewer scope, so scrub frames don't
            // recompose the whole viewer.
            ViewerDetailsPreview(items[viewerVisibleActionIndex(scrubOverlayState.value,
                pagerState.currentPage, items.size)])
        }
    }
    detailsMedia?.let { selected ->
        ViewerDetailsSheet(selected, onDismiss = { detailsMedia = null },
            photoPeople = photoPeople)
    }
}

// #509 filmstrip geometry (Samsung 15.9 look: small portrait tiles, 2dp gaps,
// the centre tile drawn ~25% larger, no label, marker line or second rail).
private val STRIP_TILE_WIDTH = 30.dp
private val STRIP_TILE_HEIGHT = 40.dp
private val STRIP_TILE_GAP = 2.dp
private val STRIP_HEIGHT = 56.dp
private const val STRIP_CENTRE_SCALE = 1.25f
private const val STRIP_THUMB_PIXELS = 144
// Chrome fades instead of popping (spec §0 Motion).
private val CHROME_ENTER = fadeIn(tween(200))
private val CHROME_EXIT = fadeOut(tween(200))

// Favourite and Trash go through MediaStore consent requests, which exist from
// API 30 (minSdk is 29) and only accept MediaStore item URIs (not documents,
// files or test fixtures). Below API 30 both buttons stay hidden.
internal fun viewerCanChangeInMediaStore(uri: String, sdkInt: Int): Boolean =
    sdkInt >= 30 && uri.startsWith("content://media/")

internal enum class ViewerVerticalSwipe { DETAILS, CLOSE, NONE }

/** Travel needed for a vertical swipe to open details (up) or close (down). */
private const val VERTICAL_SWIPE_DP = 56
private const val VERTICAL_FLING_DP = 1_100

/** V22: a down-exit is delayed, strongly vertical, and cancelled by early up motion. */
internal fun viewerMayStartDownExit(
    dy: Float, dx: Float, elapsedMs: Long, reversedUp: Boolean, slop: Float
): Boolean = dy.isFinite() && dx.isFinite() && dy > slop &&
    elapsedMs >= 25L && !reversedUp && dy > abs(dx) * 1.4f

internal fun viewerEarlyUpwardReversal(cancelled: Boolean, stepY: Float, slop: Float): Boolean =
    cancelled || (stepY.isFinite() && stepY < -slop / 3f)

internal fun viewerVerticalRelease(
    dy: Float, velocityY: Float, distancePx: Float, flingPx: Float,
    zoom: Float, gateOpen: Boolean
): ViewerVerticalSwipe = when {
    !gateOpen || !dy.isFinite() || !velocityY.isFinite() || zoom > 1.01f -> ViewerVerticalSwipe.NONE
    dy >= distancePx || (dy > 0f && velocityY >= flingPx) -> ViewerVerticalSwipe.CLOSE
    dy <= -distancePx || (dy < 0f && velocityY <= -flingPx) -> ViewerVerticalSwipe.DETAILS
    else -> ViewerVerticalSwipe.NONE
}

internal fun viewerShouldTickScrub(previous: Int?, current: Int): Boolean =
    previous != null && current != previous

internal fun viewerVisibleActionIndex(overlay: Int?, page: Int, count: Int): Int =
    (overlay ?: page).coerceIn(0, max(0, count - 1))

/** #509: up past the threshold opens details, down closes; zoomed photos pan instead. */
internal fun viewerVerticalSwipe(travelPx: Float, thresholdPx: Float, zoom: Float): ViewerVerticalSwipe =
    when {
        !travelPx.isFinite() || zoom > 1.01f -> ViewerVerticalSwipe.NONE
        travelPx <= -thresholdPx -> ViewerVerticalSwipe.DETAILS
        travelPx >= thresholdPx -> ViewerVerticalSwipe.CLOSE
        else -> ViewerVerticalSwipe.NONE
    }

/** The filmstrip item whose centre is nearest the strip's centre line. */
internal fun viewerStripCentreIndex(state: LazyListState): Int? {
    val info = state.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
}

/**
 * #509: which item the scrub overlay shows, or null for none. While the strip
 * moves under the user's finger: always its centre item. After release: the
 * held target, until the pager has actually landed on it and stopped.
 */
internal fun viewerScrubOverlayIndex(
    liveCentre: Int?, held: Int?, currentPage: Int, pagerMoving: Boolean
): Int? = when {
    liveCentre != null -> liveCentre
    held != null && (held != currentPage || pagerMoving) -> held
    else -> null
}

/**
 * Only this scope recomposes per scrub frame. Non-skippable so Compose never
 * compares the 150k-item [items] list structurally to decide on skipping.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
private fun ViewerScrubLayer(
    items: List<MediaRecord>, overlay: State<Int?>, released: () -> Boolean,
    editorReturn: EditorReturnState
) {
    val index = overlay.value ?: return
    val media = items.getOrNull(index) ?: return
    ViewerScrubPreview(media, settled = released(),
        forceFresh = editorReturn.mustReload(media.uri),
        refreshRevision = editorReturn.revision)
}

/**
 * Scrub frame: first the strip's own (memory-cached) thumbnail so it is
 * on screen in the same frame, then a viewer-size preview once the finger
 * pauses (the same 800px key the photo page uses, so release can't flash).
 */
@Composable
private fun ViewerScrubPreview(
    media: MediaRecord, settled: Boolean, forceFresh: Boolean, refreshRevision: Long
) {
    var sharp by remember(media.uri) { mutableStateOf(false) }
    LaunchedEffect(media.uri, settled) {
        if (!settled) delay(SCRUB_SHARPEN_DELAY_MS)
        sharp = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("atlas-scrub-preview")
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Fit, STRIP_THUMB_PIXELS,
            forceFresh = forceFresh, refreshRevision = refreshRevision)
        if (sharp) MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Fit, 800,
            forceFresh = forceFresh, refreshRevision = refreshRevision)
        if (media.isVideo) Icon(Icons.Filled.PlayArrow, null, Modifier.size(72.dp),
            tint = Color.White)
    }
}

/** Samsung waits ~50 ms before loading a cell during a quick scroll (parity research S1). */
private const val SCRUB_SHARPEN_DELAY_MS = 60L

@Composable
private fun ViewerPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(percent = 50)).background(Color(0xB3202124))
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        content = content)
}

/** Hide status + navigation bars with the chrome; always restore on exit. */
@Composable
private fun ViewerSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window ?: return
    DisposableEffect(window, view, visible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

internal fun shouldStartViewerVideoPlayer(
    focused: Boolean, rapidlyChanging: Boolean, settled: Boolean
): Boolean = focused && !rapidlyChanging && settled

/**
 * Video poster while scrubbing, player only after the selected frame is
 * stationary for 180ms. This prevents bursty Media3 allocations during
 * repeated filmstrip taps that may not register as an active scroll.
 */
@Composable
private fun ViewerVideoPage(
    media: MediaRecord, focused: Boolean, rapidlyChanging: Boolean,
    chromeVisible: Boolean, resumePositionMs: Long,
    onSavePosition: (Long) -> Unit, onLivePosition: ((() -> Long)?) -> Unit,
    onToggleChrome: () -> Unit
) {
    var videoSettled by remember(media.uri, focused, rapidlyChanging) {
        mutableStateOf(false)
    }
    LaunchedEffect(media.uri, focused, rapidlyChanging) {
        if (focused && !rapidlyChanging) {
            delay(180)
            videoSettled = true
        }
    }
    // TalkBack gets a surface action to reveal gallery chrome; the player
    // itself has no native controller children to intercept playback focus.
    Box(Modifier.fillMaxSize().testTag("atlas-video-surface")
        .semantics {
            if (focused) {
                contentDescription = "Video viewer: ${media.name}"
                stateDescription = if (chromeVisible)
                    "Gallery controls visible" else "Gallery controls hidden"
                onClick(
                    label = if (chromeVisible) "Hide gallery controls"
                        else "Show gallery controls"
                ) {
                    onToggleChrome()
                    true
                }
            }
        }) {
        if (shouldStartViewerVideoPlayer(focused, rapidlyChanging, videoSettled)) {
            Box(Modifier.fillMaxSize().testTag("atlas-viewer-video-player")) {
                VideoPage(media.uri, resumePositionMs, onSavePosition, onLivePosition,
                    chromeVisible = chromeVisible, onToggleChrome = onToggleChrome)
            }
        } else {
            Box(Modifier.fillMaxSize().testTag("atlas-viewer-video-poster"),
                contentAlignment = Alignment.Center) {
                MediaPreview(media, Modifier.fillMaxSize().clearAndSetSemantics {},
                    ContentScale.Fit, 700)
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(72.dp),
                    tint = Color.White)
            }
        }
    }
}

internal fun shouldRenderLargePhoto(
    focused: Boolean, fullResolution: Boolean, settled: Boolean
): Boolean = focused && fullResolution && settled

@Composable
private fun PhotoPage(
    media: MediaRecord, pageIndex: Int, itemCount: Int,
    focused: Boolean, fullResolution: Boolean,
    chromeVisible: Boolean,
    observePreviewLoads: Boolean,
    faceBoxes: List<IndexedFaceBox>,
    forceFresh: Boolean, refreshRevision: Long,
    onZoomChanged: (Float) -> Unit, onToggleChrome: () -> Unit,
    onEdgePageRequested: (Int) -> Unit,
    onPreviewReady: (() -> Unit)?
) {
    val context = LocalContext.current
    val latestEdgeCallback by rememberUpdatedState(onEdgePageRequested)
    // Gesture loops below are deliberately NOT restarted by recomposition,
    // so they must call the latest callbacks rather than captured ones.
    val latestZoomChanged by rememberUpdatedState(onZoomChanged)
    val latestToggleChrome by rememberUpdatedState(onToggleChrome)
    // Pager retains neighboring pages to keep swipes fluid. They must not
    // retain a previous zoom/pan when they stop being the focused photo:
    // otherwise shared pager gestures are enabled while an offscreen page
    // still displays a stale magnified transform on returning.
    val imageRevision = if (forceFresh) refreshRevision else 0L
    var zoomPose by remember(media.uri, focused, imageRevision) {
        mutableStateOf(ViewerZoomPosition())
    }
    var viewport by remember(media.uri, focused, imageRevision) {
        mutableStateOf(IntSize.Zero)
    }
    var decodedSize by remember(media.uri, focused, imageRevision) {
        mutableStateOf(IntSize.Zero)
    }
    LaunchedEffect(viewport, decodedSize) {
        zoomPose = viewerReboundZoom(zoomPose, viewport.width, viewport.height,
            decodedSize.width, decodedSize.height)
        onZoomChanged(zoomPose.scale)
    }
    // The pager retains neighboring pages. Readiness belongs to the
    // *current resolution/focus mode*, not merely the photo URI: when scrub
    // begins, a previously settled page must not decode 2400px for one more
    // frame while the effect cancellation is pending.
    var readyForLarge by remember(media.uri, imageRevision, fullResolution) {
        mutableStateOf(false)
    }
    LaunchedEffect(media.uri, imageRevision, fullResolution) {
        if (fullResolution) {
            delay(110)
            readyForLarge = true
        }
    }
    // Zoom transforms are intentionally applied only to the photo. Clip its
    // outer viewport so magnified pixels never bleed into the Back toolbar,
    // filmstrip or other adjacent gallery controls.
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        if (media.isVideo) {
            MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Fit, 700,
                forceFresh = forceFresh, refreshRevision = imageRevision)
            Icon(Icons.Filled.PlayArrow, "Video preview", Modifier.size(72.dp), tint = Color.White)
        } else {
            // Input, size and semantics modifiers MUST precede graphicsLayer:
            // modifiers after a scaling layer receive inverse-transformed
            // (scaled-down) pointer coordinates. That made a zoomed photo
            // move only 1/scale of the finger travel (0.4x at 2.5x, measured
            // on the API 35 emulator) and offset pinch/double-tap focal
            // points, while ViewerMotionMath expects viewport coordinates.
            Box(Modifier.fillMaxSize().testTag("atlas-photo-surface")
                .onSizeChanged { viewport = it }
                // Key ONLY on the photo/state-holder identity. Keying on
                // viewport/decodedSize restarted this loop when the HQ decode
                // landed (~0.1-0.5 s after focusing) or chrome toggled, which
                // silently cancelled an in-flight tap, pinch or zoomed pan
                // (reproduced on API 35 emulator). The delegated states are
                // read fresh at every pointer event, so no key is needed.
                .pointerInput(media.uri, imageRevision, focused) {
                    // Unlike detectTransformGestures, do NOT claim ordinary
                    // one-finger movements at scale 1. Pager owns those.
                    // This gesture loop only consumes a real pinch or a pan
                    // while magnified; two-finger transitions remain smooth.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // A pinch is a single atomic gesture: when its second
                        // finger lifts (or the photo reaches 1x), the remaining
                        // movement must NOT unexpectedly page to another photo.
                        var claimed = zoomPose.scale > 1.01f
                        val poseAtDown = zoomPose
                        var edgeTravelX = 0f
                        var multiTouch = false
                        do {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.count { it.pressed }
                            claimed = viewerGestureOwned(claimed, activePointers, zoomPose.scale)
                            if (claimed && activePointers > 0) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val pan = event.calculatePan()
                                val gestureZoom = event.calculateZoom()
                                val previous = zoomPose
                                if (activePointers > 1) {
                                    multiTouch = true
                                    edgeTravelX = 0f
                                } else if (!multiTouch) {
                                    val direction = viewerEdgeSwipeDirection(
                                        poseAtDown, previous, pan.x, pan.y,
                                        viewport.width, viewport.height,
                                        decodedSize.width, decodedSize.height)
                                    if (direction == 0) {
                                        edgeTravelX = 0f
                                    } else {
                                        if (edgeTravelX * pan.x < 0f) edgeTravelX = 0f
                                        edgeTravelX += pan.x
                                    }
                                }
                                val next = viewerZoomGesture(
                                    previous, gestureZoom, pan.x, pan.y,
                                    centroid.x, centroid.y, viewport.width, viewport.height,
                                    decodedSize.width, decodedSize.height)
                                if (next != previous) {
                                    zoomPose = next
                                    latestZoomChanged(next.scale)
                                }
                                // Consume two-pointer translations even when
                                // no scale/offset changed (e.g. at hard limits).
                                // Otherwise the parent pager can steal a pinch.
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!multiTouch) {
                            val target = viewerEdgePageTarget(
                                pageIndex, itemCount, edgeTravelX, viewport.width)
                            if (target != null) latestEdgeCallback(target)
                        }
                    }
                }
                .pointerInput(media.uri, imageRevision, focused) {
                    // Same identity-only key: a pending single tap waits for
                    // the double-tap timeout and must survive the HQ decode.
                    detectTapGestures(
                        onTap = { latestToggleChrome() },
                        onDoubleTap = { tap ->
                            zoomPose = viewerDoubleTapZoom(zoomPose, tap.x, tap.y,
                                viewport.width, viewport.height,
                                decodedSize.width, decodedSize.height)
                            latestZoomChanged(zoomPose.scale)
                        }
                    )
                }
                // Raw pointerInput gestures do not supply TalkBack with an
                // activation action. Expose the same chrome toggle, even in
                // immersive mode where there is no visible toolbar button.
                .semantics(mergeDescendants = true) {
                    if (focused) {
                        contentDescription = "Photo viewer: ${media.name}"
                        this[ViewerPhotoDecodedKey] = decodedSize != IntSize.Zero
                        stateDescription = if (chromeVisible)
                            "Gallery controls visible" else "Gallery controls hidden"
                        onClick(label = if (chromeVisible) "Hide gallery controls"
                            else "Show gallery controls") {
                            onToggleChrome()
                            true
                        }
                    }
                }
                // Innermost: transforms only the drawn photo content.
                .graphicsLayer {
                    scaleX = zoomPose.scale; scaleY = zoomPose.scale
                    translationX = zoomPose.translationX
                    translationY = zoomPose.translationY
                }) {
                MediaPreview(media,
                    Modifier.fillMaxSize().clearAndSetSemantics {}, ContentScale.Fit, 800,
                    onLoaded = onPreviewReady, observeLoaded = observePreviewLoads,
                    forceFresh = forceFresh, refreshRevision = imageRevision)
                if (shouldRenderLargePhoto(focused, fullResolution, readyForLarge)) {
                    val request = remember(media.uri, imageRevision) {
                        ImageRequest.Builder(context).data(media.uri).size(2400, 2400)
                            .apply {
                                if (forceFresh) {
                                    memoryCacheKey(editedPhotoMemoryKey(
                                        media.uri, imageRevision, 2400))
                                    diskCachePolicy(CachePolicy.DISABLED)
                                }
                            }
                            .crossfade(true).build()
                    }
                    AsyncImage(model = request, contentDescription = media.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("atlas-photo-hires")
                            .clearAndSetSemantics {},
                        onSuccess = { result ->
                            decodedSize = IntSize(result.result.image.width, result.result.image.height)
                        })
                }
                // Inside the zoom layer, so the box follows pinch and pan.
                if (faceBoxes.isNotEmpty()) FaceHighlightOverlay(faceBoxes,
                    viewerOrientedSize(media, decodedSize))
            }
        }
    }
}

/**
 * True on the focused photo surface once its full-resolution decode has set
 * the pan/zoom bounds. Before that, [viewerReboundZoom] re-fits the pose when
 * the decode lands, so a gesture test that zooms earlier races the decode.
 */
internal val ViewerPhotoDecodedKey = SemanticsPropertyKey<Boolean>("AtlasViewerPhotoDecoded")

/** Pause when leaving the foreground; never auto-resume on a later event. */
internal fun viewerVideoMustPause(event: Lifecycle.Event): Boolean =
    event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP

/** Clamp user progress before converting to a video time (also used by TalkBack). */
internal fun viewerVideoSeekPosition(durationMs: Long, fraction: Float): Long {
    if (durationMs <= 0 || !fraction.isFinite()) return 0L
    return (durationMs.toDouble() * fraction.coerceIn(0f, 1f)).toLong()
        .coerceIn(0L, durationMs)
}

internal fun viewerVideoTimecode(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    return if (minutes >= 60L)
        "%d:%02d:%02d".format(minutes / 60L, minutes % 60L, seconds % 60L)
    else "%d:%02d".format(minutes, seconds % 60L)
}

/**
 * Media3 only renders the decoded surface. The small, gallery-owned control
 * row lives above the unchanged filmstrip and bottom action pill, at the same
 * position for every video. Scrub frames are extracted on IO, only while the
 * seek thumb is held; no thumbnails are persisted or added to MediaStore.
 */
@Composable
internal fun VideoPage(
    uri: String, resumePositionMs: Long = 0L,
    onSavePosition: (Long) -> Unit = {},
    /** Gets a main-thread reader of the playing position while mounted, then null. */
    onLivePosition: ((() -> Long)?) -> Unit = {},
    chromeVisible: Boolean = true,
    onToggleChrome: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                if (resumePositionMs > 0) seekTo(resumePositionMs)
                prepare()
                playWhenReady = false
            }
    }
    val latestSavePosition by rememberUpdatedState(onSavePosition)
    // The visible position and play intent follow Media3 (including completion).
    // Reading the player only on main also keeps Find Similar's live reader safe.
    var playbackPositionMs by remember(player) { mutableLongStateOf(resumePositionMs.coerceAtLeast(0L)) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var playing by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            playing = player.playWhenReady &&
                player.playbackState != androidx.media3.common.Player.STATE_ENDED
            delay(180)
        }
    }
    // A user may start playback then background ATLAS, lock the phone, or
    // open another activity. Pause before invisible; no automatic resume.
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (viewerVideoMustPause(event)) player.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        if (!lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            player.pause()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val latestLivePosition by rememberUpdatedState(onLivePosition)
    DisposableEffect(player) {
        latestLivePosition { player.currentPosition }
        onDispose {
            latestLivePosition(null)
            latestSavePosition(viewerPlaybackCheckpoint(
                player.currentPosition, player.duration,
                player.playbackState == androidx.media3.common.Player.STATE_ENDED))
            player.release()
        }
    }

    var seeking by remember(player) { mutableStateOf(false) }
    var seekFraction by remember(player) { mutableFloatStateOf(0f) }
    var resumeAfterSeeking by remember(player) { mutableStateOf(false) }
    val progress = if (seeking) seekFraction else if (durationMs > 0L)
        (playbackPositionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        else 0f
    val targetMs = if (seeking) viewerVideoSeekPosition(durationMs, seekFraction) else null
    var seekPreview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    // Changing the target cancels the previous request; a bounded 240x135
    // frame is decoded only after the finger has held its latest position.
    LaunchedEffect(uri, targetMs) {
        seekPreview = null
        val timeMs = targetMs ?: return@LaunchedEffect
        if (durationMs <= 0L) return@LaunchedEffect
        delay(100)
        seekPreview = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getScaledFrameAtTime(
                    timeMs.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135)
            } catch (_: Exception) {
                // Revoked or corrupt media: leave the preview empty, never
                // expose a URI or interfere with Media3 playback.
                null
            } finally {
                retriever.release()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player
            useController = false
        } }, modifier = Modifier.fillMaxSize())
        // The Android video surface has no tap action. This transparent
        // Compose layer gives touch users the same chrome toggle as TalkBack
        // without stealing horizontal pager drags.
        Box(Modifier.fillMaxSize().pointerInput(uri) {
            detectTapGestures(onTap = { onToggleChrome() })
        })
        AnimatedVisibility(chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 14.dp, vertical = 164.dp),
            enter = CHROME_ENTER, exit = CHROME_EXIT
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (seeking) {
                    Box(Modifier.size(176.dp, 110.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF292929))
                        .testTag("atlas-video-seek-preview"),
                        contentAlignment = Alignment.Center) {
                        seekPreview?.let { frame ->
                            Image(frame.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        }
                        Text(viewerVideoTimecode(targetMs ?: 0L),
                            color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .background(Color(0xA0000000))
                                .padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ViewerPill(Modifier.fillMaxWidth().height(54.dp)
                    .testTag("atlas-video-controls")) {
                    IconButton(
                        onClick = {
                            if (player.playWhenReady) player.pause()
                            else {
                                if (player.playbackState ==
                                    androidx.media3.common.Player.STATE_ENDED) player.seekTo(0L)
                                player.play()
                            }
                        },
                        modifier = Modifier.testTag("atlas-video-play-pause")
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause video" else "Play video", tint = Color.White)
                    }
                    Text(viewerVideoTimecode(if (seeking) targetMs ?: 0L else playbackPositionMs),
                        color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = progress,
                        onValueChange = { value ->
                            if (!seeking) {
                                resumeAfterSeeking = player.playWhenReady
                                player.pause()
                                seeking = true
                            }
                            seekFraction = value.coerceIn(0f, 1f)
                        },
                        onValueChangeFinished = {
                            if (seeking) {
                                val atMs = viewerVideoSeekPosition(durationMs, seekFraction)
                                player.seekTo(atMs)
                                playbackPositionMs = atMs
                                if (resumeAfterSeeking) player.play()
                                seeking = false
                            }
                        },
                        enabled = durationMs > 0L,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0xFF888888)),
                        modifier = Modifier.weight(1f)
                            .testTag("atlas-video-seek")
                            .semantics { contentDescription = "Seek video" }
                    )
                    Text(viewerVideoTimecode(durationMs),
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

/**
 * VS-S2b (#468): the viewer's "Find similar" hook, supplied by the host. It
 * gets the shown item and, for a video, the frame time on screen (ms).
 */
class ViewerFindSimilar(val onFindSimilar: (MediaRecord, Long?) -> Unit)

/** VS-F2b (#468): the viewer's "Find this person" hook, supplied by the host. */
class ViewerFaceSearch(
    /** Off main: true when [MediaRecord] has indexed faces under the live grant. */
    val hasFaces: suspend (MediaRecord) -> Boolean,
    val onFindPerson: (MediaRecord) -> Unit
)

/**
 * The displayed (EXIF-oriented) pixel size of [media]: the full decode once
 * it landed, else MediaStore's size with a 90/270 rotation applied. Face boxes
 * are normalised to the same oriented frame the face decoder read.
 */
internal fun viewerOrientedSize(media: MediaRecord, decoded: IntSize): IntSize = when {
    decoded.width > 0 && decoded.height > 0 -> decoded
    media.widthPixels <= 0 || media.heightPixels <= 0 -> IntSize.Zero
    media.orientationDegrees % 180 != 0 -> IntSize(media.heightPixels, media.widthPixels)
    else -> IntSize(media.widthPixels, media.heightPixels)
}

/** Box (normalised to the image) mapped into a ContentScale.Fit viewport, in px. */
internal fun fitFaceRect(
    box: IndexedFaceBox, viewportW: Float, viewportH: Float, imageW: Int, imageH: Int
): Rect? {
    if (imageW <= 0 || imageH <= 0 || viewportW <= 0f || viewportH <= 0f) return null
    val scale = minOf(viewportW / imageW, viewportH / imageH)
    val w = imageW * scale
    val h = imageH * scale
    val left = (viewportW - w) / 2f
    val top = (viewportH - h) / 2f
    return Rect(left + box.left.coerceIn(0f, 1f) * w, top + box.top.coerceIn(0f, 1f) * h,
        left + box.right.coerceIn(0f, 1f) * w, top + box.bottom.coerceIn(0f, 1f) * h)
}

@Composable
private fun FaceHighlightOverlay(boxes: List<IndexedFaceBox>, image: IntSize) {
    if (image == IntSize.Zero) return
    Canvas(Modifier.fillMaxSize().testTag("atlas-viewer-face-highlight")
        .semantics { contentDescription = "${boxes.size} matched face highlighted" }) {
        val stroke = 3.dp.toPx()
        for (box in boxes) {
            val r = fitFaceRect(box, size.width, size.height, image.width, image.height) ?: continue
            drawRoundRect(Color(0xFFB5E7DA), topLeft = r.topLeft, size = r.size,
                cornerRadius = CornerRadius(stroke * 2), style = Stroke(width = stroke))
        }
    }
}
