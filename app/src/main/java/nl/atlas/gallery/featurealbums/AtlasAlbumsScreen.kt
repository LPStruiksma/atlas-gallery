package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuredesign.AtlasScrollAwayHeader
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuredesign.AtlasSpace
import java.text.NumberFormat

private fun mediaSummary(album: AlbumSummary): String {
    val parts = buildList {
        if (album.photoCount > 0) add(
            if (album.photoCount == 1) "1 photo" else
                NumberFormat.getIntegerInstance().format(album.photoCount) + " photos")
        if (album.videoCount > 0) add(
            if (album.videoCount == 1) "1 video" else
                NumberFormat.getIntegerInstance().format(album.videoCount) + " videos")
    }
    return parts.joinToString(" · ")
}

/**
 * Only safe display categories cross into UI. Never pass raw ContentResolver
 * exception text, private paths, or provider stack traces to a card.
 */
enum class AlbumLoadIssue { ACCESS_NOT_GRANTED, READ_FAILED }

/** A card has one accessible click target; the photograph remains the hero. */
@Composable
fun AtlasAlbumCard(
    album: AlbumSummary,
    onOpen: (AlbumRoute) -> Unit,
    mediaFilter: AlbumMediaFilter,
    modifier: Modifier = Modifier,
    coverIllustration: (@Composable (AlbumSummary) -> Unit)? = null,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)? = null,
    onChooseCover: ((AlbumRoute) -> Unit)? = null,
    /** Non-null when the grid owns long press (drag to reorder); the card then only opens. */
    externalCoverMenu: Boolean? = null,
    onExternalCoverMenuDismiss: () -> Unit = {},
    accessibilityActions: List<CustomAccessibilityAction> = emptyList()
) {
    val summary = remember(album.photoCount, album.videoCount) { mediaSummary(album) }
    // Distinct folders can share their visible name. Include the disambiguating
    // source location in the single TalkBack target, even if text is ellipsized.
    val spokenLabel = remember(album.title, album.locationLabel, summary) {
        listOfNotNull(album.title, album.locationLabel, summary).joinToString(", ")
    }
    val route = remember(album.relativePath, mediaFilter, album.volumeName) {
        AlbumRoute(album.relativePath, mediaFilter, album.volumeName)
    }
    var ownCoverMenu by remember { mutableStateOf(false) }
    val gridOwnsLongPress = externalCoverMenu != null
    val coverMenuOpen = if (gridOwnsLongPress) externalCoverMenu == true else ownCoverMenu
    val longPressLabel = if (onChooseCover == null) null else "Album cover options"
    Box(modifier = modifier) {
        // Independent sibling targets: the album's Open action cannot swallow
        // the optional cover editor or present it as a nested click action.
        Column(
            modifier = Modifier.fillMaxWidth()
                .semantics {
                    contentDescription = spokenLabel
                    if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                }
                .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                .combinedClickable(
                    onClick = { onOpen(route) },
                    // When the grid owns long press (hold to drag, hold-and-release
                    // for the cover menu) the card must not race it with its own.
                    onLongClick = if (onChooseCover == null || gridOwnsLongPress) null
                        else ({ ownCoverMenu = true }),
                    onClickLabel = "Open album",
                    onLongClickLabel = if (gridOwnsLongPress) null else longPressLabel,
                    role = Role.Button
                )
                .testTag("atlas-album-card")
                .padding(bottom = 8.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (coverIllustration != null) {
                    // Fictional showcase hook only. Production always uses real Coil media.
                    coverIllustration(album)
                } else if (album.coverMedia != null && coverRenderer != null) {
                    // Renderer is supplied by I, which owns current grants, publication
                    // epoch and M's per-media cache revision. A bare URI here would
                    // bypass revocation safety and could resurrect stale thumbnails.
                    coverRenderer(album.coverMedia, Modifier.fillMaxSize())
                } else {
                    Icon(
                        Icons.Filled.Collections, contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
                // The photograph fills the rounded tile; labels live underneath.
                if (album.coverIsVideo) {
                    Icon(
                        Icons.Filled.PlayCircle, contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                            .padding(5.dp).size(25.dp),
                        tint = Color.White
                    )
                }

            }
            Spacer(Modifier.height(10.dp))
            Text(
                album.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)
            )
            if (album.locationLabel != null) {
                Text(
                    album.locationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp)
                )
            }
            Text(
                NumberFormat.getIntegerInstance().format(album.mediaCount), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp)
            )
        }
        if (onChooseCover != null) {
            // Contextual edit is available through the card long press.
            // The album cover stays unobstructed until the user requests it.
            DropdownMenu(
                expanded = coverMenuOpen,
                onDismissRequest = {
                    ownCoverMenu = false
                    onExternalCoverMenuDismiss()
                }
            ) {
                DropdownMenuItem(
                    text = { Text("Choose cover") },
                    onClick = {
                        ownCoverMenu = false
                        onExternalCoverMenuDismiss()
                        onChooseCover(route)
                    },
                    modifier = Modifier.testTag("atlas-cover-picker-open")
                )
            }
        }
    }
}

/**
 * Samsung parity R3/P5 (#20): one sort icon opens the same compact bottom
 * choice sheet as Photos. This only chooses an AlbumSort; the caller retains
 * the catalog projection, user Custom order and persistence contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtlasAlbumSortMenu(
    sort: AlbumSort,
    onSort: (AlbumSort) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSort by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { showSort = true },
            modifier = Modifier.size(AtlasSpace.MinimumTarget).testTag("atlas-album-sort")
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort: " + albumSortLabel(sort))
        }
    }
    if (showSort) {
        ModalBottomSheet(
            onDismissRequest = { showSort = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("atlas-album-sort-sheet")
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                AlbumSort.entries.forEach { choice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(
                                selected = choice == sort,
                                role = Role.RadioButton,
                                onClick = {
                                    showSort = false
                                    onSort(choice)
                                }
                            )
                            .testTag("atlas-album-sort-" + choice.name.lowercase()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(albumSortLabel(choice), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = choice == sort, onClick = null)
                    }
                }
            }
        }
    }
}

private fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.RECENT -> "Recent"
    AlbumSort.NAME -> "Name"
    AlbumSort.COUNT -> "Number of items"
    AlbumSort.CUSTOM -> "Custom"
}

/**
 * Independent Albums destination. The integrator owns async background
 * projection, refresh generation, selected route and Back/filmstrip state.
 * The component never changes MediaStore, sort preferences or permissions.
 */
@Composable
fun AtlasAlbumsScreen(
    albums: List<AlbumSummary>,
    sort: AlbumSort,
    onSort: (AlbumSort) -> Unit,
    onOpenAlbum: (AlbumRoute) -> Unit,
    mediaFilter: AlbumMediaFilter,
    onMediaFilterChange: (AlbumMediaFilter) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onOpenLibraryStatus: (() -> Unit)? = null,
    coverIllustration: (@Composable (AlbumSummary) -> Unit)? = null,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)? = null,
    loadIssue: AlbumLoadIssue? = null,
    onRetry: (() -> Unit)? = null,
    onChooseCover: ((AlbumRoute) -> Unit)? = null,
    onOpenStories: (() -> Unit)? = null,
    onOpenPhotoDna: (() -> Unit)? = null,
    /** Samsung's "Hide albums" screen (#568, parity research GRID:A7). */
    onOpenHideAlbums: (() -> Unit)? = null,
    /**
     * Receives the whole new display order after a drag (or a TalkBack move).
     * Pinned albums never move; the caller switches the sort to Custom.
     */
    onReorderAlbums: ((List<AlbumSummary>) -> Unit)? = null
) {
    // One compact header: secondary destinations share its sole overflow.
    val header: @Composable () -> Unit = {
        AtlasGalleryHeader(
            title = "Albums",
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            onOpenLibraryStatus = onOpenLibraryStatus,
            menuItems = buildList {
                if (onOpenStories != null) add(AtlasHeaderMenuItem(
                    "Stories", "atlas-albums-menu-stories", onClick = onOpenStories))
                if (onOpenPhotoDna != null) add(AtlasHeaderMenuItem(
                    "Photo DNA", "atlas-albums-menu-photo-dna", onClick = onOpenPhotoDna))
                if (onOpenHideAlbums != null) add(AtlasHeaderMenuItem(
                    "Hide albums", "atlas-albums-menu-hide", onClick = onOpenHideAlbums))
            },
            actions = { AtlasAlbumSortMenu(sort, onSort) }
        )
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The grid draws the header itself so it can scroll away (#506).
        if (isLoading || loadIssue != null || albums.isEmpty()) header()
        // An in-progress permission/source refresh must not expose old covers.
        if (isLoading) {
            AtlasGalleryLoading("Gathering albums on this device…")
            return@Column
        }
        if (loadIssue != null) {
            // Do not expose stale covers or claim access while permissions fail.
            // Optional buttons are shown only when the provided action is real.
            val accessFailure = loadIssue == AlbumLoadIssue.ACCESS_NOT_GRANTED
            val action = if (accessFailure) onOpenLibraryStatus else onRetry
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AtlasEmptyState(
                    title = if (accessFailure) "Albums need photo access"
                        else "Couldn’t refresh albums",
                    explanation = if (accessFailure)
                        "ATLAS can only show albums you allow it to access. " +
                            "Review your photo and video access in Library status."
                    else "Your original photos haven’t been changed. " +
                        "Try loading the library again.",
                    icon = Icons.Filled.Collections,
                    actionLabel = if (action == null) null else
                        if (accessFailure) "Library status" else "Try again",
                    onAction = action
                )
            }
            return@Column
        }
        if (albums.isEmpty()) {
            // Large font/landscape can otherwise clip the primary action.
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AtlasEmptyState(
                    title = when (mediaFilter) {
                        AlbumMediaFilter.ALL -> "Your albums will appear here"
                        AlbumMediaFilter.PHOTOS -> "No photo albums here"
                        AlbumMediaFilter.VIDEOS -> "No video albums here"
                    },
                    explanation = when (mediaFilter) {
                        AlbumMediaFilter.ALL ->
                            "Albums appear here when ATLAS can access photos or videos on this device."
                        AlbumMediaFilter.PHOTOS ->
                            "None of your currently accessible albums contain photos. Try All."
                        AlbumMediaFilter.VIDEOS ->
                            "None of your currently accessible albums contain videos. Try All."
                    },
                    icon = Icons.Filled.Collections,
                    actionLabel = if (mediaFilter == AlbumMediaFilter.ALL) null else "Show all albums",
                    onAction = if (mediaFilter == AlbumMediaFilter.ALL) null else {
                        { onMediaFilterChange(AlbumMediaFilter.ALL) }
                    }
                )
            }
            return@Column
        }
        AlbumGrid(
            header = header,
            albums = albums,
            mediaFilter = mediaFilter,
            onOpenAlbum = onOpenAlbum,
            coverIllustration = coverIllustration,
            coverRenderer = coverRenderer,
            onChooseCover = onChooseCover,
            onReorderAlbums = onReorderAlbums
        )
    }
}

/**
 * Samsung-style album grid: three rounded covers per row on a phone. With
 * [onReorderAlbums], touch-and-hold lifts an unpinned album so it can be
 * dragged to a new place (Samsung's custom order); releasing without moving
 * opens the cover menu instead. The gesture runs in the Initial pass and
 * consumes events only once the hold is recognised, so taps still open an
 * album and ordinary drags still scroll the grid.
 */
@Composable
private fun AlbumGrid(
    header: @Composable () -> Unit,
    albums: List<AlbumSummary>,
    mediaFilter: AlbumMediaFilter,
    onOpenAlbum: (AlbumRoute) -> Unit,
    coverIllustration: (@Composable (AlbumSummary) -> Unit)?,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)?,
    onChooseCover: ((AlbumRoute) -> Unit)?,
    onReorderAlbums: ((List<AlbumSummary>) -> Unit)?
) {
    // Scroll position survives a reorder (a new list) but holds no album data.
    val gridState = rememberLazyGridState()
    val listKey = AlbumPresentationListKey(albums)
    // Live order while dragging; kept after the drop until the caller's new
    // projection arrives, so the album does not flash back to its old slot.
    var dragOrder by remember(listKey) { mutableStateOf<List<AlbumSummary>?>(null) }
    var dragged by remember(listKey) { mutableStateOf<AlbumSummary?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var coverMenuFor by remember(listKey) { mutableStateOf<AlbumSummary?>(null) }
    val haptics = LocalHapticFeedback.current
    val shown = dragOrder ?: albums
    // Numeric keys from the caller's list position: unique, stable during a
    // drag (so neighbours animate aside) and never a private folder path.
    val originalIndex = remember(listKey) {
        java.util.IdentityHashMap<AlbumSummary, Int>().apply {
            albums.forEachIndexed { index, album -> put(album, index) }
        }
    }
    val currentAlbums by rememberUpdatedState(albums)
    val currentOnReorder by rememberUpdatedState(onReorderAlbums)

    val reorderGesture = if (onReorderAlbums == null) Modifier else
        Modifier.pointerInput(listKey) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.containsPoint(down.position) } ?: return@awaitEachGesture
                val album = currentAlbums.getOrNull(start.index) ?: return@awaitEachGesture
                if (!awaitHold(down)) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val movable = albumPinRank(album.title) == UNPINNED_RANK
                if (movable) {
                    dragOrder = currentAlbums
                    dragged = album
                    pointer = down.position
                    grab = down.position - start.offset.toOffset()
                }
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    if (!moved && (change.position - down.position).getDistance() >
                        viewConfiguration.touchSlop) moved = true
                    if (!movable || !moved) continue
                    pointer = change.position
                    val order = dragOrder ?: break
                    val over = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.containsPoint(change.position) } ?: continue
                    val from = order.indexOfFirst { it === album }
                    val next = moveUnpinnedAlbum(order, from, over.index)
                    if (next !== order) {
                        dragOrder = next
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                val finalOrder = dragOrder
                dragged = null
                // Dragging away and back yields a new list with the same order:
                // that is no reorder and must not switch the sort to Custom.
                if (moved && finalOrder != null && !sameAlbumOrder(finalOrder, currentAlbums)) {
                    currentOnReorder?.invoke(finalOrder)
                } else {
                    dragOrder = null
                    if (!moved && onChooseCover != null) coverMenuFor = album
                }
            }
        }

    // A new published list re-keys the grid content so no slot state from an
    // older list is applied to another folder; scroll position is kept.
    val gridScope = remember(AlbumPresentationListKey(albums)) { Any() }
    // Samsung (#506): the header scrolls away with the covers and returns on
    // any upward scroll. A held album never scrolls the grid, so it stays put.
    AtlasScrollAwayHeader(gridState, Modifier.fillMaxSize(), header = header) { headerHeight ->
    key(gridScope) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(
            start = AtlasSpace.Gutter,
            end = AtlasSpace.Gutter,
            top = headerHeight,
            bottom = AtlasSpace.Section + LocalAtlasBottomChrome.current
        ),
        horizontalArrangement = Arrangement.spacedBy(AtlasSpace.TileGap),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize().then(reorderGesture).testTag("atlas-album-grid")
    ) {
        itemsIndexed(shown, key = { index, album -> originalIndex[album] ?: (-1 - index) }) {
                index, album ->
            val lifted = album === dragged
            val movable = onReorderAlbums != null && albumPinRank(album.title) == UNPINNED_RANK
            val actions = if (!movable) emptyList() else buildList {
                if (index > 0 && albumPinRank(shown[index - 1].title) == UNPINNED_RANK) {
                    add(CustomAccessibilityAction("Move earlier") {
                        onReorderAlbums?.invoke(moveUnpinnedAlbum(shown, index, index - 1)); true
                    })
                }
                if (index < shown.lastIndex) {
                    add(CustomAccessibilityAction("Move later") {
                        onReorderAlbums?.invoke(moveUnpinnedAlbum(shown, index, index + 1)); true
                    })
                }
            } + if (onChooseCover != null && onReorderAlbums != null) listOf(
                CustomAccessibilityAction("Choose cover") {
                    onChooseCover(AlbumRoute(album.relativePath, mediaFilter, album.volumeName)); true
                }) else emptyList()
            AtlasAlbumCard(
                album, onOpen = onOpenAlbum, mediaFilter = mediaFilter,
                coverIllustration = coverIllustration, coverRenderer = coverRenderer,
                onChooseCover = onChooseCover,
                externalCoverMenu = if (onReorderAlbums == null) null else coverMenuFor === album,
                onExternalCoverMenuDismiss = { coverMenuFor = null },
                accessibilityActions = actions,
                modifier = Modifier
                    .then(if (lifted) Modifier.zIndex(1f) else
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = tween(250, easing = FastOutSlowInEasing)))
                    .graphicsLayer {
                        if (lifted) {
                            // Read per frame only here (draw phase), never in composition.
                            val slot = gridState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == index }?.offset?.toOffset() ?: Offset.Zero
                            val at = pointer - grab - slot
                            translationX = at.x
                            translationY = at.y
                            scaleX = 1.06f
                            scaleY = 1.06f
                            shadowElevation = 12.dp.toPx()
                            shape = RoundedCornerShape(AtlasSpace.CoverCorner)
                            clip = false
                        }
                    }
            )
        }
    }
    }
    }
}

private const val UNPINNED_RANK = 3

private fun sameAlbumOrder(a: List<AlbumSummary>, b: List<AlbumSummary>): Boolean =
    a.size == b.size && a.indices.all { a[it] === b[it] }

private fun LazyGridItemInfo.containsPoint(point: Offset): Boolean =
    point.x >= offset.x && point.x < offset.x + size.width &&
        point.y >= offset.y && point.y < offset.y + size.height

/** True once the pointer is held still for the long-press timeout. */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange): Boolean {
    val ended = withTimeoutOrNull<Boolean>(viewConfiguration.longPressTimeoutMillis) {
        var done = false
        while (!done) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            done = change == null || !change.pressed || change.isConsumed ||
                event.changes.size > 1 ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        }
        true
    }
    return ended == null
}
