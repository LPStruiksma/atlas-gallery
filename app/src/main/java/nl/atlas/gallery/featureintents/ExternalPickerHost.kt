package nl.atlas.gallery.featureintents

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.GalleryViewModel
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.collectAsIdentityState
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumRoute
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featurealbums.buildAlbumSummaries
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuregrid.AtlasTimelineGallery
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.rememberAtlasGridState
import nl.atlas.gallery.matchesAlbumRoute
import nl.atlas.gallery.necessaryPermissions
import nl.atlas.gallery.needsNewGrantBoundScan
import nl.atlas.gallery.readGalleryMediaGrant

/** Test tags for the external picker (#573 A2b). */
internal object ExternalPickerTags {
    const val ROOT = "atlas-picker"
    const val TITLE = "atlas-picker-title"
    const val TOP_CANCEL = "atlas-picker-top-cancel"
    const val CANCEL = "atlas-picker-cancel"
    const val DONE = "atlas-picker-done"
    const val TAB_PICTURES = "atlas-picker-tab-pictures"
    const val TAB_ALBUMS = "atlas-picker-tab-albums"
    const val ALBUM_BACK = "atlas-picker-album-back"
    const val EMPTY = "atlas-picker-empty"
    const val PERMISSION = "atlas-picker-permission"
    const val ERROR = "atlas-picker-error"
}

/**
 * The choice survives rotation (a ViewModel, never a saved Bundle), and is
 * dropped with the Activity: a cancelled or finished pick leaves nothing behind.
 */
internal class ExternalPickViewModel : ViewModel() {
    var chosen by mutableStateOf(emptyList<String>())
    var albumRoute by mutableStateOf<AlbumRoute?>(null)
    var albumsTab by mutableStateOf(false)
}

/**
 * BENCH-2 (#573) A2b: another app asked ATLAS for photos or videos
 * (`ACTION_GET_CONTENT` / `ACTION_PICK`).
 *
 * The same Pictures timeline grid and Albums list as the library, with only the
 * requested kind offered, but none of the library's other surfaces (no Settings,
 * Trash, People, Stories, viewer or sharing). A tap chooses; Done hands back
 * exactly the chosen items, re-checked against the provider's own MIME type;
 * Cancel or Back hands back nothing. ATLAS's own media permission is asked for
 * like on any first start; the caller's request is never treated as one.
 */
@Composable
internal fun ExternalPickerHost(
    request: ExternalEntry.Pick,
    vm: GalleryViewModel,
    /** Null cancels. Called at most once by the Activity's guard. */
    onFinish: (PickResultPlan?) -> Unit
) {
    val context = LocalContext.current
    val pick: ExternalPickViewModel = viewModel(key = "external-pick-$request")
    val inventory by vm.publishedInventory.collectAsIdentityState()
    val busy by vm.busy.collectAsIdentityState()
    var grants by remember { mutableStateOf(readGalleryMediaGrant(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        grants = readGalleryMediaGrant(context)
        if (grants.hasAny) vm.refreshForAccessChange()
    }
    // Same start as the library: show the cached catalogue at once and bind a
    // fresh scan to the current grant; a narrowed or lost grant drops the list.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = readGalleryMediaGrant(context)
                grants = current
                when {
                    !current.hasAny -> vm.revokeMediaAccess()
                    needsNewGrantBoundScan(current, vm.publishedInventory.value.scanBoundGrant, null) ->
                        vm.refreshForAccessChange()
                    else -> vm.refreshIfChanged()
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val filter = request.filter
    // Only the requested kind, newest first; 150k rows are filtered off the main thread.
    val offered by produceState<List<MediaRecord>?>(null, inventory.items, filter) {
        value = withContext(Dispatchers.Default) {
            val job = coroutineContext
            inventory.items.filterIndexed { index, record ->
                if ((index and 1023) == 0) job.ensureActive()
                filter.offers(record.isVideo)
            }.sortedWith(compareByDescending<MediaRecord> { it.takenAtMillis }.thenByDescending { it.id })
        }
    }
    // A chosen item that disappears (deleted, grant narrowed) leaves the choice.
    LaunchedEffect(offered) {
        val now = offered ?: return@LaunchedEffect
        val uris = withContext(Dispatchers.Default) { now.mapTo(HashSet()) { it.uri } }
        pick.chosen = PickSelection.stillOffered(pick.chosen, uris)
    }

    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun toggle(record: MediaRecord) {
        error = null
        pick.chosen = PickSelection.toggle(pick.chosen, record.uri, request.allowMultiple)
    }
    fun done() {
        val chosen = pick.chosen
        if (resolving || !PickResultPlanner.doneEnabled(request, chosen.size)) return
        resolving = true
        scope.launch {
            val resolver = context.applicationContext.contentResolver
            val usable = withContext(Dispatchers.IO) { chosen.filter { usableForCaller(resolver, it, filter) } }
            resolving = false
            if (usable.size != chosen.size) {
                // Keep the picker open with what still works; never return a partial set.
                pick.chosen = usable
                error = PickCopy.SOME_UNAVAILABLE
                return@launch
            }
            PickResultPlanner.plan(request, usable)?.let(onFinish)
        }
    }

    BackHandler {
        when {
            pick.albumRoute != null -> pick.albumRoute = null
            else -> onFinish(null)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(ExternalPickerTags.ROOT)) {
        PickerTopBar(request, onCancel = { onFinish(null) })
        if (!grants.hasAny) {
            AtlasEmptyState(
                title = "Photos need access",
                explanation = "Atlas shows your own photos and videos here so you can choose some.",
                actionLabel = "Allow access",
                onAction = { permission.launch(necessaryPermissions()) },
                bottomInset = 0.dp,
                modifier = Modifier.weight(1f).testTag(ExternalPickerTags.PERMISSION)
            )
        } else {
            PickerTabs(albums = pick.albumsTab, onAlbums = {
                pick.albumsTab = it
                pick.albumRoute = null
            })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val items = offered
                when {
                    items == null || (items.isEmpty() && (busy || inventory.fromCache)) ->
                        AtlasGalleryLoading(bottomInset = 0.dp)
                    items.isEmpty() -> AtlasEmptyState(
                        title = PickCopy.empty(filter),
                        bottomInset = 0.dp,
                        modifier = Modifier.testTag(ExternalPickerTags.EMPTY),
                        titleTag = "atlas-picker-empty-title"
                    )
                    pick.albumsTab && pick.albumRoute == null -> PickerAlbums(items) { pick.albumRoute = it }
                    else -> {
                        val route = pick.albumRoute
                        PickerGrid(
                            items = items,
                            route = route,
                            epoch = inventory.epoch,
                            chosen = pick.chosen,
                            allowMultiple = request.allowMultiple,
                            onToggle = ::toggle,
                            onReplace = { newSet, shown ->
                                error = null
                                pick.chosen = PickSelection.replaced(pick.chosen, newSet, shown, request.allowMultiple)
                            },
                            onAlbumBack = { pick.albumRoute = null }
                        )
                    }
                }
            }
        }
        error?.let { message ->
            Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }.testTag(ExternalPickerTags.ERROR),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        PickerActions(
            doneLabel = PickCopy.done(request, pick.chosen.size),
            doneEnabled = grants.hasAny && !resolving && PickResultPlanner.doneEnabled(request, pick.chosen.size),
            onCancel = { onFinish(null) },
            onDone = ::done
        )
    }
}

/** The provider's own type must satisfy the caller's request, and the item must still open. */
internal fun usableForCaller(resolver: ContentResolver, uri: String, filter: PickMediaFilter): Boolean = try {
    val parsed = Uri.parse(uri)
    filter.accepts(resolver.getType(parsed)) &&
        resolver.openAssetFileDescriptor(parsed, "r")?.use { true } == true
} catch (stopped: kotlinx.coroutines.CancellationException) {
    throw stopped
} catch (_: Exception) {
    false
}

/** `data` holds the first item; `ClipData` holds all, in pick order; one read grant covers both. */
internal fun pickResultIntent(plan: PickResultPlan): Intent = Intent().apply {
    data = Uri.parse(plan.primaryUri)
    val clip = ClipData.newRawUri(null, Uri.parse(plan.clipUris.first()))
    plan.clipUris.drop(1).forEach { clip.addItem(ClipData.Item(Uri.parse(it))) }
    clipData = clip
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
private fun PickerTopBar(request: ExternalEntry.Pick, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
        .heightIn(min = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)
            .testTag(ExternalPickerTags.TOP_CANCEL)) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Text(PickCopy.title(request), Modifier.weight(1f).semantics { heading() }
            .testTag(ExternalPickerTags.TITLE),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickerTabs(albums: Boolean, onAlbums: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !albums, onClick = { onAlbums(false) }, label = { Text("Pictures") },
            modifier = Modifier.heightIn(min = 48.dp).testTag(ExternalPickerTags.TAB_PICTURES))
        FilterChip(selected = albums, onClick = { onAlbums(true) }, label = { Text("Albums") },
            modifier = Modifier.heightIn(min = 48.dp).testTag(ExternalPickerTags.TAB_ALBUMS))
    }
}

@Composable
private fun PickerAlbums(items: List<MediaRecord>, onOpen: (AlbumRoute) -> Unit) {
    var sort by remember { mutableStateOf(AlbumSort.RECENT) }
    var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }
    val albums by produceState<List<nl.atlas.gallery.featurealbums.AlbumSummary>?>(null, items, sort, mediaFilter) {
        value = null
        value = withContext(Dispatchers.Default) {
            val job = coroutineContext
            buildAlbumSummaries(items, sort, checkActive = { job.ensureActive() },
                mediaFilter = mediaFilter, volumeOf = { it.volumeName })
        }
    }
    AtlasAlbumsScreen(
        albums = albums.orEmpty(),
        sort = sort,
        onSort = { if (it != AlbumSort.CUSTOM) sort = it },
        onOpenAlbum = onOpen,
        mediaFilter = mediaFilter,
        onMediaFilterChange = { mediaFilter = it },
        isLoading = albums == null,
        coverRenderer = { record, modifier -> MediaPreview(record, modifier, ContentScale.Crop, 512) }
    )
}

@Composable
private fun PickerGrid(
    items: List<MediaRecord>,
    route: AlbumRoute?,
    epoch: Long,
    chosen: List<String>,
    allowMultiple: Boolean,
    onToggle: (MediaRecord) -> Unit,
    onReplace: (Set<String>, List<String>) -> Unit,
    onAlbumBack: () -> Unit
) {
    val shown by produceState(if (route == null) items else null, items, route) {
        value = if (route == null) items else withContext(Dispatchers.Default) {
            items.filter { matchesAlbumRoute(it, route) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (route != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onAlbumBack, modifier = Modifier.testTag(ExternalPickerTags.ALBUM_BACK)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to albums")
                }
                Text(route.relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Album" },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val list = shown
        if (list == null) {
            AtlasGalleryLoading(bottomInset = 0.dp)
        } else {
            val gridState = rememberAtlasGridState()
            val listUris = remember(list) { list.map { it.uri } }
            AtlasTimelineGallery(
                items = list,
                sort = TimelineSort.NEWEST,
                gridState = gridState,
                onOpen = onToggle,
                viewKey = "picker",
                catalogueEpoch = epoch,
                selectedMediaUris = chosen.toSet(),
                onMediaToggle = onToggle,
                onSelectionChange = if (allowMultiple) ({ set -> onReplace(set, listUris) }) else null,
                mediaSort = nl.atlas.gallery.MediaSort.default
            )
        }
    }
}

@Composable
private fun PickerActions(doneLabel: String, doneEnabled: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)
            .testTag(ExternalPickerTags.CANCEL)) { Text("Cancel") }
        Button(onClick = onDone, enabled = doneEnabled, modifier = Modifier.heightIn(min = 48.dp)
            .testTag(ExternalPickerTags.DONE)) { Text(doneLabel) }
    }
}
