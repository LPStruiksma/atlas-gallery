package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.MediaRecord

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
        GridDensity.entries.forEach { choice ->
            FilterChip(
                selected = choice == density,
                onClick = { onChoose(choice) },
                label = { Text(when (choice) {
                    GridDensity.LARGE -> "Large"
                    GridDensity.MEDIUM -> "Medium"
                    GridDensity.SMALL -> "Small"
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
    emptyText: String = "No photos here yet"
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(DENSITY_PREFS, Context.MODE_PRIVATE) }
    var density by rememberSaveable(viewKey) {
        mutableStateOf(entryDensity ?: GridDensity.fromColumns(prefs.getInt(viewKey, GridDensity.MEDIUM.columns)))
    }
    LaunchedEffect(viewKey, entryDensity) {
        if (entryDensity != null && density != entryDensity) density = entryDensity
    }
    LaunchedEffect(viewKey, density) { prefs.edit().putInt(viewKey, density.columns).apply() }

    val gapPx = with(LocalDensity.current) { 3.dp.roundToPx() }
    // In Compose effects and pointerInput keys, reference identity is O(1) and
    // distinguishes equal-looking catalogs published under different grants.
    val sourceEpoch = GridCatalogIdentity(items, catalogueEpoch)
    var pendingAnchor by remember { mutableStateOf<GridAnchorCommit?>(null) }
    val currentOpen by rememberUpdatedState(onOpen)
    val originalCurrent by rememberUpdatedState(onOriginalProportionsRequested)

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
        if (next == density) return
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
        val size = state.layoutInfo.viewportSize
        chooseDensity(next, Offset(size.width / 2f, size.height / 2f))
    }

    Column(modifier) {
        if (showViewAs) GridViewAsControl(density, onChoose = ::chooseAtCentre)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (busy) "Loading photos…" else emptyText,
                    Modifier.testTag("atlas-feature-grid-empty"))
            }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val cellPx = GridViewportGeometry(
                viewportWidthPx.coerceAtLeast(1), 1, gapPx, gapPx, 0
            ).cellSidePx(density.columns)
            val previewPixels = gridPreviewRequestPixels(cellPx, cellPx)
            // Position-only lazy keys may be saved by Compose. Remount the grid
            // on any new source/grant or density, never by a private URI.
            key(sourceEpoch, density, viewKey) {
            LazyVerticalGrid(
            columns = GridCells.Fixed(density.columns),
            state = state,
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp,
                bottom = LocalAtlasBottomChrome.current),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize().galleryPinchLadder(sourceEpoch, density, state, newTracker = { slop ->
                GalleryPinchTracker(GalleryZoomStop.forDensity(density), slop,
                    allowOriginal = originalCurrent != null)
            }) { next, focus ->
                if (next == GalleryZoomStop.ORIGINAL) requestOriginal(focus)
                else next.squareDensityOrNull()?.let { chooseDensity(it, focus) }
            }.semantics {
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
                        GridDensity.entries.getOrNull(density.ordinal + 1)?.let { smaller ->
                            add(CustomAccessibilityAction(SMALLER_TILES_ACTION) {
                                chooseAtCentre(smaller); true })
                        }
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            }.testTag("atlas-feature-grid")
        ) {
            itemsIndexed(items, key = { index, _ -> gridLazySaveablePositionKey(index) }) { _, media ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClickLabel = "Open ${media.name}") { currentOpen(media) }
                    .testTag("atlas-grid-media-${media.id}-${if (media.isVideo) "video" else "photo"}")) {
                    // Existing Coil media request and cache remain owned by the host.
                    // A fixed request size across 3/4/7 does not force extra decodes
                    // while the user is changing density.
                    MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Crop, previewPixels, gridTile = true)
                    if (media.isVideo) Text("▶", Modifier.align(Alignment.BottomEnd)
                        .padding(6.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        } // End source/grant/layout keyed Compose subtree.
        }
    }
}

/** Convenience for a new screen; integrations with existing navigation pass their state. */
@Composable
fun rememberAtlasGridState(): LazyGridState = rememberLazyGridState()
