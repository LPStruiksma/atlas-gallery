package nl.atlas.gallery

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featuremedia.microThumbnailMemoryKey
import androidx.compose.ui.graphics.asImageBitmap
import nl.atlas.gallery.featuremedia.MicroThumbnailWarmer
import nl.atlas.gallery.featuremedia.MicroThumbnailWarmScheduler
import nl.atlas.gallery.featuremedia.microWarmForCatalog
import nl.atlas.gallery.featuremedia.microWarmCharging
import nl.atlas.gallery.featuremedia.microThumbnailDiskKey
import nl.atlas.gallery.featuremedia.ThumbnailPipeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.window.Dialog
import java.time.ZoneId
import java.util.UUID
import nl.atlas.gallery.featurealbums.AlbumBucketKey
import nl.atlas.gallery.featurealbums.AlbumCoverPageSession
import nl.atlas.gallery.featurealbums.AlbumCoverPageCursor
import nl.atlas.gallery.featurealbums.AlbumCoverChoiceProjection
import nl.atlas.gallery.featurealbums.AlbumCoverPreferences
import nl.atlas.gallery.featurealbums.AtlasAlbumCoverChooser
import nl.atlas.gallery.featurealbums.buildAlbumCoverChoices
import nl.atlas.gallery.featurealbums.currentAlbumCoverChoicesOrNull
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AlbumLoadIssue
import nl.atlas.gallery.featurealbums.AlbumRoute
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumProjectionSnapshot
import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featurealbums.customAlbumOrder
import nl.atlas.gallery.featurealbums.albumDisplayName
import nl.atlas.gallery.featurealbums.currentAlbumProjectionOrNull
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featurealbums.AlbumPickerScreen
import nl.atlas.gallery.featurealbums.HiddenAlbums
import nl.atlas.gallery.featurealbums.HideAlbumsScreen
import nl.atlas.gallery.featurestories.AtlasStoriesScreen
import nl.atlas.gallery.featurestories.rememberStoryPlaybackState
import nl.atlas.gallery.featurecuration.CurationReviewRequest
import nl.atlas.gallery.featurealbums.buildAlbumSummaries
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.LocalAtlasTabBackdrop
import nl.atlas.gallery.featuredesign.rememberAtlasTabBackdropState
import nl.atlas.gallery.featuredesign.atlasCaptureTabBackdrop
import nl.atlas.gallery.featuredesign.LocalAtlasBottomChrome
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasDarkRoute
import nl.atlas.gallery.featuredesign.AtlasPageColor
import nl.atlas.gallery.featuredesign.AtlasSystemBarIcons
import nl.atlas.gallery.featuredesign.LocalAtlasDark
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.ReadOnlyComposable
import nl.atlas.gallery.featuregrid.AtlasFeatureGrid
import nl.atlas.gallery.featuregrid.AtlasTimelineGallery
import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.TimelineDisplayedSlots
import nl.atlas.gallery.featuregrid.TimelineMedia
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.buildGridTimelineIndex
import nl.atlas.gallery.featuregrid.AtlasRatioGallery
import nl.atlas.gallery.featuregrid.GalleryZoomStop
import nl.atlas.gallery.featuregrid.isCurrentFor
import nl.atlas.gallery.featureorganization.SamsungSelectionBottomBar
import nl.atlas.gallery.featureorganization.AlbumTransferMode
import nl.atlas.gallery.featureorganization.AlbumTransferPlan
import nl.atlas.gallery.featureorganization.AlbumTransferResult
import nl.atlas.gallery.featureorganization.AlbumTransferTarget
import nl.atlas.gallery.featureorganization.MediaStoreAlbumTransfer
import nl.atlas.gallery.featureorganization.SelectionMoreAction
import nl.atlas.gallery.featureorganization.albumNameProblem
import nl.atlas.gallery.featureorganization.albumNameProblemText
import nl.atlas.gallery.featureorganization.albumTransferMessage
import nl.atlas.gallery.featureorganization.newAlbumRelativePath
import nl.atlas.gallery.featureorganization.planAlbumTransfer
import nl.atlas.gallery.featureorganization.SamsungSelectionTopBar
import nl.atlas.gallery.featureorganization.toggleAll
import nl.atlas.gallery.featureorganization.GalleryBatchSelection
import nl.atlas.gallery.featureorganization.MAX_BATCH_SHARE_ITEMS
import nl.atlas.gallery.featureorganization.MAX_BATCH_TRASH_ITEMS
import nl.atlas.gallery.featureorganization.resolveBatchSelection
import nl.atlas.gallery.featuregrid.RatioLayout
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleActionAvailability
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleProfileUi
import nl.atlas.gallery.featurepeopleui.ReviewedPeopleProjection
import nl.atlas.gallery.featurepeopleui.buildReviewedPeopleProjection
import nl.atlas.gallery.featurepeopleui.currentReviewedPeopleProjectionOrNull
import nl.atlas.gallery.featuresearch.LocalSearchControls
import nl.atlas.gallery.featuresearch.face.FaceSearchRoute
import nl.atlas.gallery.featuresearch.face.FaceSearchSession
import nl.atlas.gallery.featuresearch.face.FaceSearchState
import nl.atlas.gallery.featuresearch.face.FaceSearchStep
import nl.atlas.gallery.featuresearch.visual.AppFindSimilarPort
import nl.atlas.gallery.featuresearch.visual.FindSimilarReference
import nl.atlas.gallery.featuresearch.visual.FindSimilarRoute
import nl.atlas.gallery.featuresearch.visual.FindSimilarSession
import nl.atlas.gallery.featuresearch.visual.FindSimilarState
import nl.atlas.gallery.featuresearch.visual.FindSimilarStep
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featuresearch.GrantScopedSearchSession
import nl.atlas.gallery.featuresearch.LocalSearchFacets
import nl.atlas.gallery.featuresearch.SearchMediaKind
import nl.atlas.gallery.featuresearch.EphemeralSearchHistory
import nl.atlas.gallery.featuresearch.FacetedSearchSnapshot
import nl.atlas.gallery.featuresearch.currentFacetedSearchOrNull
import nl.atlas.gallery.featuresearch.searchAuthorizedSnapshot
import nl.atlas.gallery.featuresearch.SearchSuggestionKind
import nl.atlas.gallery.featuresearch.SearchSuggestionSource
import nl.atlas.gallery.featuresearch.planLocalSearchSuggestions
import nl.atlas.gallery.featuresearch.SearchPerson
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeopleui.peopleHomeAlbumsForGrant
import nl.atlas.gallery.featurepeopleui.ReviewReferenceKey
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/**
 * The app root background (status bar, Photos, People, person page): the
 * theme's page colour, #0B0D10 in dark and white in light (#662).
 */
internal val DeepBg: Color
    @Composable @ReadOnlyComposable get() = AtlasPageColor
private val Surface: Color
    // #662: unchanged #171B21 in dark; the theme's container colour in light.
    @Composable @ReadOnlyComposable get() =
        if (LocalAtlasDark.current) Color(0xFF171B21) else MaterialTheme.colorScheme.surfaceContainer
private val Mint: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalAtlasDark.current) Color(0xFFB5E7DA) else MaterialTheme.colorScheme.primary

/**
 * VS-S2b (#468): the published visual index of the live face scope
 * [faceEpoch], for Find similar; null while the "Visual search index" opt-in
 * is off or not yet published.
 */
internal fun visualSearchSource(host: PeopleDiscoveryHost, faceEpoch: String): VisualVectorSource? =
    host.visualSearchSource(faceEpoch)

/** Scope version travels with every grid, filmstrip, viewer and People preview. */
internal val LocalGalleryPublicationEpoch = compositionLocalOf { -1L }

/** #555: the micro-thumbnail warmer starts this long after a catalogue is published. */
private const val MICRO_WARM_START_DELAY_MS = 2_000L

/**
 * A freshly emitted gallery/People list is an inventory generation. Compose's
 * default collectAsState uses structural equality and can scan 150k media
 * records on the UI thread. StateFlow already handles its own conflation;
 * only avoid repeating that O(N) list comparison during UI publication.
 */
@Composable
internal fun <T : Any> StateFlow<T>.collectAsIdentityState(): State<T> {
    val flow = this
    val state = remember(flow) {
        mutableStateOf(flow.value, referentialEqualityPolicy())
    }
    LaunchedEffect(flow) {
        flow.collect { next -> state.value = next }
    }
    return state
}

internal enum class PeopleCsvMode { IMPORT_ONCE, LINK_LIVE }
internal data class PendingPeopleCsv(val documentUri: String, val mode: PeopleCsvMode)

/** Explicit consent gate: SAF selection is not a destructive import approval. */
internal data class PeopleCsvReviewState(val pending: PendingPeopleCsv? = null) {
    fun selected(documentUri: String, mode: PeopleCsvMode): PeopleCsvReviewState {
        require(documentUri.startsWith("content://") && documentUri.length > "content://".length) {
            "Choose a content document using Android's document picker"
        }
        return copy(pending = PendingPeopleCsv(documentUri, mode))
    }
    /** Caller MUST install the returned cleared state before executing the approval. */
    fun decide(approved: Boolean): Pair<PeopleCsvReviewState, PendingPeopleCsv?> =
        PeopleCsvReviewState() to pending.takeIf { approved }
}

/**
 * Isolated production Compose consent surface, independently testable without
 * a People database, SAF provider, MediaStore or real private photos.
 */
@Composable
internal fun PeopleCsvApprovalDialog(
    pending: PendingPeopleCsv,
    existingProfiles: Int,
    onCancel: () -> Unit,
    onApprove: () -> Unit
) {
    val linking = pending.mode == PeopleCsvMode.LINK_LIVE
    // A double-tap delivered before recomposition must never approve twice.
    var consumed by remember(pending) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            if (!consumed) {
                consumed = true
                onCancel()
            }
        },
        title = { Text("Replace ATLAS People assignments?") },
        text = {
            Text(
                "This replaces every locally saved confirmed face assignment " +
                    "in ATLAS (currently ${existingProfiles} named People profiles) with " +
                    "the selected V4 CSV. Previously imported assignments are not " +
                    "automatically recoverable. Verify your People/settings backup first. " +
                    "The original V4 database and photos will not be changed.\n\n" +
                    if (linking)
                        "Live link: future app resumes may replace ATLAS assignments " +
                            "again when the linked CSV changes."
                    else
                        "One-time import: the existing automatic V4 link will be disabled."
            )
        },
        confirmButton = {
            TextButton(
                enabled = !consumed,
                onClick = {
                    if (!consumed) {
                        consumed = true
                        onApprove()
                    }
                },
                modifier = Modifier.testTag("atlas-people-csv-confirm")
            ) { Text(if (linking) "Replace and link live" else "Replace once") }
        },
        dismissButton = {
            TextButton(
                enabled = !consumed,
                onClick = {
                    if (!consumed) {
                        consumed = true
                        onCancel()
                    }
                },
                modifier = Modifier.testTag("atlas-people-csv-cancel")
            ) { Text("Cancel · keep People") }
        }
    )
}

/**
 * The app's system bars: the page colour behind them with icons that read on
 * it (#584, #662: light icons on dark, dark icons on light). Shared with
 * emulator evidence so a route rendered on its own shows the same legible
 * status bar as in the app.
 */
internal fun applyAtlasSystemBars(window: android.view.Window, dark: Boolean = true) {
    val page = if (dark) android.graphics.Color.rgb(11, 13, 16) else android.graphics.Color.WHITE
    window.statusBarColor = page
    window.navigationBarColor = page
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

/** Whether the phone is set to dark (#662: ATLAS follows it). */
internal fun android.content.res.Configuration.isNightMode(): Boolean =
    (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAtlasSystemBars(window, resources.configuration.isNightMode())
        setContent {
            val galleryVm: GalleryViewModel = viewModel()
            val inventory by galleryVm.publishedInventory.collectAsIdentityState()
            CompositionLocalProvider(LocalGalleryPublicationEpoch provides inventory.epoch) {
                val dark = isSystemInDarkTheme()
                AtlasSystemBarIcons(dark)
                AtlasGalleryTheme(dark = dark) {
                    // Root background + readable content colour for EVERY route,
                    // including full-screen ones drawn outside the Scaffold
                    // (viewer, cover chooser, Declutter, loading text).
                    Surface(Modifier.fillMaxSize(), color = DeepBg,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        AtlasApp(galleryVm)
                    }
                }
            }
        }
    }
}

private fun necessaryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun readGalleryMediaGrant(context: android.content.Context): GalleryMediaGrant {
    fun granted(name: String) =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> GalleryMediaGrant(
            granted(Manifest.permission.READ_MEDIA_IMAGES),
            granted(Manifest.permission.READ_MEDIA_VIDEO),
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        Build.VERSION.SDK_INT >= 33 -> GalleryMediaGrant(
            granted(Manifest.permission.READ_MEDIA_IMAGES),
            granted(Manifest.permission.READ_MEDIA_VIDEO),
            false
        )
        else -> {
            val storage = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            GalleryMediaGrant(storage, storage, false)
        }
    }
}

/**
 * Large-library sorting happens on Dispatchers.Default, never while Compose
 * draws a frame. The producer's coroutine is cancelled when MediaStore's
 * source generation, sort mode or random seed changes. Consumers must still
 * validate the snapshot to avoid one frame of stale results during relaunch.
 */
@Composable
internal fun rememberAsyncSortedCatalog(
    media: List<MediaRecord>, order: SortOrder, seed: Long
): CatalogSortSnapshot? {
    val result by produceState<CatalogSortSnapshot?>(
        initialValue = null, key1 = CatalogSourceKey(media), key2 = order, key3 = seed
    ) {
        value = null
        value = withContext(Dispatchers.Default) {
            val sortContext = currentCoroutineContext()
            CatalogSortSnapshot(media, order, seed,
                sortMedia(media, order, seed) { sortContext.ensureActive() })
        }
    }
    return result
}

/** Logcat tag for #568 Copy/Move: stages and exception class names, never paths. */
internal const val TRANSFER_LOG = "ATLAS_TRANSFER"

/**
 * #568: Photos without hidden albums. Nothing hidden is the same list at
 * zero cost; otherwise one cancellable pass on the Default pool, published
 * only for this exact source list and hidden set (null while it runs).
 */
@Composable
internal fun rememberAsyncHiddenAlbumFilter(
    sorted: List<MediaRecord>, hidden: Set<AlbumBucketKey>
): List<MediaRecord>? {
    if (hidden.isEmpty()) return sorted
    val result by produceState<Pair<List<MediaRecord>, List<MediaRecord>>?>(
        initialValue = null, key1 = CatalogSourceKey(sorted), key2 = hidden
    ) {
        value = null
        value = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            sorted to HiddenAlbums.filterPhotos(sorted, hidden) { worker.ensureActive() }
        }
    }
    return result?.takeIf { it.first === sorted }?.second
}

/**
 * Large MediaStore inventories can include 150k+ rows. Do not scan and sort
 * album paths inside Compose's frame/recomposition; always publish a complete
 * sorted snapshot from Dispatchers.Default, keyed by source reference identity.
 */
@Composable
internal fun rememberAsyncAlbumCatalog(media: List<MediaRecord>): AlbumCatalogSnapshot? {
    val result by produceState<AlbumCatalogSnapshot?>(
        initialValue = null, key1 = CatalogSourceKey(media)
    ) {
        value = null
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            AlbumCatalogSnapshot(media, albumPaths(media) { context.ensureActive() })
        }
    }
    return result
}

/**
 * Selecting a physical album previously filtered all 150k media records
 * synchronously on the Compose UI thread. Keep All at zero-copy latency, but
 * evaluate exact selected-folder membership on the cancellable Default pool.
 */
@Composable
internal fun rememberAsyncAlbumSelection(
    sorted: List<MediaRecord>, album: String?
): AlbumSelectionSnapshot? {
    val result by produceState<AlbumSelectionSnapshot?>(
        initialValue = null, key1 = CatalogSourceKey(sorted), key2 = album
    ) {
        value = null
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            AlbumSelectionSnapshot(sorted, album,
                selectAlbum(sorted, album) { context.ensureActive() })
        }
    }
    return result
}

private data class PersonAlbumRequest(
    val personId: Long?, val blackAndWhiteFolderOnly: Boolean
)

/**
 * Confirmed People virtual albums are exact read-only MediaStore projections.
 * Filtering up to 150k names/paths never blocks Compose touch animations,
 * and generation/request guards reject obsolete People imports or B&W toggles.
 */
@Composable
internal fun rememberAsyncPersonAlbum(
    sorted: List<MediaRecord>, people: List<PersonEntry>,
    personId: Long?, blackAndWhiteFolderOnly: Boolean
): PersonAlbumSnapshot? {
    val result by produceState<PersonAlbumSnapshot?>(
        initialValue = null,
        key1 = CatalogSourceKey(sorted),
        key2 = CatalogSourceKey(people),
        key3 = PersonAlbumRequest(personId, blackAndWhiteFolderOnly)
    ) {
        value = null
        if (personId != null) {
            value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                val paths = people.find { it.id == personId }?.imagePaths.orEmpty()
                context.ensureActive()
                val verified = selectAuthorizedReviewedPersonAlbum(
                    sorted, paths, blackAndWhiteFolderOnly,
                    cancellationCheckpoint = { context.ensureActive() })
                PersonAlbumSnapshot(sorted, people, personId, blackAndWhiteFolderOnly, verified)
            }
        }
    }
    return result
}

/**
 * Compute named-People covers and exact accessible-photo counts off main.
 * The worker stores ONLY explicitly confirmed paths rather than indexing
 * every MediaStore record. Source reference identity prevents stale covers
 * after photo permission or imported People changes.
 */
@Composable
internal fun rememberAsyncPeopleCovers(
    media: List<MediaRecord>, people: List<PersonEntry>
): PeopleCoversSnapshot? {
    val state by produceState<PeopleCoversSnapshot?>(
        initialValue = null,
        key1 = CatalogSourceKey(media),
        key2 = CatalogSourceKey(people)
    ) {
        value = null
        if (people.isNotEmpty()) {
            value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                buildPeopleCovers(media, people) { context.ensureActive() }
            }
        }
    }
    return state
}

private class SafeReviewedPeopleCards(
    val projection: ReviewedPeopleProjection,
    val authorizedCoversByUri: Map<String, MediaRecord>
)

@Composable
private fun rememberAsyncReviewedPeopleCards(
    media: List<MediaRecord>, people: List<PersonEntry>,
    permissionEpoch: String, active: Boolean
): SafeReviewedPeopleCards? {
    var completed by remember {
        mutableStateOf<SafeReviewedPeopleCards?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(media), CatalogSourceKey(people), permissionEpoch, active) {
        completed = null
        if (!active || people.isEmpty()) return@LaunchedEffect
        val job = currentCoroutineContext()
        val safe = withContext(Dispatchers.Default) {
            val projection = buildReviewedPeopleProjection(media, people, permissionEpoch,
                isAuthorizedPrimaryImage = ::isAuthorizedReviewedPrimaryImage,
                cancellationCheckpoint = { job.ensureActive() })
            val wanted = projection.profiles.mapNotNull { it.coverUri }.toSet()
            val covers = HashMap<String, MediaRecord>(wanted.size)
            media.forEachIndexed { i, record ->
                if ((i and 511) == 0) job.ensureActive()
                if (record.uri in wanted && isAuthorizedReviewedPrimaryImage(record)) {
                    covers[record.uri] = record
                }
            }
            job.ensureActive()
            SafeReviewedPeopleCards(projection, covers)
        }
        job.ensureActive()
        completed = safe
    }
    return completed?.takeIf { active &&
        currentReviewedPeopleProjectionOrNull(it.projection, media, people,
            permissionEpoch) != null
    }
}

private data class SearchRequest(val query: String, val enabled: Boolean)

/**
 * Search 150k metadata rows without blocking touch/keyboard frames.
 * Source and confirmed-People generation keys use reference identity:
 * a fresh MediaStore/People list with equal values still triggers a new scan.
 * The caller validates result provenance before displaying anything.
 */
@Composable
internal fun rememberAsyncGallerySearch(
    media: List<MediaRecord>, people: List<PersonEntry>,
    query: String, enabled: Boolean
): CatalogSearchSnapshot? {
    val snapshot by produceState<CatalogSearchSnapshot?>(
        initialValue = null,
        key1 = CatalogSourceKey(media),
        key2 = CatalogSourceKey(people),
        key3 = SearchRequest(query, enabled)
    ) {
        value = null
        if (enabled && query.isNotBlank()) {
            value = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                CatalogSearchSnapshot(media, people, query,
                    searchCatalog(media, people, query) { context.ensureActive() })
            }
        }
    }
    return snapshot
}

/** Searches never publish results from an older Android grant or People list. */
@Composable
private fun rememberAsyncFacetedSearch(
    media: List<MediaRecord>, people: List<PersonEntry>,
    filters: LocalSearchFacets, permissionEpoch: String, active: Boolean,
    /** #554: picked native people's URIs; null while they are still resolving. */
    nativePersonUris: Map<String, Set<String>>? = emptyMap()
): FacetedSearchSnapshot? {
    var current by remember {
        mutableStateOf<FacetedSearchSnapshot?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(media), CatalogSourceKey(people),
        filters, permissionEpoch, active, nativePersonUris?.let(::CatalogSourceKey)) {
        current = null
        if (active && nativePersonUris != null) {
            val job = currentCoroutineContext()
            val result = withContext(Dispatchers.Default) {
                searchAuthorizedSnapshot(media, people, filters, permissionEpoch,
                    faceIndexGeneration = null,
                    mediaVolumeOf = { it.volumeName },
                    cancellationCheckpoint = { job.ensureActive() },
                    nativePersonUris = nativePersonUris)
            }
            job.ensureActive()
            current = result
        }
    }
    return current?.takeIf {
        it.nativePersonUris === nativePersonUris &&
        currentFacetedSearchOrNull(it, media, people, filters,
            permissionEpoch, faceIndexGeneration = null) != null
    }
}

/**
 * #554: each picked native person's photo URIs under the live grant, read on
 * IO. Empty map when nobody is picked; null while resolving. A person whose
 * album cannot be read (scope gone) is left out, so the search fails closed.
 */
@Composable
private fun rememberNativePersonUris(
    host: PeopleDiscoveryHost, galleryGrant: String?, keys: Set<String>,
    people: PeopleHomeSummary?
): Map<String, Set<String>>? {
    if (keys.isEmpty()) return remember { emptyMap() }
    val resolved by produceState<Pair<Set<String>, Map<String, Set<String>>>?>(
        null, galleryGrant, keys, ReviewReferenceKey(people)
    ) {
        value = null
        value = keys to withContext(Dispatchers.IO) {
            buildMap {
                keys.forEach { key ->
                    host.personAlbumUris(galleryGrant, PersonKey(key))?.let { put(key, it) }
                }
            }
        }
    }
    return resolved?.takeIf { it.first == keys }?.second
}

/** Exact, testable route matching; never substitute a filename or folder display name. */
internal fun matchesAlbumRoute(record: MediaRecord, route: AlbumRoute): Boolean =
    record.volumeName == route.volumeName &&
        record.relativePath == route.relativePath &&
        route.mediaFilter.accepts(record)

/** Explicit scope prevents a same-named folder on another volume from leaking into Albums. */
private class RoutedAlbumSnapshot(
    val source: List<MediaRecord>, val route: AlbumRoute, val selected: List<MediaRecord>
)

@Composable
private fun rememberAsyncRoutedAlbumSelection(
    source: List<MediaRecord>, route: AlbumRoute?
): RoutedAlbumSnapshot? {
    var completed by remember {
        mutableStateOf<RoutedAlbumSnapshot?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(source), route) {
        completed = null
        if (route != null) {
            val job = currentCoroutineContext()
            val selected = withContext(Dispatchers.Default) {
                source.filterIndexed { index, item ->
                    if ((index and 511) == 0) job.ensureActive()
                    matchesAlbumRoute(item, route)
                }.also { job.ensureActive() }
            }
            job.ensureActive()
            completed = RoutedAlbumSnapshot(source, route, selected)
        }
    }
    return completed?.takeIf { it.source === source && it.route == route }
}

/** A small, completed Search display derived from exact authorized Album summaries. */
private class SearchChoicesSnapshot(
    val albums: List<AlbumSummary>, val people: List<PersonEntry>,
    val text: String, val accessEpoch: Long,
    val choices: List<SearchSuggestionSource>
)

/**
 * Reuse the off-thread Albums projection, and never perform 150k grouping or
 * name matching on a Compose frame or every IME keystroke.
 */
@Composable
private fun rememberAsyncSearchChoices(
    albums: List<AlbumSummary>, people: List<PersonEntry>,
    text: String, accessEpoch: Long, active: Boolean
): SearchChoicesSnapshot? {
    var completed by remember {
        mutableStateOf<SearchChoicesSnapshot?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(albums), CatalogSourceKey(people),
        text, accessEpoch, active) {
        completed = null
        if (!active || text.isBlank()) return@LaunchedEffect
        val job = currentCoroutineContext()
        val choices = withContext(Dispatchers.Default) {
            buildAuthorizedSearchChoiceSources(albums, people, text) {
                job.ensureActive()
            }
        }
        job.ensureActive()
        completed = SearchChoicesSnapshot(albums, people, text, accessEpoch, choices)
    }
    return completed?.takeIf {
        active && it.albums === albums && it.people === people &&
            it.text == text && it.accessEpoch == accessEpoch
    }
}

/** Off-main Albums projection; source *and* grant publication epoch are authority. */
@Composable
private fun rememberAsyncAlbumCards(
    source: List<MediaRecord>, sort: AlbumSort, mediaFilter: AlbumMediaFilter,
    accessEpoch: Long, enabled: Boolean,
    retryEpoch: Int = 0, failureState: MutableState<Boolean>? = null,
    coverPreferences: AlbumCoverPreferences? = null,
    customOrder: List<AlbumBucketKey> = emptyList(),
    customOrderRevision: Long = 0L
): AlbumProjectionSnapshot? {
    val validPreferences = coverPreferences?.currentOrNull(source, accessEpoch)
    val coverRevision = validPreferences?.revision ?: 0L
    val preferredCoverByBucket = validPreferences?.preferredCoverByBucket.orEmpty()
    var completed by remember {
        mutableStateOf<AlbumProjectionSnapshot?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(source), sort, mediaFilter,
        accessEpoch, enabled, retryEpoch, coverRevision, customOrderRevision) {
        failureState?.value = false
        val reusable = completed?.takeIf {
            currentAlbumProjectionOrNull(it, source, sort, mediaFilter,
                coverRevision = coverRevision, accessEpoch = accessEpoch,
                customOrderRevision = customOrderRevision) != null
        }
        if (!enabled) {
            // Back from a selected album can reuse its exact, unchanged source;
            // revocation changes accessEpoch and discards the old projection.
            if (reusable == null) completed = null
            return@LaunchedEffect
        }
        if (reusable != null) return@LaunchedEffect
        completed = null
        val job = currentCoroutineContext()
        try {
            val cards = withContext(Dispatchers.Default) {
                buildAlbumSummaries(source, sort, checkActive = { job.ensureActive() },
                    mediaFilter = mediaFilter, volumeOf = { it.volumeName },
                    preferredCoverByBucket = preferredCoverByBucket,
                    customOrder = customOrder)
            }
            job.ensureActive()
            completed = AlbumProjectionSnapshot(source, sort, mediaFilter,
                coverRevision = coverRevision, albums = cards, accessEpoch = accessEpoch,
                customOrderRevision = customOrderRevision)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled // A superseding grant never publishes an old result.
        } catch (_: Exception) {
            // Do not leave a spinner forever on an unexpected catalogue failure.
            // UI exposes an explicit retry, not an invented empty archive.
            job.ensureActive()
            failureState?.value = true
        }
    }
    return completed?.takeIf { enabled &&
        currentAlbumProjectionOrNull(it, source, sort, mediaFilter,
            coverRevision = coverRevision, accessEpoch = accessEpoch,
            customOrderRevision = customOrderRevision) != null
    }
}

/**
 * Explicit picker work stays off the UI thread and is cancelled on every
 * source/permission epoch/route/page change. Previously completed pages are
 * never admitted for a different source or cursor.
 */
private class AlbumCoverPageAttempt(
    val source: List<MediaRecord>,
    val epoch: Long,
    val route: AlbumRoute,
    val cursor: AlbumCoverPageCursor?,
    val projection: AlbumCoverChoiceProjection?,
    val failed: Boolean
)

/** An actual provider failure must never look like an endless loading spinner. */
@Composable
private fun rememberAsyncAlbumCoverPage(
    source: List<MediaRecord>, epoch: Long, route: AlbumRoute?,
    cursor: AlbumCoverPageCursor?
): AlbumCoverPageAttempt? {
    var completed by remember {
        mutableStateOf<AlbumCoverPageAttempt?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(CatalogSourceKey(source), epoch, route, cursor) {
        completed = null
        if (route != null && epoch >= 0L) {
            val worker = currentCoroutineContext()
            try {
                val ready = withContext(Dispatchers.Default) {
                    buildAlbumCoverChoices(source, route, epoch,
                        checkActive = { worker.ensureActive() }, olderThan = cursor)
                }
                worker.ensureActive()
                completed = AlbumCoverPageAttempt(
                    source, epoch, route, cursor, ready, failed = false)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                worker.ensureActive()
                completed = AlbumCoverPageAttempt(
                    source, epoch, route, cursor, null, failed = true)
            }
        }
    }
    return if (route == null) null else completed?.takeIf {
        it.source === source && it.epoch == epoch &&
            it.route == route && it.cursor == cursor &&
            (it.failed || currentAlbumCoverChoicesOrNull(
                it.projection, source, epoch, route, cursor) != null)
    }
}

@Composable
private fun AtlasApp(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    // The source list and successful grant epoch MUST be collected together.
    // Collecting two StateFlows separately can expose an obsolete album for
    // a frame after the grant-completion signal arrives ahead of its list.
    val publishedGallery by vm.publishedInventory.collectAsIdentityState()
    val people by vm.people.collectAsIdentityState()
    val v4SyncState by vm.v4SyncState.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var grants by remember { mutableStateOf(readGalleryMediaGrant(context)) }
    val access = grants.hasAny
    // Activity recreation can reuse a ViewModel with a broader prior album.
    // Start restricted scopes gated even BEFORE ON_RESUME is delivered.
    var awaitingScopedRefresh by remember {
        mutableLongStateOf(initialRestrictedMediaRefreshTicket(grants))
    }
    // Never equate "a query is pending" with "the query belongs to the
    // latest Android grant." A retained ViewModel may be scanning scope A
    // while an Activity recreated from Settings already has scope B.
    var pendingScopedGrant by remember { mutableStateOf<GalleryMediaGrant?>(null) }
    fun requestScopedRefresh(grant: GalleryMediaGrant) {
        // Pair ticket and grant in one main-thread callback without suspend.
        val ticket = vm.refreshForAccessChange()
        pendingScopedGrant = grant
        awaitingScopedRefresh = ticket
    }
    fun adoptScopedRefresh(ticket: Long?, grant: GalleryMediaGrant) {
        if (ticket != null) {
            pendingScopedGrant = grant
            awaitingScopedRefresh = ticket
        }
    }
    // This is the ONLY catalog passed to async sorts/search/album/People-cover
    // preparation. #505: the last published (or cached) catalogue stays on
    // screen while a background scan refreshes it; only full access loss
    // empties it. Android enforces what can actually be read.
    val media = publishedGallery.displayItems(grants)
    // #555: ATLAS keeps its own micro thumbnails only under full media access
    // (they are deleted otherwise), and fills them newest-first in the
    // background for the current authorized catalogue, never a cached one.
    LaunchedEffect(publishedGallery.epoch, grants) {
        MicroThumbnails.applyGrant(grants.images, grants.videos, grants.selected)
        if (ThumbnailPipeline.autoWarm && media.isNotEmpty() && !publishedGallery.fromCache &&
            MicroThumbnails.active
        ) {
            delay(MICRO_WARM_START_DELAY_MS) // first screen and its tiles first
            val store = MicroThumbnails.store ?: return@LaunchedEffect
            // The scan, including every disk existence check, happens off the UI thread.
            // LaunchedEffect cancels stale epochs; the live grant is checked again after
            // the scan and after any warm, before changing deferred work.
            val authorized = {
                MicroThumbnails.active && ThumbnailPipeline.autoWarm &&
                    readGalleryMediaGrant(context) == grants
            }
            microWarmForCatalog(
                items = media,
                charging = microWarmCharging(context),
                isMissing = { item ->
                    microThumbnailDiskKey(item)?.let { !store.contains(it) } ?: false
                },
                authorized = authorized,
                warm = { limit ->
                    MicroThumbnailWarmer.warm(context.contentResolver, media,
                        limit = limit, gate = authorized)
                },
                deferRemainder = { MicroThumbnailWarmScheduler.enqueue(context) },
                cancelDeferred = { MicroThumbnailWarmScheduler.cancel(context) }
            )
        }
    }

    val failedInventoryEpoch by vm.failedInventoryEpoch.collectAsState()
    // Primary destinations: Photos=0, Albums=1, People=2, Search=3.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var statusOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = statusOpen) { statusOpen = false }
    BackHandler(enabled = settingsOpen && !statusOpen) { settingsOpen = false }
    var albumReturnToAlbums by rememberSaveable { mutableStateOf(false) }
    // E2a (#439): Stories open over the Albums tab; no new primary destination.
    var storiesOpen by rememberSaveable { mutableStateOf(false) }
    // E2b (#442): Photo DNA also opens over the Albums tab.
    var photoDnaOpen by rememberSaveable { mutableStateOf(false) }
    var photoDnaVerifying by remember { mutableStateOf(false) }
    // Exact local folder paths remain in app-private preferences; never put them
    // in a saved-instance Bundle (which Android can hand to other components).
    val albumOrderStore = remember(context) {
        context.getSharedPreferences("atlas_album_order", android.content.Context.MODE_PRIVATE)
    }
    var albumCustomOrder by remember {
        mutableStateOf(
            albumOrderStore.getString("order", null)?.split("\n")
                ?.mapNotNull { entry ->
                    val separator = entry.indexOf('\u0001')
                    if (separator <= 0 || separator == entry.lastIndex) null else
                        AlbumBucketKey(entry.substring(0, separator), entry.substring(separator + 1))
                }?.distinct() ?: emptyList()
        )
    }
    fun persistAlbumCustomOrder(order: List<AlbumBucketKey>) {
        // SharedPreferences is private to this installation. Neither the
        // album paths nor the sort inventory are copied off-device.
        albumOrderStore.edit().putString("order", order.joinToString("\n") {
            it.volumeName + '\u0001' + it.relativePath
        }).apply()
    }
    var albumOrderRevision by remember { mutableLongStateOf(0L) }
    // The chosen album sort survives restarts, as in Samsung Gallery.
    var albumSort by remember {
        mutableStateOf(albumOrderStore.getString("sort", null)
            ?.let { saved -> AlbumSort.entries.firstOrNull { it.name == saved } }
            ?: AlbumSort.RECENT)
    }
    fun persistAlbumSort(sort: AlbumSort) {
        albumOrderStore.edit().putString("sort", sort.name).apply()
    }
    // #568 Samsung hidden albums (GRID:A7): app-private, like the album order.
    var hiddenAlbums by remember {
        mutableStateOf(HiddenAlbums.decode(albumOrderStore.getString("hidden", null)))
    }
    var hideAlbumsOpen by rememberSaveable { mutableStateOf(false) }
    fun toggleHiddenAlbum(album: AlbumBucketKey) {
        hiddenAlbums = HiddenAlbums.toggled(hiddenAlbums, album)
        albumOrderStore.edit().putString("hidden", HiddenAlbums.encode(hiddenAlbums)).apply()
    }
    // #568 selection More: which picker is open, and a move waiting for consent.
    var albumPicker by remember { mutableStateOf<AlbumTransferMode?>(null) }
    var pendingMove by remember {
        mutableStateOf<AlbumTransferPlan?>(null, referentialEqualityPolicy())
    }
    var albumMediaFilter by rememberSaveable { mutableStateOf(AlbumMediaFilter.ALL) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var albumVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var albumSelectedFilter by rememberSaveable { mutableStateOf(AlbumMediaFilter.ALL) }
    val albumRoute = if (album != null && albumVolume != null)
        AlbumRoute(album!!, albumSelectedFilter, albumVolume!!) else null
    // User-selected covers are ephemeral and conservatively scoped to one
    // completed catalogue publication. Never infer grant continuity from
    // matching permission bits or a MediaStore inventory refresh.
    var coverPreferences by remember {
        mutableStateOf<AlbumCoverPreferences?>(null, referentialEqualityPolicy())
    }
    var coverSession by remember {
        mutableStateOf<AlbumCoverPageSession?>(null, referentialEqualityPolicy())
    }
    val activeCoverPreferences =
        coverPreferences?.currentOrNull(media, publishedGallery.epoch)
    LaunchedEffect(CatalogSourceKey(media), publishedGallery.epoch) {
        if (coverPreferences?.currentOrNull(media, publishedGallery.epoch) == null)
            coverPreferences = null
        if (coverSession?.let {
                it.currentOrNull(media, publishedGallery.epoch, it.route)
            } == null) coverSession = null
    }
    val currentCoverSession = coverSession?.let {
        it.currentOrNull(media, publishedGallery.epoch, it.route)
    }
    val coverPageAttempt = rememberAsyncAlbumCoverPage(
        media, publishedGallery.epoch,
        currentCoverSession?.route, currentCoverSession?.cursor
    )
    val coverPage = coverPageAttempt?.projection
    val coverPageFailed = coverPageAttempt?.failed == true
    // Each click checks the synchronous Android grant and the authoritative
    // ViewModel publication, not only the last Compose frame's remembered flags.
    fun currentCoverSourceOrNull(): List<MediaRecord>? {
        val actual = readGalleryMediaGrant(context)
        if (!actual.hasAny || actual != grants) return null
        val publishedNow = vm.publishedInventory.value
        if (publishedNow.epoch != publishedGallery.epoch ||
            !publishedNow.showable(actual)) return null
        return publishedNow.displayItems(actual).takeIf { it === media }
    }
    var sort by rememberSaveable { mutableStateOf(SortOrder.NEWEST) }
    var randomSeed by rememberSaveable { mutableLongStateOf(12345L) }
    var originalProportions by rememberSaveable { mutableStateOf(false) }
    val albumFailed = remember { mutableStateOf(false) }
    var albumRetryEpoch by remember { mutableIntStateOf(0) }
    var timelineEnabled by rememberSaveable { mutableStateOf(true) }
    // #641: Photos and an album each keep their own timeline scroll. Sharing
    // one state carried an album's end-of-scroll position into Photos after
    // Back, where the pinned header then folds its count line.
    val photosTimelineGridState = rememberLazyGridState()
    val albumTimelineGridState = rememberLazyGridState()
    val timelineGridState = if (albumRoute != null) albumTimelineGridState else photosTimelineGridState
    // Exact slots G's timeline currently displays. Its lazy keys are positional
    // (no private URIs), so layout-switch anchors resolve visible slot indices here.
    var timelineDisplayed by remember {
        mutableStateOf<TimelineDisplayedSlots?>(null, referentialEqualityPolicy())
    }
    val ratioListState = rememberLazyListState()
    var ratioCatalog by remember {
        mutableStateOf<List<MediaRecord>?>(null, referentialEqualityPolicy())
    }
    var ratioLayout by remember { mutableStateOf<RatioLayout?>(null, referentialEqualityPolicy()) }
    var ratioEpoch by remember { mutableLongStateOf(-1L) }
    var pendingRatioAnchor by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var pendingRatioReturn by remember {
        mutableStateOf<PendingViewerReturn?>(null, referentialEqualityPolicy())
    }
    // G zoom ladder: a pinch in from original rows enters the timeline at the
    // adjacent large stop once; it is cleared after the timeline adopted it so
    // a later remount restores the user's own remembered density instead.
    var timelineEntryDensity by remember { mutableStateOf<GridDensity?>(null) }
    // Return from original rows to the layout the user entered from.
    var ratioReturnToTimeline by remember { mutableStateOf(true) }
    LaunchedEffect(timelineEntryDensity) {
        if (timelineEntryDensity == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        timelineEntryDensity = null
    }
    // D batch selection: in-memory only, bound to one list reference + epoch.
    var batchSelection by remember {
        mutableStateOf<GalleryBatchSelection?>(null, referentialEqualityPolicy())
    }
    var batchOperating by remember { mutableStateOf(false) }
    // The album a Photos selection was started in; another album ends it.
    var selectionAlbumRoute by remember { mutableStateOf<AlbumRoute?>(null) }
    // Search text, selected reviewed identities and recents belong to ONE
    // permission publication AND one confirmed People generation. Neither
    // tokens nor queries are saved across process/Activity recreation.
    val searchGrant = if (access &&
        publishedGallery.showable(grants) &&
        publishedGallery.epoch >= 0L
    ) publishedGallery.epoch.toString() else null
    // Stories (#439) plan only while Albums is shown, once per grant/catalogue/day.
    // Minted in remember(): no plain field is written during composition (#451).
    val storyRevision = rememberStoryCatalogRevision(media)
    val storyTokens = storyPlanTokensOrNull(grants, publishedGallery.epoch,
        authorized = searchGrant != null,
        catalogRevision = storyRevision,
        epochDay = java.time.LocalDate.now().toEpochDay())
    val storyDeck = rememberStoryDeck(media, storyTokens, enabled = tab == 1)
    // The open slideshow lives above the viewer route (#444): Back from a
    // photo opened in a story returns to that story at the same moment, and
    // it is saved across rotation and process death (#451).
    val storyPlayback = rememberStoryPlaybackState(storyDeck)
    // #457: Stories closed by any route never resumes a pending restore later.
    ClearStoryPlaybackWhenHidden(shown = tab == 1 && storiesOpen, playback = storyPlayback)
    // Photo DNA (#442) hashes same-size files only while it is open, once per
    // grant/catalogue publication; a grant change mid-read yields Unknown.
    val photoDnaTokens = photoDnaTokensOrNull(grants, publishedGallery.epoch,
        authorized = searchGrant != null, source = media)
    val latestPhotoDnaTokens = rememberUpdatedState(photoDnaTokens)
    val photoDnaGrant = grants
    val photoDna = rememberPhotoDna(media, photoDnaTokens,
        enabled = tab == 1 && photoDnaOpen) { record, check ->
        hashAuthorizedMedia(context.contentResolver, record,
            stillAuthorized = { readGalleryMediaGrant(context) == photoDnaGrant },
            checkCancelled = check)
    }
    // O(1) reference-identity generation: even replacing confirmed People with
    // an equal-count/different-label roster creates a new anonymous token.
    val peopleGeneration = remember(CatalogSourceKey(people)) {
        UUID.randomUUID().toString()
    }
    var searchSession by remember { mutableStateOf(GrantScopedSearchSession()) }
    // Native face discovery (#433): one process-scoped host. It is bound
    // outside the People tab so that a revoked or changed grant reaches a
    // running scan even while another tab is shown. Nothing starts here.
    val discoveryHost = remember(context) { PeopleDiscoveryHost.forApp(context) }
    val discovery = rememberPeopleDiscovery(discoveryHost, searchGrant, media)
    var peopleReviewOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = tab == 2 && peopleReviewOpen) { peopleReviewOpen = false }
    // #584: People ⋮ → Hidden (Samsung). A person opened from it returns here.
    var hiddenPeopleOpen by rememberSaveable { mutableStateOf(false) }
    // #643: People maintenance tools live in Settings.
    var visualIndexOpen by remember { mutableStateOf(false) }
    var provenanceDetailsOpen by remember { mutableStateOf(false) }
    var peopleNumbersOpen by remember { mutableStateOf(false) }
    // In memory only: a person key or name never enters saved instance state.
    var nativePersonAlbum by remember { mutableStateOf<NativePersonAlbumRequest?>(null) }
    val nativePersonGridState = rememberLazyGridState()
    // An old render must NEVER expose old Person IDs/terms while LaunchedEffect
    // is still adopting the new grant or People identity.
    val currentSearchSession = searchSession.currentOrNull(searchGrant, peopleGeneration)
    val searchFacets = currentSearchSession?.facets ?: LocalSearchFacets()
    val searchHistory = currentSearchSession?.recent ?: EphemeralSearchHistory()
    // Delayed clicks/IME may originate in an older compositional frame. The
    // event carries the rendered scope; live scopes are read at invocation.
    val latestSearchGrant = rememberUpdatedState(searchGrant)
    val latestPeopleGeneration = rememberUpdatedState(peopleGeneration)
    LaunchedEffect(searchGrant, peopleGeneration) {
        searchSession = searchSession.enterScope(searchGrant, peopleGeneration)
    }
    // Search by face (VS-F2b, #468): in memory only, scoped to the live face
    // epoch AND the current gallery grant (read at every port call).
    var faceStep by remember { mutableStateOf<FaceSearchStep?>(null) }
    // The viewer list and photo "Find this person" started from; Back returns there.
    var faceOrigin by remember {
        mutableStateOf<Pair<List<MediaRecord>, String>?>(null, referentialEqualityPolicy())
    }
    val faceHostVersion by discoveryHost.changes.collectAsState()
    val latestFaceMedia = rememberUpdatedState(media)
    val facePort = remember(discoveryHost) { discoveryHost.faceSearchPort { latestSearchGrant.value } }
    val faceSession = remember(facePort) {
        FaceSearchSession(facePort, records = { uris ->
            // On IO: one pass over the permission-gated gallery list.
            latestFaceMedia.value.filter { it.uri in uris }.associateBy { it.uri }
        })
    }
    val faceState by faceSession.state.collectAsState()
    val faceResults = (faceState as? FaceSearchState.Results)
        ?.takeIf { faceStep == FaceSearchStep.Results }
    // A revoke, a new face epoch or a new grant drops results at once.
    LaunchedEffect(faceSession, searchGrant, faceHostVersion) {
        withContext(Dispatchers.IO) { faceSession.revalidate() }
    }
    fun closeFaceSearch() {
        faceStep = null
        faceOrigin = null
        faceSession.clear()
    }
    // Find similar (VS-S2b, #468): same scope rules as face search; the
    // visual index rides on the live face epoch and the current grant.
    var similarStep by remember { mutableStateOf<FindSimilarStep?>(null) }
    var similarOrigin by remember {
        mutableStateOf<Pair<List<MediaRecord>, String>?>(null, referentialEqualityPolicy())
    }
    val similarPort = remember(facePort) {
        AppFindSimilarPort(context, facePort) { faceEpoch -> visualSearchSource(discoveryHost, faceEpoch) }
    }
    val similarSession = remember(similarPort) {
        FindSimilarSession(similarPort, records = { uris ->
            latestFaceMedia.value.filter { it.uri in uris }.associateBy { it.uri }
        })
    }
    val similarState by similarSession.state.collectAsState()
    val similarResults = (similarState as? FindSimilarState.Results)
        ?.takeIf { similarStep == FindSimilarStep.Results }
    LaunchedEffect(similarSession, searchGrant, faceHostVersion) {
        withContext(Dispatchers.IO) { similarSession.revalidate() }
    }
    fun closeFindSimilar() {
        similarStep = null
        similarOrigin = null
        similarSession.clear()
    }
    // Back from Find similar returns to the photo it started from, if that
    // gallery list is still the one shown (same identity, same grant).
    var similarReturn by remember {
        mutableStateOf<Pair<List<MediaRecord>, String>?>(null, referentialEqualityPolicy())
    }
    // A V4 CSV replacement may reuse numeric Person IDs for different names.
    // Never restore an old selected Person into a newly confirmed roster.
    // In-memory per-roster state intentionally does not survive process death.
    var personFilter by remember(peopleGeneration) { mutableStateOf<Long?>(null) }
    var personBwFolderOnly by remember(peopleGeneration) { mutableStateOf(false) }
    // These are immutable gallery generations, NOT value-equal collections.
    // A newly refreshed but equal-content list must replace its predecessor
    // without 150k-item List.equals work on Compose's UI thread.
    var viewerItems by remember {
        mutableStateOf<List<MediaRecord>?>(null, referentialEqualityPolicy())
    }
    var viewerUri by remember { mutableStateOf<String?>(null) }
    var viewerAccessEpoch by remember { mutableLongStateOf(-1L) }
    var pendingViewerReturn by remember {
        mutableStateOf<PendingViewerReturn?>(null, referentialEqualityPolicy())
    }
    var notification by remember { mutableStateOf<String?>(null) }
    // Transient SAF consent only; neither URI nor intent is persisted.
    var peopleCsvReview by remember { mutableStateOf(PeopleCsvReviewState()) }
    // Only a transient deck, never persisted as an unattended delete job.
    var declutterFolder by remember { mutableStateOf<String?>(null) }
    var declutterSource by remember {
        mutableStateOf<List<MediaRecord>?>(null, referentialEqualityPolicy())
    }
    var declutterItems by remember {
        mutableStateOf<List<MediaRecord>?>(null, referentialEqualityPolicy())
    }
    var trashPreflightPending by remember { mutableStateOf(false) }
    val atlasScope = rememberCoroutineScope()
    fun closeDeclutter() {
        declutterFolder = null
        declutterItems = null
        declutterSource = null
        trashPreflightPending = false
    }
    // Distinct source objects must restart the background deck build even
    // when all MediaStore metadata happens to compare value-equal.
    LaunchedEffect(declutterFolder, declutterSource?.let(::CatalogSourceKey)) {
        val chosen = declutterFolder
        val frozenCatalog = declutterSource
        if (chosen != null && frozenCatalog != null) {
            // This folder-only size sort stays off the Compose UI thread.
            declutterItems = withContext(Dispatchers.Default) {
                folderDeclutterDeck(frozenCatalog, chosen)
            }
        }
    }
    // Each browsing context owns its scroll state: Back restores the exact prior location.
    val photosGridState = rememberLazyGridState()
    val searchGridState = rememberLazyGridState()
    val personGridState = rememberLazyGridState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val previousGrants = grants
        val currentGrants = readGalleryMediaGrant(context)
        grants = currentGrants
        if (invalidatesGallerySnapshotOnResume(previousGrants, currentGrants)) {
            viewerUri = null
            viewerItems = null
            closeDeclutter()
        }
        if (!currentGrants.hasAny) {
            // Hiding pixels alone leaves the old catalog and IO scan alive.
            pendingScopedGrant = null
            vm.revokeMediaAccess()
        } else if (requiresGalleryRescanOnResume(previousGrants, currentGrants) ||
            needsNewGrantBoundScan(
                currentGrants, vm.publishedInventory.value.scanBoundGrant, pendingScopedGrant
            )) {
            requestScopedRefresh(currentGrants)
        } else {
            // A recreated Activity may already see restored full grants,
            // while its retained ViewModel is still paused from total loss.
            val recoveryTicket = vm.restoreRevokedMediaAccessIfGranted(currentGrants)
            adoptScopedRefresh(recoveryTicket, currentGrants)
            if (recoveryTicket == null) vm.refresh()
        }
    }
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            closeDeclutter()
            // Unlike ACTION_EDIT, a successful MediaStore trash request
            // completes the operation before returning to ATLAS.
            vm.refresh()
            Toast.makeText(context, "Moved selected items to Android Trash",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Trash canceled · your photos are unchanged",
                Toast.LENGTH_SHORT).show()
        }
    }
    // Choosing a document NEVER starts a replacement. Explicit confirmation follows.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) peopleCsvReview = peopleCsvReview.selected(
            uri.toString(), PeopleCsvMode.IMPORT_ONCE)
    }
    val linkV4Launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) peopleCsvReview = peopleCsvReview.selected(
            uri.toString(), PeopleCsvMode.LINK_LIVE)
    }
    peopleCsvReview.pending?.let { pending ->
        PeopleCsvApprovalDialog(
            pending = pending,
            existingProfiles = people.size,
            onCancel = { peopleCsvReview = peopleCsvReview.decide(false).first },
            onApprove = {
                // Transient approval is consumed FIRST so no second callback
                // or stale dialog interaction can replay the import.
                val (cleared, approved) = peopleCsvReview.decide(true)
                peopleCsvReview = cleared
                if (approved == pending) {
                    val selectedUri = Uri.parse(approved.documentUri)
                    if (approved.mode == PeopleCsvMode.LINK_LIVE)
                        vm.linkV4LiveCsv(selectedUri) { notification = it }
                    else
                        vm.importV4Csv(selectedUri) { notification = it }
                }
            }
        )
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshLinkedV4Csv()
                val previousGrants = grants
                val currentGrants = readGalleryMediaGrant(context)
                grants = currentGrants
                if (invalidatesGallerySnapshotOnResume(previousGrants, currentGrants)) {
                    // MediaStore has not yet returned the newly authorized set.
                    // Never show cached viewer/deck items from the prior grant,
                    // especially when Android 14+ selective photos are re-picked.
                    viewerUri = null
                    viewerItems = null
                    closeDeclutter()
                }
                if (!currentGrants.hasAny) {
                    // Also covers retained ViewModels after Activity recreation.
                    pendingScopedGrant = null
                    vm.revokeMediaAccess()
                } else if (requiresGalleryRescanOnResume(previousGrants, currentGrants) ||
                    awaitingScopedRefresh == Long.MAX_VALUE ||
                    needsNewGrantBoundScan(
                        currentGrants, vm.publishedInventory.value.scanBoundGrant, pendingScopedGrant
                    )) {
                    requestScopedRefresh(currentGrants)
                } else {
                    val recoveryTicket = vm.restoreRevokedMediaAccessIfGranted(currentGrants)
                    adoptScopedRefresh(recoveryTicket, currentGrants)
                    if (recoveryTicket == null) vm.refreshIfChanged()
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Compose can attach its lifecycle observer AFTER the first resume.
    // Resolve the initial restricted-scope sentinel in that case rather than
    // displaying stale content or waiting for another app backgrounding.
    LaunchedEffect(lifecycle, vm) {
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (!grants.hasAny) {
                // A recreated Activity may miss its very first ON_RESUME.
                pendingScopedGrant = null
                vm.revokeMediaAccess()
            } else if (awaitingScopedRefresh == Long.MAX_VALUE ||
                needsNewGrantBoundScan(
                    grants, vm.publishedInventory.value.scanBoundGrant, pendingScopedGrant
                )) {
                // A pending old-scope ticket is NOT evidence that the current
                // full/reselected scope has been queried after recreation.
                requestScopedRefresh(grants)
            } else {
                // Compose may attach AFTER the initial ON_RESUME and the
                // Activity's initial grant flags may already be fully restored.
                adoptScopedRefresh(vm.restoreRevokedMediaAccessIfGranted(grants), grants)
            }
        }
    }

    val catalogSort = rememberAsyncSortedCatalog(media, sort, randomSeed)
    val matchingSort = currentSortedCatalogOrNull(catalogSort, media, sort, randomSeed)
    val sortPending = media.isNotEmpty() && matchingSort == null
    val sorted = matchingSort.orEmpty()
    val albumSelection = rememberAsyncRoutedAlbumSelection(sorted, albumRoute)
    // Legacy saved folder without a volume is intentionally not selected:
    // guessing a primary volume can expose a different removable volume.
    val selectedAlbum = albumSelection?.selected
    // #568: Photos leaves out hidden albums (an opened album never does).
    val photosShown = rememberAsyncHiddenAlbumFilter(sorted, hiddenAlbums)
    val albumItems = if (albumRoute == null) photosShown.orEmpty() else selectedAlbum.orEmpty()
    val albumSelectionPending =
        (albumRoute != null && sorted.isNotEmpty() && selectedAlbum == null) ||
            (albumRoute == null && sorted.isNotEmpty() && photosShown == null)
    val searchActive = personFilter == null && tab == 3 && (
        searchFacets.text.isNotBlank() ||
        searchFacets.confirmedPersonIds.isNotEmpty() ||
        searchFacets.kind != SearchMediaKind.ALL ||
        searchFacets.albumPaths.isNotEmpty() ||
        searchFacets.albumLocations.isNotEmpty() ||
        searchFacets.onlyTheseIndexedFaces ||
        searchFacets.dateWindow != null ||
        searchFacets.nativePersonKeys.isNotEmpty()
    )
    // #554: named native People for Search's name suggestions and chips.
    val nativeSearchPeople = remember(discovery.status?.people, discovery.status?.displayEpoch) {
        val status = discovery.status
        val summary = status?.people
        peopleHomeAlbumsForGrant(summary, status?.displayEpoch, summary?.sourceGeneration)
            .map { entry ->
                val cover = summary?.people?.get(entry.person)?.coverFaceUid
                    ?.let { uid -> runCatching { NativeFaceObservationId(uid) }.getOrNull() }
                SearchPerson(entry.person.value, entry.name) to cover
            }
    }
    val namedSearchPeople = remember(nativeSearchPeople) { nativeSearchPeople.map { it.first } }
    val nativeSearchUris = rememberNativePersonUris(discoveryHost, searchGrant,
        searchFacets.nativePersonKeys, discovery.status?.people)
    val searchSnapshot = rememberAsyncFacetedSearch(sorted, people, searchFacets,
        permissionEpoch = publishedGallery.epoch.toString(), active = searchActive,
        nativePersonUris = nativeSearchUris)
    val searched = searchSnapshot?.results
    val searchPending = searchActive && (sortPending || searched == null)
    val personAlbumSnapshot = rememberAsyncPersonAlbum(
        sorted, people, personFilter, personBwFolderOnly)
    val selectedPersonPhotos = currentPersonAlbumOrNull(
        personAlbumSnapshot, sorted, people, personFilter, personBwFolderOnly)
    val personAlbumPending = personFilter != null && sorted.isNotEmpty() &&
        selectedPersonPhotos == null
    // Selecting an already-built list is O(1). Do not use List-valued
    // Compose remember keys here: their structural equals() can compare
    // 150,000 MediaRecords on the UI thread after a catalogue refresh.
    val visible = when {
        // Face search results are the viewer's list while that route is open.
        faceStep == FaceSearchStep.Results -> faceResults?.records.orEmpty()
        similarStep == FindSimilarStep.Results -> similarResults?.records.orEmpty()
        personFilter != null -> selectedPersonPhotos.orEmpty()
        tab == 3 -> if (!searchActive) sorted else searched.orEmpty()
        else -> albumItems
    }
    // Revoke/reselect invalidates the ratio index synchronously by identity
    // before any previous image positions can be reused in a different scope.
    // A selection never survives a new tab, album, layout or lost access: the
    // user selects again rather than acting on items from another scope. A
    // library reload of the same view keeps it (Samsung GRID:S4): the URIs
    // still in the newly published list stay selected, the rest drop out.
    LaunchedEffect(CatalogSourceKey(visible), publishedGallery.epoch, tab,
        originalProportions, timelineEnabled, personFilter, albumRoute) {
        val selection = batchSelection ?: return@LaunchedEffect
        if (tab != 0 || originalProportions || !timelineEnabled || personFilter != null ||
            selectionAlbumRoute != albumRoute || !grants.hasAny ||
            !publishedGallery.showable(grants) || publishedGallery.epoch < 0L) {
            batchSelection = null
        } else if (selection.source !== visible || selection.epoch != publishedGallery.epoch) {
            val source = visible
            val epoch = publishedGallery.epoch
            val rebased = withContext(Dispatchers.Default) {
                val worker = currentCoroutineContext()
                selection.rebasedOnto(source, epoch) { worker.ensureActive() }
            }
            if (batchSelection === selection) batchSelection = rebased
        }
    }
    LaunchedEffect(CatalogSourceKey(visible), publishedGallery.epoch) {
        if (ratioCatalog !== visible || ratioEpoch != publishedGallery.epoch) {
            ratioLayout = null
            ratioCatalog = null
            ratioEpoch = -1L
            pendingRatioAnchor = null
        }
    }
    val albumCards = rememberAsyncAlbumCards(media, albumSort, albumMediaFilter,
        accessEpoch = publishedGallery.epoch, enabled = tab == 1,
        retryEpoch = albumRetryEpoch, failureState = albumFailed,
        coverPreferences = activeCoverPreferences, customOrder = albumCustomOrder,
        customOrderRevision = albumOrderRevision)
    val allAlbums = albumCards?.albums
    val albums = remember(allAlbums, hiddenAlbums) {
        HiddenAlbums.visibleAlbums(allAlbums.orEmpty(), hiddenAlbums)
    }
    val pickerAlbumCards = rememberAsyncAlbumCards(media, albumSort, AlbumMediaFilter.ALL,
        accessEpoch = publishedGallery.epoch, enabled = albumPicker != null,
        customOrder = albumCustomOrder, customOrderRevision = albumOrderRevision)
    val albumsPending = shouldWaitForAlbumProjection(
        albumsVisible = tab == 1,
        inventoryCount = media.size,
        hasProjection = albumCards != null,
        projectionFailed = albumFailed.value
    )
    val searchChoicesEnabled = tab == 3 && personFilter == null &&
        searchFacets.text.isNotBlank()
    val searchableAlbumCards = rememberAsyncAlbumCards(
        media, AlbumSort.NAME, AlbumMediaFilter.ALL,
        accessEpoch = publishedGallery.epoch, enabled = searchChoicesEnabled)
    val searchableAlbums = searchableAlbumCards?.albums.orEmpty()
    val searchChoices = rememberAsyncSearchChoices(
        searchableAlbums, people, searchFacets.text,
        publishedGallery.epoch, active = searchChoicesEnabled &&
            searchableAlbumCards != null)
    // For original proportions, the URI lookup is also off-main; the
    // GalleryViewer callback must never linearly scan 150k photos on touch.
    LaunchedEffect(pendingRatioReturn, viewerUri, CatalogSourceKey(visible),
        originalProportions, grants, awaitingScopedRefresh, publishedGallery.epoch) {
        val request = pendingRatioReturn ?: return@LaunchedEffect
        if (viewerUri != null) return@LaunchedEffect
        if (!originalProportions ||
            !viewerReturnMatchesCurrentRoute(request, visible, 0,
                grants.hasAny && publishedGallery.showable(grants),
                publishedGallery.epoch)) {
            pendingRatioReturn = null
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            var index = -1
            for (i in request.source.indices) {
                if ((i and 511) == 0) worker.ensureActive()
                if (request.source[i].uri == request.uri) { index = i; break }
            }
            index
        }
        if (pendingRatioReturn === request && found >= 0 &&
            request.source === visible &&
            grants.hasAny && publishedGallery.showable(grants)) {
            pendingRatioAnchor = found to request.uri
        }
        if (pendingRatioReturn === request) pendingRatioReturn = null
    }

    // The plan callback can finish before the deferred URI lookup, or after.
    // Keep both immutable prerequisites until they match the current grant.
    LaunchedEffect(pendingRatioAnchor, ratioLayout, CatalogSourceKey(visible),
        originalProportions, viewerUri, grants, awaitingScopedRefresh,
        publishedGallery.epoch) {
        val anchor = pendingRatioAnchor ?: return@LaunchedEffect
        if (viewerUri != null) return@LaunchedEffect
        if (!originalProportions || !grants.hasAny ||
            !publishedGallery.showable(grants)) {
            pendingRatioAnchor = null
            return@LaunchedEffect
        }
        val ready = ratioLayout?.takeIf {
            ratioCatalog === visible && ratioEpoch == publishedGallery.epoch
        } ?: return@LaunchedEffect
        if (ready.rows.isEmpty() || visible.getOrNull(anchor.first)?.uri != anchor.second) {
            pendingRatioAnchor = null
            return@LaunchedEffect
        }
        val target = ready.scrollTarget(anchor.first, .5f,
            ready.options.viewportHeightPx / 2f)
        withFrameNanos { }
        if (pendingRatioAnchor == anchor &&
            ratioLayout === ready && ratioCatalog === visible &&
            ratioEpoch == publishedGallery.epoch &&
            viewerUri == null && grants.hasAny &&
            publishedGallery.showable(grants)) {
            ratioListState.scrollToItem(target.firstRowIndex,
                target.firstRowScrollOffsetPx)
        }
        if (pendingRatioAnchor == anchor) pendingRatioAnchor = null
    }

    // Viewer is mounted as a full-screen route, so the grid does not exist
    // while GalleryViewer emits onExitSettledUri. Wait for the correct screen,
    // grant and actual LazyGrid item count BEFORE moving the destination.
    LaunchedEffect(pendingViewerReturn, viewerUri, CatalogSourceKey(visible),
        tab, personFilter, originalProportions, timelineEnabled, sort,
        grants, awaitingScopedRefresh, publishedGallery.epoch) {
        val request = pendingViewerReturn ?: return@LaunchedEffect
        if (viewerUri != null) return@LaunchedEffect
        val route = when {
            personFilter != null -> 2
            tab == 3 -> 1
            else -> 0
        }
        if ((route == 0 && originalProportions) ||
            !viewerReturnMatchesCurrentRoute(
                request, visible, route, grants.hasAny &&
                    publishedGallery.showable(grants),
                publishedGallery.epoch)
        ) {
            pendingViewerReturn = null
            return@LaunchedEffect
        }
        val source = request.source
        val index = withContext(Dispatchers.Default) {
            var found = -1
            for (i in source.indices) {
                if ((i and 511) == 0) currentCoroutineContext().ensureActive()
                if (source[i].uri == request.uri) { found = i; break }
            }
            found
        }
        if (index < 0) {
            pendingViewerReturn = null
            return@LaunchedEffect
        }
        if (route == 0 && timelineEnabled) {
            // G's dated LazyGrid has additional full-span HEADER slots.
            // Resolve the photo's slot using G's exact pure index off-main;
            // never scroll to the raw MediaRecord index after a dated group.
            val shared = context.getSharedPreferences(
                "atlas_featuregrid_density", android.content.Context.MODE_PRIVATE)
            val density = GridDensity.fromColumns(
                shared.getInt("photos", GridDensity.MEDIUM.columns))
            val timelineSort = when (sort) {
                SortOrder.NEWEST -> TimelineSort.NEWEST
                SortOrder.OLDEST -> TimelineSort.OLDEST
                SortOrder.LARGEST -> TimelineSort.LARGEST
                SortOrder.SMALLEST -> TimelineSort.SMALLEST
                SortOrder.RANDOM -> TimelineSort.RANDOM
            }
            val timeline = withContext(Dispatchers.Default) {
                val worker = currentCoroutineContext()
                val projection = ArrayList<TimelineMedia>(source.size)
                source.forEachIndexed { i, item ->
                    if ((i and 511) == 0) worker.ensureActive()
                    projection.add(TimelineMedia(item.uri, item.takenAtMillis))
                }
                buildGridTimelineIndex(projection, timelineSort, density,
                    ZoneId.systemDefault(), Locale.getDefault()) {
                    worker.ensureActive()
                }
            }
            val slot = timeline.entryForMedia(index)
            // The mounted count is measured from the ACTUAL G grid; the
            // model's matching count alone is never a permission check.
            val ready = withTimeoutOrNull(5000L) {
                snapshotFlow { timelineGridState.layoutInfo.totalItemsCount }
                    .first { it == timeline.slots.size && it > slot }
            } != null
            if (ready && pendingViewerReturn === request && viewerUri == null &&
                admittedTimelineReturnSlotOrNull(
                    request, visible, route,
                    authorized = grants.hasAny &&
                        publishedGallery.showable(grants),
                    mediaIndex = index, timeline = timeline,
                    mountedItemCount = timelineGridState.layoutInfo.totalItemsCount,
                    currentAccessEpoch = publishedGallery.epoch
                ) == slot
            ) timelineGridState.scrollToItem(slot)
        } else {
            val destination = when (route) {
                2 -> personGridState
                1 -> searchGridState
                else -> photosGridState
            }
            // A grid can be temporarily empty while a new permission-bound
            // projection finishes; never seek into a not-yet-mounted old list.
            val ready = withTimeoutOrNull(5000L) {
                snapshotFlow { destination.layoutInfo.totalItemsCount }
                    .first { it > index }
            } != null
            if (ready && pendingViewerReturn === request && viewerUri == null &&
                viewerReturnCanApply(request, visible, route,
                    grantAuthorized = grants.hasAny &&
                        publishedGallery.showable(grants),
                    mediaIndex = index,
                    mountedItemCount = destination.layoutInfo.totalItemsCount,
                    currentAccessEpoch = publishedGallery.epoch)
            ) destination.scrollToItem(index)
        }
        if (pendingViewerReturn === request) pendingViewerReturn = null
    }

    // Reset only when the CONTENT of a context intentionally changes, not on Back/tab switches.
    // #641: a new order (sort, seed) starts both timelines at the top; opening
    // an album starts the album's at the top; leaving it keeps Photos where it
    // was. Saved keys: a rotation or process restore keeps the positions.
    var timelineOrderKey by rememberSaveable { mutableStateOf("") }
    var timelineAlbumKey by rememberSaveable { mutableStateOf("") }
    val latestVisible by rememberUpdatedState(visible)
    LaunchedEffect(albumRoute, sort, randomSeed) {
        photosGridState.scrollToItem(0)
        val orderKey = "$sort|$randomSeed"
        val albumKey = albumRoute?.toString().orEmpty()
        val orderChanged = timelineOrderKey.isNotEmpty() && timelineOrderKey != orderKey
        val albumOpened = albumRoute != null && timelineAlbumKey != albumKey
        timelineOrderKey = orderKey
        timelineAlbumKey = albumKey
        val reset = buildList {
            if (orderChanged) add(photosTimelineGridState)
            if (orderChanged || albumOpened) add(albumTimelineGridState)
        }
        if (reset.isEmpty()) return@LaunchedEffect
        reset.forEach { it.scrollToItem(0) }
        // The new order (async sort, album selection) and its timeline plan
        // arrive later and mount a fresh grid; settle on the top again once
        // that plan is displayed for the new catalogue.
        val shown = withTimeoutOrNull(15_000L) {
            snapshotFlow { timelineDisplayed?.source?.takeIf { it === latestVisible } }
                .filterNotNull().first()
        }
        if (shown != null) {
            withFrameNanos { }
            reset.forEach { it.scrollToItem(0) }
        }
    }
    LaunchedEffect(searchFacets) { searchGridState.scrollToItem(0) }
    LaunchedEffect(personFilter, personBwFolderOnly) { personGridState.scrollToItem(0) }

    fun openViewer(items: List<MediaRecord>, uri: String) {
        viewerItems = items
        viewerAccessEpoch = publishedGallery.epoch
        viewerUri = uri
    }
    fun closeViewer() {
        viewerUri = null
        viewerItems = null
        viewerAccessEpoch = -1L
    }
    fun galleryAccessCurrent(): Boolean = grants.hasAny &&
        publishedGallery.showable(grants) && publishedGallery.epoch >= 0L
    // Batch Trash is always Android's own consent dialog (createTrashRequest);
    // ATLAS never deletes directly. Only RESULT_OK means anything changed.
    val batchTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        batchOperating = false
        if (result.resultCode == Activity.RESULT_OK) {
            batchSelection = null
            vm.refresh()
            Toast.makeText(context, "Moved selected items to Android Trash",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Trash canceled · your photos are unchanged",
                Toast.LENGTH_SHORT).show()
        }
    }
    // Batch favourite is Android's own consent dialog (createFavoriteRequest).
    val batchFavouriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        batchOperating = false
        if (result.resultCode == Activity.RESULT_OK) {
            batchSelection = null
            vm.refresh()
        }
    }
    // #568 Copy/Move to album. Each step is tried on its own; one failure
    // never stops the rest, and the toast says how many did not make it.
    suspend fun runAlbumTransfer(plan: AlbumTransferPlan): AlbumTransferResult =
        withContext(Dispatchers.IO) {
            var done = 0
            var failed = plan.otherVolume.size
            for (step in plan.steps) {
                currentCoroutineContext().ensureActive()
                val ok = try {
                    if (plan.mode == AlbumTransferMode.COPY)
                        MediaStoreAlbumTransfer.copy(context.contentResolver, step, plan.target)
                    else MediaStoreAlbumTransfer.move(context.contentResolver, step, plan.target)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    // Class name only: messages can carry private folder paths.
                    Log.w(TRANSFER_LOG, "step failed: ${failure.javaClass.simpleName}")
                    false
                }
                if (!ok) Log.i(TRANSFER_LOG, "step not done ${plan.mode.name}")
                if (ok) done++ else failed++
            }
            AlbumTransferResult(done, failed, plan.alreadyThere.size)
        }
    fun finishAlbumTransfer(plan: AlbumTransferPlan, result: AlbumTransferResult) {
        Log.i(TRANSFER_LOG, "done ${plan.mode.name} ok=${result.done} failed=${result.failed} " +
            "skipped=${result.skipped}")
        Toast.makeText(context, albumTransferMessage(plan.mode, plan.target.title, result),
            Toast.LENGTH_LONG).show()
        if (result.done > 0) {
            batchSelection = null
            vm.refresh()
        }
    }
    // Move rewrites rows ATLAS may not own: only after Android's write consent.
    val batchMoveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val plan = pendingMove
        pendingMove = null
        Log.i(TRANSFER_LOG, "consent result=${result.resultCode} plan=${plan != null}")
        if (plan == null || result.resultCode != Activity.RESULT_OK) {
            batchOperating = false
            Toast.makeText(context, "Move canceled · your photos are unchanged",
                Toast.LENGTH_SHORT).show()
        } else {
            atlasScope.launch {
                try {
                    finishAlbumTransfer(plan, runAlbumTransfer(plan))
                } finally {
                    batchOperating = false
                }
            }
        }
    }
    // Photo DNA Trash is also only Android's own consent dialog (#442).
    val photoDnaTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        photoDnaVerifying = false
        if (result.resultCode == Activity.RESULT_OK) {
            vm.refresh()
            Toast.makeText(context, "Moved selected copies to Android Trash",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Trash canceled · your photos are unchanged",
                Toast.LENGTH_SHORT).show()
        }
    }
    fun photoDnaLiveTokensOrNull(): PhotoDnaTokens? = latestPhotoDnaTokens.value.takeIf {
        readGalleryMediaGrant(context) == grants &&
            vm.publishedInventory.value.epoch == publishedGallery.epoch &&
            galleryAccessCurrent()
    }
    fun requestPhotoDnaTrash(request: CurationReviewRequest) {
        val held = currentPhotoDna(photoDna, photoDnaLiveTokensOrNull()) ?: run {
            Toast.makeText(context, "Your library changed. Review again; nothing was trashed.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (photoDnaVerifying) return
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(context, "Android Trash requires Android 11 or newer",
                Toast.LENGTH_LONG).show()
            return
        }
        val liveTokens = held.tokens
        val liveSource = media
        val grantAtStart = grants
        photoDnaVerifying = true
        atlasScope.launch {
            var launched = false
            try {
                // Fresh full-byte read of every copy, then the plan's own
                // recheck, then the MediaStore metadata preflight: all off main.
                val check = withContext(Dispatchers.IO) {
                    val worker = currentCoroutineContext()
                    val recheck = recheckPhotoDnaTrash(held, request, liveTokens, liveSource,
                        hash = { record ->
                            hashAuthorizedMedia(context.contentResolver, record,
                                stillAuthorized = {
                                    readGalleryMediaGrant(context) == grantAtStart
                                },
                                checkCancelled = { worker.ensureActive() })
                        },
                        checkpoint = { worker.ensureActive() })
                    if (recheck is PhotoDnaTrashCheck.Ready &&
                        !(DeclutterTrashPreflight.unchanged(context.contentResolver,
                            listOf(recheck.keeper)) &&
                            DeclutterTrashPreflight.unchanged(context.contentResolver,
                                recheck.selected))) {
                        PhotoDnaTrashCheck.Refused(
                            "Selected files changed or became inaccessible. Nothing was trashed.")
                    } else recheck
                }
                // The user may have navigated or the grant changed while reading.
                if (photoDnaLiveTokensOrNull() != liveTokens || !photoDnaOpen) {
                    Toast.makeText(context, "Your library changed. Review again; nothing was trashed.",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                when (check) {
                    is PhotoDnaTrashCheck.Refused ->
                        Toast.makeText(context, check.reason, Toast.LENGTH_LONG).show()
                    is PhotoDnaTrashCheck.Ready -> {
                        val trash = MediaStore.createTrashRequest(context.contentResolver,
                            check.selected.map { Uri.parse(it.uri) }, true)
                        photoDnaTrashLauncher.launch(
                            IntentSenderRequest.Builder(trash.intentSender).build())
                        launched = true
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't verify selected files. Nothing was trashed.",
                    Toast.LENGTH_LONG).show()
            } finally {
                if (!launched) photoDnaVerifying = false
            }
        }
    }
    fun shareBatch() {
        val selection = batchSelection ?: return
        if (batchOperating ||
            !selection.isCurrentFor(visible, publishedGallery.epoch, galleryAccessCurrent())) return
        if (selection.count > MAX_BATCH_SHARE_ITEMS) {
            Toast.makeText(context, "Share up to $MAX_BATCH_SHARE_ITEMS items at a time",
                Toast.LENGTH_LONG).show()
            return
        }
        val source = visible
        batchOperating = true
        atlasScope.launch {
            try {
                val records = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()
                    resolveBatchSelection(selection, source) { worker.ensureActive() }
                }
                if (records.isNullOrEmpty() || batchSelection !== selection) {
                    Toast.makeText(context, "Selection changed · select again to share",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                val uris = ArrayList(records.map { Uri.parse(it.uri) })
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE)
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                send.type = when {
                    records.all { it.isVideo } -> "video/*"
                    records.none { it.isVideo } -> "image/*"
                    else -> "*/*"
                }
                // Read-only, per-URI grants for the chosen target app only.
                send.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                    for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
                }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app can share these items",
                    Toast.LENGTH_LONG).show()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't share the selection", Toast.LENGTH_LONG).show()
            } finally {
                batchOperating = false
            }
        }
    }
    fun trashBatch() {
        val selection = batchSelection ?: return
        if (Build.VERSION.SDK_INT < 30 || batchOperating ||
            !selection.isCurrentFor(visible, publishedGallery.epoch, galleryAccessCurrent())) return
        if (selection.count > MAX_BATCH_TRASH_ITEMS) {
            Toast.makeText(context, "Move up to $MAX_BATCH_TRASH_ITEMS items to Trash at a time",
                Toast.LENGTH_LONG).show()
            return
        }
        val source = visible
        batchOperating = true
        atlasScope.launch {
            var launched = false
            try {
                val records = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()
                    resolveBatchSelection(selection, source) { worker.ensureActive() }
                }
                // A photo can change after it was selected: revalidate the
                // exact MediaStore rows off the main thread, then re-check
                // that the user is still looking at this same selection.
                val unchanged = records != null && withContext(Dispatchers.IO) {
                    DeclutterTrashPreflight.unchanged(context.contentResolver, records)
                }
                if (records == null || !unchanged || batchSelection !== selection ||
                    !selection.isCurrentFor(visible, publishedGallery.epoch,
                        galleryAccessCurrent())) {
                    Toast.makeText(context,
                        "Selected files changed or became inaccessible. " +
                            "Select again; nothing was trashed.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val request = MediaStore.createTrashRequest(
                    context.contentResolver, records.map { Uri.parse(it.uri) }, true)
                batchTrashLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build())
                launched = true
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't verify selected files. Nothing was trashed.",
                    Toast.LENGTH_LONG).show()
            } finally {
                if (!launched) batchOperating = false
            }
        }
    }
    fun favouriteBatch() {
        val selection = batchSelection ?: return
        if (Build.VERSION.SDK_INT < 30 || batchOperating || selection.count == 0 ||
            !selection.isCurrentFor(visible, publishedGallery.epoch, galleryAccessCurrent())) return
        if (selection.count > MAX_BATCH_SHARE_ITEMS) {
            Toast.makeText(context, "Favourite up to $MAX_BATCH_SHARE_ITEMS items at a time",
                Toast.LENGTH_LONG).show()
            return
        }
        val source = visible
        batchOperating = true
        atlasScope.launch {
            var launched = false
            try {
                val records = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()
                    resolveBatchSelection(selection, source) { worker.ensureActive() }
                }
                if (records.isNullOrEmpty() || batchSelection !== selection ||
                    records.any { !viewerCanChangeInMediaStore(it.uri, Build.VERSION.SDK_INT) }) {
                    Toast.makeText(context, "Selection changed · select again",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Samsung: favourite all, unless every selected item already is one.
                val value = !records.all { it.isFavorite }
                val request = MediaStore.createFavoriteRequest(
                    context.contentResolver, records.map { Uri.parse(it.uri) }, value)
                batchFavouriteLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build())
                launched = true
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't change favourites", Toast.LENGTH_LONG).show()
            } finally {
                if (!launched) batchOperating = false
            }
        }
    }
    fun transferBatch(mode: AlbumTransferMode, target: AlbumTransferTarget) {
        val selection = batchSelection ?: return
        if (Build.VERSION.SDK_INT < 30 || batchOperating || selection.count == 0) return
        // The picker is a separate screen: a library reload while it was open
        // (e.g. after a previous copy) re-publishes the list and rebases the
        // selection a moment later. Resolve against the selection's own list
        // instead of silently doing nothing; every URI is still checked by
        // MediaStore (byte count for a copy, Android's consent for a move).
        if (!galleryAccessCurrent()) {
            Toast.makeText(context, "Photo access changed · select again", Toast.LENGTH_LONG).show()
            return
        }
        if (selection.count > MAX_BATCH_SHARE_ITEMS) {
            Toast.makeText(context, (if (mode == AlbumTransferMode.COPY) "Copy" else "Move") +
                " up to $MAX_BATCH_SHARE_ITEMS items at a time", Toast.LENGTH_LONG).show()
            return
        }
        val source = selection.source
        batchOperating = true
        Log.i(TRANSFER_LOG, "start ${mode.name} items=${selection.count}")
        atlasScope.launch {
            Log.i(TRANSFER_LOG, "running")
            var handedOff = false
            try {
                // IO, not Default: right after a copy the new photos start
                // CPU-bound indexing on the small Default pool, and a move must
                // not queue behind it. The pass itself is one cheap scan.
                val records = withContext(Dispatchers.IO) {
                    val worker = currentCoroutineContext()
                    resolveBatchSelection(selection, source) { worker.ensureActive() }
                }
                if (records.isNullOrEmpty() ||
                    records.any { !viewerCanChangeInMediaStore(it.uri, Build.VERSION.SDK_INT) }) {
                    Log.i(TRANSFER_LOG, "stale selection")
                    Toast.makeText(context, "Selection changed · select again",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                Log.i(TRANSFER_LOG, "resolved ${records.size}")
                val plan = withContext(Dispatchers.IO) {
                    planAlbumTransfer(mode, target, records,
                        MediaStoreAlbumTransfer.takenNames(context.contentResolver, target))
                }
                Log.i(TRANSFER_LOG, "plan steps=${plan.steps.size} there=${plan.alreadyThere.size} " +
                    "otherVolume=${plan.otherVolume.size}")
                if (plan.steps.isEmpty()) {
                    finishAlbumTransfer(plan, AlbumTransferResult(0, plan.otherVolume.size,
                        plan.alreadyThere.size))
                } else if (mode == AlbumTransferMode.COPY) {
                    // A copy only creates new rows that ATLAS owns: no consent needed.
                    finishAlbumTransfer(plan, runAlbumTransfer(plan))
                } else {
                    val request = MediaStore.createWriteRequest(context.contentResolver,
                        plan.steps.map { Uri.parse(it.source.uri) })
                    pendingMove = plan
                    Log.i(TRANSFER_LOG, "consent requested steps=${plan.steps.size}")
                    batchMoveLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    handedOff = true
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                Log.i(TRANSFER_LOG, "cancelled")
                throw cancel
            } catch (failure: Exception) {
                pendingMove = null
                Log.w(TRANSFER_LOG, "transfer failed: ${failure.javaClass.simpleName}")
                Toast.makeText(context, if (mode == AlbumTransferMode.COPY)
                    "Couldn't copy the selection" else "Couldn't move the selection",
                    Toast.LENGTH_LONG).show()
            } finally {
                if (!handedOff) batchOperating = false
            }
        }.invokeOnCompletion { cause ->
            // A job cancelled before its body ran never reaches its finally.
            if (cause != null) {
                Log.i(TRANSFER_LOG, "ended ${cause.javaClass.simpleName}")
                if (pendingMove == null) batchOperating = false
            }
        }
    }
    // Samsung "Unfavourite": every selected item is already a favourite.
    val selectionForFavourite = batchSelection
    val selectionAllFavourite by produceState(false, selectionForFavourite) {
        val selected = selectionForFavourite
        value = false
        if (selected == null || selected.count == 0) return@produceState
        value = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            resolveBatchSelection(selected, selected.source) { worker.ensureActive() }
                ?.let { records -> records.isNotEmpty() && records.all { it.isFavorite } } == true
        }
    }
    // A picker never outlives its selection (Back, a new scope or lost access).
    LaunchedEffect(batchSelection == null) {
        if (batchSelection == null) albumPicker = null
    }
    BackHandler(enabled = coverSession != null || declutterFolder != null ||
        viewerUri != null || batchSelection != null || statusOpen ||
        personFilter != null || album != null || tab != 0) {
        when {
            coverSession != null -> coverSession = null
            declutterFolder != null -> closeDeclutter()
            viewerUri != null -> closeViewer()
            batchSelection != null -> batchSelection = null
            statusOpen -> statusOpen = false
            personFilter != null -> personFilter = null
            tab == 0 && album != null -> {
                album = null
                albumVolume = null
                if (albumReturnToAlbums) tab = 1
                albumReturnToAlbums = false
            }
            else -> tab = 0
        }
    }

    // #505: no blocking "Updating your allowed photos" screen. The last
    // published or cached catalogue is shown at once and refreshed in the
    // background. Only a first-ever scan with nothing to show yet lands here.
    if (access && !publishedGallery.showable(grants)) {
        Column(Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (failedInventoryEpoch >= awaitingScopedRefresh) {
                Text("Couldn't load your photos.",
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    requestScopedRefresh(grants)
                }) { Text("Retry") }
            } else {
                CircularProgressIndicator(Modifier.testTag("atlas-first-scan"))
            }
        }
        return
    }

    // Cover chooser is a separate, nonpersisted route. Re-authorize every
    // candidate/page action and keep the ordinary album list unmounted while
    // choosing. No MediaStore mutation or raw URI preference ever occurs.
    if (coverSession != null) {
      // Full-screen route outside the Scaffold: keep it clear of the status
      // bar, cutout and navigation bar under Android 15+ edge-to-edge.
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val session = currentCoverSession
        if (session == null || currentCoverSourceOrNull() == null) {
            LaunchedEffect(coverSession) { coverSession = null }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Album access changed", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val bucket = AlbumBucketKey(session.route.volumeName,
                session.route.relativePath)
            val selectedCover = activeCoverPreferences
                ?.preferredCoverByBucket?.get(bucket)
            if (coverPageFailed) {
                Column(Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load album cover choices",
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("Your photos haven't been changed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { coverSession = null }) {
                        Text("Back to Albums")
                    }
                }
            } else AtlasAlbumCoverChooser(
                projection = coverPage,
                authorizedSource = media,
                accessEpoch = publishedGallery.epoch,
                route = session.route,
                pageCursor = session.cursor,
                selectedCoverUri = selectedCover,
                isLoading = coverPage == null,
                coverRenderer = { record, modifier ->
                    MediaPreview(record, modifier, ContentScale.Crop, 400)
                },
                onDismiss = { coverSession = null },
                onChoose = { choice ->
                    if (coverSession === session &&
                        currentCoverSourceOrNull() === media) {
                        val existing = activeCoverPreferences
                            ?: AlbumCoverPreferences.start(
                                media, publishedGallery.epoch)
                        val chosen = existing.chooseOrNull(
                            coverPage, choice, media, publishedGallery.epoch,
                            session.route)
                        if (chosen != null) {
                            coverPreferences = chosen
                            coverSession = null
                        }
                    }
                },
                onRestoreAutomatic = if (selectedCover != null) ({
                    if (coverSession === session &&
                        currentCoverSourceOrNull() === media) {
                        val updated = activeCoverPreferences?.clearOrNull(
                            bucket, media, publishedGallery.epoch)
                        if (updated != null) {
                            coverPreferences = updated
                            coverSession = null
                        }
                    }
                }) else null,
                onOlderPage = { requested ->
                    if (coverSession === session &&
                        currentCoverSourceOrNull() === media) {
                        val next = session.olderOrNull(
                            coverPage, media, publishedGallery.epoch,
                            session.route)
                        if (next?.cursor == requested) coverSession = next
                    }
                },
                onPreviousPage = {
                    if (coverSession === session &&
                        currentCoverSourceOrNull() === media) {
                        session.newerOrNull(
                            media, publishedGallery.epoch, session.route
                        )?.let { coverSession = it }
                    }
                }
            )
        }
      } // End safe-drawing cover chooser route.
        return
    }

    val folderForReview = declutterFolder
    if (folderForReview != null) {
      // Declutter is a regular themed page, not a forced-dark media viewer.
      Box(Modifier.fillMaxSize().background(AtlasPageColor)) {
        val itemsForReview = declutterItems
        if (itemsForReview == null) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.Center) {
                Text("Preparing largest files in selected folder…")
            }
        } else {
            DeclutterScreen(
                folder = folderForReview,
                deck = itemsForReview,
                verifying = trashPreflightPending,
                onExit = ::closeDeclutter,
                onTrashRequest = { selected ->
                    // A left swipe NEVER deletes. Only after ATLAS's review
                    // and explicit confirm does Android show its own approval.
                    if (Build.VERSION.SDK_INT < 30) {
                        Toast.makeText(context,
                            "Android Trash requires Android 11 or newer",
                            Toast.LENGTH_LONG).show()
                    } else if (selected.isEmpty() || selected.size > 200 ||
                        selected.any { Uri.parse(it.uri).authority != MediaStore.AUTHORITY }) {
                        Toast.makeText(context,
                            "Cannot prepare a bounded, valid MediaStore Trash request",
                            Toast.LENGTH_LONG).show()
                    } else if (!trashPreflightPending) {
                        // A photo can change AFTER it was reviewed. Validate
                        // only the staged MediaStore entries off main thread.
                        trashPreflightPending = true
                        atlasScope.launch {
                            try {
                                val unchanged = withContext(Dispatchers.IO) {
                                    DeclutterTrashPreflight.unchanged(
                                        context.contentResolver, selected)
                                }
                                if (declutterFolder != folderForReview ||
                                    declutterItems !== itemsForReview) {
                                    // Prevent an old async review from launching
                                    // OS Trash after navigation to a new deck.
                                    return@launch
                                }
                                if (!unchanged) {
                                    Toast.makeText(context,
                                        "Selected files changed or became inaccessible. " +
                                        "Restart the folder review; nothing was trashed.",
                                        Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                val request = MediaStore.createTrashRequest(
                                    context.contentResolver,
                                    selected.map { Uri.parse(it.uri) },
                                    true
                                )
                                trashLauncher.launch(
                                    IntentSenderRequest.Builder(request.intentSender).build()
                                )
                            } catch (_: Exception) {
                                Toast.makeText(context,
                                    "Couldn't verify selected files. Nothing was trashed.",
                                    Toast.LENGTH_LONG).show()
                            } finally {
                                if (declutterItems === itemsForReview) {
                                    trashPreflightPending = false
                                }
                            }
                        }
                    }
                }
            )
        }
      } // End Declutter route.
        return
    }

    val contextItems = viewerItems
    // GalleryViewer can retain a decoded image after its original permission
    // publication is replaced. Hide the full-screen route synchronously at
    // composition time, before any stale bitmap can be remounted.
    val viewerAllowed = canDisplayAuthorizedViewer(
        viewerItems, viewerUri, viewerAccessEpoch, grants.hasAny)
    if (viewerUri != null && !viewerAllowed) {
        LaunchedEffect(viewerUri, viewerAccessEpoch, grants) {
            closeViewer()
            pendingViewerReturn = null
            pendingRatioReturn = null
            pendingRatioAnchor = null
        }
    }
    val currentUri = viewerUri?.takeIf { viewerAllowed }
    if (contextItems != null && currentUri != null) {
      // #662: the viewer stays dark whatever the phone's setting, as on Samsung.
      AtlasDarkRoute {
        GalleryViewer(
            contextItems, currentUri,
            // Trash went through Android's own consent dialog; only RESULT_OK lands here.
            // Drop the photo from this viewer and show its neighbour, like Samsung does.
            onTrashed = { gone ->
                val index = contextItems.indexOfFirst { it.uri == gone.uri }
                val rest = contextItems.filterNot { it.uri == gone.uri }
                if (rest.isEmpty()) {
                    closeViewer()
                } else {
                    viewerItems = rest
                    viewerUri = rest[index.coerceIn(0, rest.lastIndex)].uri
                }
                vm.refresh()
            },
            onEdit = { photo ->
                val launchResult = PhotoEditorHandoff.launch(context, photo)
                when (launchResult) {
                    PhotoEditorHandoff.Result.SAMSUNG_EDITOR -> Unit
                    PhotoEditorHandoff.Result.SAMSUNG_GALLERY ->
                        Toast.makeText(context, "Tap the pencil in Samsung Gallery to edit",
                            Toast.LENGTH_LONG).show()
                    PhotoEditorHandoff.Result.SYSTEM_EDITOR ->
                        Toast.makeText(context, "Opened an available photo editor",
                            Toast.LENGTH_SHORT).show()
                    PhotoEditorHandoff.Result.UNAVAILABLE ->
                        Toast.makeText(context, "No supported editor could open this photo",
                            Toast.LENGTH_LONG).show()
                }
                launchResult != PhotoEditorHandoff.Result.UNAVAILABLE
            },
            onExitSettledUri = { lastViewedUri ->
                if (viewerAllowed && viewerAccessEpoch == publishedGallery.epoch &&
                    grants.hasAny &&
                    publishedGallery.showable(grants) &&
                    // Stories (#439) open the viewer from Albums: no grid to restore.
                    contextItems === visible && tab != 1 && faceStep == null &&
                    similarStep == null) {
                    if (tab == 0 && originalProportions) {
                        // Only record the immutable intent on the click path.
                        // Off-main resolution happens after the viewer unmounts.
                        pendingRatioReturn = PendingViewerReturn(
                            contextItems, lastViewedUri, 0,
                            publishedGallery.epoch)
                    } else {
                        pendingViewerReturn = PendingViewerReturn(
                            contextItems, lastViewedUri,
                            when {
                                personFilter != null -> 2
                                tab == 3 -> 1
                                else -> 0
                            }, publishedGallery.epoch)
                    }
                }
            },
            // "Find this person" only from gallery browsing, not from results.
            faceSearch = if (faceStep != null) null else ViewerFaceSearch(
                hasFaces = { photo ->
                    withContext(Dispatchers.IO) {
                        facePort.liveEpoch()?.let { facePort.indexedFaceCount(it, photo) > 0 } == true
                    }
                },
                onFindPerson = { photo ->
                    faceSession.clear()
                    faceOrigin = contextItems to photo.uri
                    closeViewer()
                    faceStep = FaceSearchStep.PickFace(photo, fromChooser = false)
                }
            ),
            faceHighlights = faceResults?.takeIf { contextItems === it.records }
                ?.boxesByUri.orEmpty(),
            // "Find similar" from gallery browsing (any photo or video frame),
            // not from inside another search's results.
            findSimilar = if (faceStep != null || similarStep != null) null else
                ViewerFindSimilar { record, frameMs ->
                    similarSession.clear()
                    similarOrigin = contextItems to record.uri
                    closeViewer()
                    similarStep = FindSimilarStep.Results
                    atlasScope.launch { similarSession.search(FindSimilarReference(record, frameMs)) }
                },
            videoStartPositions = similarResults?.takeIf { contextItems === it.records }
                ?.startPositions.orEmpty(),
            // #553: People in swipe-up Details; a tap opens that person.
            photoPeople = remember(discoveryHost) {
                viewerPhotoPeople(discoveryHost, { latestSearchGrant.value }) { person ->
                    closeViewer()
                    tab = 2
                    personFilter = null
                    peopleReviewOpen = false
                    hiddenPeopleOpen = false
                    nativePersonAlbum = NativePersonAlbumRequest(person.person, person.name)
                }
            },
            onBack = ::closeViewer
        )
      }
        return
    }

    val pendingSimilarReturn = similarReturn
    if (pendingSimilarReturn != null && similarStep == null) {
        LaunchedEffect(pendingSimilarReturn) {
            similarReturn = null
            if (pendingSimilarReturn.first === visible)
                openViewer(pendingSimilarReturn.first, pendingSimilarReturn.second)
        }
    }
    val similarRoute = similarStep
    if (similarRoute != null) {
        FindSimilarRoute(
            session = similarSession,
            portVersion = faceHostVersion,
            step = similarRoute,
            photos = sorted,
            onStep = { similarStep = it },
            onOpen = { items, record, _ -> if (items === visible) openViewer(items, record.uri) },
            // #643: the Visual search index switch lives in Settings.
            onOpenSettings = {
                closeFindSimilar()
                settingsOpen = true
            },
            onClose = {
                // `visible` is still the results list in this frame: reopen the
                // origin photo once the gallery list is shown again (below).
                similarReturn = similarOrigin
                closeFindSimilar()
            },
            thumbnail = { record ->
                withContext(Dispatchers.IO) {
                    AppFindSimilarPort.thumbnail(context, record.uri)?.asImageBitmap()
                }
            }
        )
        return
    }

    val faceRoute = faceStep
    if (faceRoute != null) {
        FaceSearchRoute(
            port = facePort,
            portVersion = faceHostVersion,
            session = faceSession,
            step = faceRoute,
            photos = sorted,
            onStep = { faceStep = it },
            onOpen = { items, record -> if (items === visible) openViewer(items, record.uri) },
            onOpenAlbum = { person, name ->
                closeFaceSearch()
                tab = 2
                personFilter = null
                nativePersonAlbum = NativePersonAlbumRequest(person, name)
            },
            onOpenPeople = {
                closeFaceSearch()
                tab = 2
                personFilter = null
                nativePersonAlbum = null
                hiddenPeopleOpen = false
            },
            onClose = {
                val origin = faceOrigin
                closeFaceSearch()
                if (origin != null && origin.first === visible) openViewer(origin.first, origin.second)
            }
        )
        return
    }

    val pickerMode = albumPicker
    if (pickerMode != null) {
        AlbumPickerScreen(
            title = if (pickerMode == AlbumTransferMode.COPY) "Copy to album" else "Move to album",
            albums = pickerAlbumCards?.albums,
            onPick = { picked ->
                albumPicker = null
                transferBatch(pickerMode,
                    AlbumTransferTarget(picked.volumeName, picked.relativePath, picked.title))
            },
            onCreate = { name ->
                albumPicker = null
                transferBatch(pickerMode, AlbumTransferTarget(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY, newAlbumRelativePath(name), name))
            },
            nameProblem = { typed ->
                val existing = pickerAlbumCards?.albums.orEmpty()
                    .map { AlbumBucketKey(it.volumeName, it.relativePath) }
                albumNameProblem(typed, existing, MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    ?.let(::albumNameProblemText)
            },
            onClose = { albumPicker = null },
            coverRenderer = { record, modifier ->
                MediaPreview(record, modifier, ContentScale.Crop, 512)
            }
        )
        return
    }

    // The content and floating navigation share the underlying *drawn* page.
    // The recorded layer never includes the pill or selection actions.
    val tabBackdrop = rememberAtlasTabBackdropState()
    Scaffold(
        containerColor = DeepBg,
        // DeepBg is not a colorScheme colour, so contentColorFor(DeepBg) fell
        // back to the root LocalContentColor (black): every heading, title and
        // icon without an explicit colour rendered black-on-black (seen on the
        // API 35 emulator: "Photos", the empty-state heading, Settings icon).
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            if (!settingsOpen && !statusOpen) {
            val photosSelection = batchSelection?.takeIf {
                tab == 0 && personFilter == null && !statusOpen &&
                    !originalProportions && timelineEnabled
            }
            // #556: Samsung swaps the tab bar for the selection actions.
            if (photosSelection != null) {
                val live = photosSelection.isCurrentFor(visible, publishedGallery.epoch,
                    galleryAccessCurrent())
                SamsungSelectionBottomBar(
                    count = photosSelection.count,
                    enabled = live && !batchOperating,
                    allFavourite = selectionAllFavourite,
                    onShare = { shareBatch() },
                    // Android 10 has no consent-mediated trash or favourite request.
                    onTrash = if (Build.VERSION.SDK_INT >= 30) ({ trashBatch() }) else null,
                    onFavourite = if (Build.VERSION.SDK_INT >= 30) ({ favouriteBatch() }) else null,
                    // #568 Samsung More: Copy, then Move (needs Android 11 write consent).
                    moreActions = if (Build.VERSION.SDK_INT >= 30) listOf(
                        SelectionMoreAction("Copy to album", "atlas-selection-copy-to-album") {
                            albumPicker = AlbumTransferMode.COPY
                        },
                        SelectionMoreAction("Move to album", "atlas-selection-move-to-album") {
                            albumPicker = AlbumTransferMode.MOVE
                        }
                    ) else emptyList()
                )
            } else CompositionLocalProvider(LocalAtlasTabBackdrop provides tabBackdrop) {
                AtlasGalleryNavigation(
                selected = when (tab) {
                    1 -> AtlasDestination.ALBUMS
                    2 -> AtlasDestination.PEOPLE
                    3 -> AtlasDestination.SEARCH
                    else -> AtlasDestination.PHOTOS
                },
                onSelect = { destination ->
                    // A primary-tab tap always opens that tab's unfiltered entry point.
                    tab = when (destination) {
                        AtlasDestination.PHOTOS -> 0
                        AtlasDestination.ALBUMS -> 1
                        AtlasDestination.PEOPLE -> 2
                        AtlasDestination.SEARCH -> 3
                    }
                    statusOpen = false
                    settingsOpen = false
                    storiesOpen = false
                    storyPlayback.position = null
                    photoDnaOpen = false
                    hideAlbumsOpen = false
                    personFilter = null
                    nativePersonAlbum = null
                    hiddenPeopleOpen = false
                    album = null
                    albumVolume = null
                    albumReturnToAlbums = false
                }
                )
            }
            }
        }
    ) { insets ->
        // #652: on the Photos timeline/grid, album detail and the Albums grid,
        // content draws under the floating tab pill; the grids add the bar's
        // height as bottom content padding so their last row stays reachable.
        // Every other screen (and selection mode's opaque bar) stays above it.
        val navPillShown = !(batchSelection != null && tab == 0 && personFilter == null &&
            !statusOpen && !originalProportions && timelineEnabled)
        val drawUnderBar = navPillShown && access && !statusOpen && !settingsOpen && personFilter == null &&
            (tab == 0 || (tab == 1 && !storiesOpen && !photoDnaOpen))
        val layoutDirection = LocalLayoutDirection.current
        CompositionLocalProvider(LocalAtlasBottomChrome provides
            (if (drawUnderBar) insets.calculateBottomPadding() else 0.dp)) {
        // No GPU capture or blur allocation in the Android 10/11 fallback.
        val contentModifier = if (Build.VERSION.SDK_INT >= 31) {
            Modifier.fillMaxSize().atlasCaptureTabBackdrop(tabBackdrop)
        } else {
            Modifier.fillMaxSize()
        }
        Column(contentModifier.padding(
            start = insets.calculateStartPadding(layoutDirection),
            top = insets.calculateTopPadding(),
            end = insets.calculateEndPadding(layoutDirection),
            bottom = if (drawUnderBar) 0.dp else insets.calculateBottomPadding())) {
            // #492: one slim Samsung-style bar. Photos' sort, view toggles and
            // Declutter moved from two extra rows into its icon + overflow menu;
            // the toggles keep their exact former handlers and test tags.
            val photosControls = access && tab == 0 && personFilter == null && !statusOpen
            val toggleTimeline: () -> Unit = {
                // The two layouts cannot share raw grid slots;
                // a switch back starts in its own remembered grid.
                if (originalProportions) {
                    // Leaving the uncropped list should not jump to
                    // the timeline's old slot, which can precede
                    // thousands of date headers. Capture the live
                    // first visible ratio media; the existing
                    // grant-scoped, off-main return resolver maps
                    // that URI to the correct timeline slot.
                    val sourceMatches = ratioCatalog === visible &&
                        ratioEpoch == publishedGallery.epoch
                    val mediaIndex = if (sourceMatches) {
                        ratioLayout?.rows?.getOrNull(
                            ratioListState.firstVisibleItemIndex)
                            ?.firstMediaIndex
                    } else null
                    pendingViewerReturn = mediaIndex?.let { index ->
                        visible.getOrNull(index)?.let { item ->
                            PendingViewerReturn(visible, item.uri, 0,
                                publishedGallery.epoch)
                        }
                    }
                    originalProportions = false
                    timelineEnabled = true
                } else if (timelineEnabled) {
                    // The dated grid includes header slots. Use
                    // its LIVE media key rather than the inactive
                    // plain grid's previous item index.
                    val timelineInfo = timelineGridState.layoutInfo
                    val uri = firstTimelineVisibleMediaUri(
                        timelineInfo.visibleItemsInfo.map { it.index },
                        timelineInfo.totalItemsCount,
                        timelineDisplayed, visible,
                        publishedGallery.epoch)
                    pendingViewerReturn = uri?.let {
                        PendingViewerReturn(visible, it, 0,
                            publishedGallery.epoch)
                    }
                    timelineEnabled = false
                } else {
                    // In the plain grid every slot is media.
                    // G's timeline remount will resolve this
                    // intent to a date-header-aware slot.
                    val index = photosGridState.firstVisibleItemIndex
                    pendingViewerReturn = visible.getOrNull(index)?.let {
                        PendingViewerReturn(visible, it.uri, 0,
                            publishedGallery.epoch)
                    }
                    timelineEnabled = true
                }
            }
            val canDeclutter = albumRoute != null &&
                albumRoute.relativePath.isNotBlank() &&
                albumRoute.relativePath.endsWith('/') &&
                selectedAlbum != null && !busy &&
                !sortPending && !albumSelectionPending
            // Albums supplies its own photographic heading; other routes share one shell.
            val galleryHeader: @Composable () -> Unit = {
                AtlasGalleryHeader(
                    title = when {
                        statusOpen -> "Library status"
                        settingsOpen -> "Settings"
                        personFilter != null -> people.firstOrNull { it.id == personFilter }?.name ?: "Person"
                        tab == 0 && album != null -> album!!.trimEnd('/').substringAfterLast('/')
                        tab == 2 -> "People"
                        tab == 3 -> "Search"
                        else -> "Photos"
                    },
                    subtitle = if (photosControls && !settingsOpen && !statusOpen)
                        photosHeaderStatus(sortPending, albumSelectionPending, visible.size)
                    else null,
                    onBack = when {
                        statusOpen -> ({ statusOpen = false })
                        settingsOpen -> ({ settingsOpen = false })
                        photosControls && album != null -> ({
                            album = null
                            albumVolume = null
                            if (albumReturnToAlbums) tab = 1
                            albumReturnToAlbums = false
                        })
                        else -> null
                    },
                    backDescription = when {
                        statusOpen -> "Back to Settings"
                        settingsOpen -> "Close Settings"
                        else -> "All albums"
                    },
                    onOpenLibraryStatus = if (statusOpen || settingsOpen) null else ({ settingsOpen = true }),
                    menuItems = if (photosControls && !settingsOpen && !statusOpen) photosHeaderMenu(
                        timelineSelected = timelineEnabled && !originalProportions,
                        canDeclutter = canDeclutter,
                        onToggleTimeline = toggleTimeline,
                        onDeclutter = {
                            val route = albumRoute
                            val selected = selectedAlbum
                            if (route != null && route.relativePath.isNotBlank() &&
                                route.relativePath.endsWith('/') && selected != null) {
                                declutterItems = null
                                // Already route-filtered on Dispatcher.Default.
                                // The existing builder never expands its input.
                                declutterSource = selected
                                declutterFolder = route.relativePath
                            }
                        }
                    ) else emptyList(),
                    actions = {
                        if (photosControls && !settingsOpen) PhotosSortAction(
                            order = sort, onSort = { sort = it }, onShuffle = { randomSeed++ })
                    }
                )
            }
            // #514/#506: on the Photos grid the header is drawn over the grid and
            // scrolls away with it (Samsung); the grid then uses the full height.
            val headerScrollsWithGrid = photosControls && timelineEnabled && !originalProportions &&
                !settingsOpen && !statusOpen
            // #644: Search is one field with its own ⋮ (Samsung), no title bar.
            val searchOwnsTop = tab == 3 && personFilter == null && !statusOpen && !settingsOpen
            if (((tab != 1 && tab != 2) || personFilter != null || statusOpen || settingsOpen) &&
                !headerScrollsWithGrid && !searchOwnsTop) {
                galleryHeader()
            }
            if (notification != null) {
                Text(notification!!, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = Mint, style = MaterialTheme.typography.bodySmall)
            }
            if (!access && !settingsOpen && !statusOpen) {
                Column(Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(58.dp), tint = Mint)
                    Spacer(Modifier.height(16.dp))
                    Text("Your photos stay on your phone", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("Grant photo and video access to build your private gallery. ATLAS never uploads or edits your originals.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(necessaryPermissions()) }) {
                        Text("Choose photo access")
                    }
                }
            } else {
                when {
                    statusOpen -> {
                        // Advanced > Diagnostics: same read-only library facts.
                        ActivityScreen(media.size, people.size, busy, error, vm::refresh)
                    }
                    settingsOpen -> {
                        val visualStatus = discovery.status?.visual
                        val provenance = discovery.status?.provenance
                        AtlasSettingsScreen(
                            scanPhase = discovery.status?.ui?.phase,
                            onPauseScan = discovery.onPause.takeIf {
                                discovery.status?.actions?.mayPauseScan == true
                            },
                            onResumeScan = discovery.onResume.takeIf {
                                discovery.status?.actions?.mayResumeScan == true
                            },
                            visualIndexOn = visualStatus?.enabled,
                            showPhotoDetails = provenance != null &&
                                (provenance.missingPhotoFields.isNotEmpty() ||
                                    provenance.ignoredPhotoRows > 0),
                            versionName = remember(context) {
                                runCatching {
                                    context.packageManager.getPackageInfo(
                                        context.packageName, 0).versionName
                                }.getOrNull() ?: "Unknown"
                            },
                            onVisualIndex = { visualIndexOpen = true },
                            onPeopleNumbers = { peopleNumbersOpen = true },
                            onPhotoDetails = { provenanceDetailsOpen = true },
                            onImportV4 = {
                                importLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
                            },
                            onLinkV4 = {
                                linkV4Launcher.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
                            },
                            onDiagnostics = { statusOpen = true }
                        )
                        if (peopleNumbersOpen) PeopleNumbersRoute(discoveryHost, searchGrant) {
                            peopleNumbersOpen = false
                        }
                        if (visualIndexOpen && visualStatus != null) {
                            Dialog(onDismissRequest = { visualIndexOpen = false }) {
                                VisualSearchIndexPanel(
                                    status = visualStatus,
                                    onToggle = discovery.onVisualIndex,
                                    onDelete = discovery.onDeleteVisualIndex
                                )
                            }
                        }
                        if (provenanceDetailsOpen && provenance != null) {
                            PhotoDetailsDialog(
                                content = photoDetailsContent(
                                    checked = provenance.observedPhotoRows,
                                    partial = provenance.weakPhotoRows,
                                    skipped = provenance.ignoredPhotoRows,
                                    missing = provenance.missingPhotoFields,
                                    locale = Locale.getDefault()
                                ),
                                onClose = { provenanceDetailsOpen = false }
                            )
                        }
                    }
                    personFilter != null -> {
                        PeopleAlbumContent(
                            visible = visible,
                            gridState = personGridState,
                            busy = busy || sortPending || personAlbumPending,
                            catalogueEpoch = publishedGallery.epoch,
                            blackAndWhiteFolderOnly = personBwFolderOnly,
                            onBack = { personFilter = null },
                            onToggleBlackAndWhite = {
                                personBwFolderOnly = !personBwFolderOnly
                            },
                            onOpen = { openViewer(visible, it.uri) }
                        )
                    }
                    tab == 0 -> {
                        val photosSelecting = batchSelection != null && !originalProportions &&
                            timelineEnabled
                        if (originalProportions) {
                            // Source-reference key avoids Compose's 150k List.equals
                            // inside G's remember(items)/LaunchedEffect(items).
                            key(CatalogSourceKey(visible)) {
                                AtlasRatioGallery(
                                    items = visible,
                                    aspectRatioOf = { it.displayAspectRatioOrNull() },
                                    onOpen = { openViewer(visible, it.uri) },
                                    state = ratioListState,
                                    catalogueEpoch = publishedGallery.epoch,
                                    // G zoom ladder: pinch in from original rows to the
                                    // adjacent large square stop, focused on the pinched
                                    // photo once its URI is re-proven for this epoch.
                                    onCompactGridRequested = { request ->
                                        if (originalProportions && galleryAccessCurrent() &&
                                            ratioCatalog === visible &&
                                            ratioEpoch == publishedGallery.epoch &&
                                            request.isCurrentFor(publishedGallery.epoch,
                                                visible.size) { visible.getOrNull(it)?.uri }) {
                                            pendingRatioAnchor = null
                                            pendingRatioReturn = null
                                            pendingViewerReturn = request.focal?.let {
                                                PendingViewerReturn(visible, it.mediaKey, 0,
                                                    publishedGallery.epoch)
                                            }
                                            timelineEntryDensity =
                                                request.destination?.squareDensityOrNull()
                                                    ?: GridDensity.LARGE
                                            timelineEnabled = ratioReturnToTimeline
                                            originalProportions = false
                                        }
                                    },
                                    onLayoutReady = { ready ->
                                        // No I/O or scrolling in G's callback.
                                        // The separate effect matches both the
                                        // plan and deferred media URI to this epoch.
                                        ratioLayout = ready
                                        ratioCatalog = visible
                                        ratioEpoch = publishedGallery.epoch
                                    }
                                )
                            }
                        } else if (timelineEnabled) {
                            // G owns date/group scrub logic; Lane I supplies only
                            // a permission-gated catalog and the destination state.
                            key(CatalogSourceKey(visible)) {
                                AtlasTimelineGallery(
                                    items = visible,
                                    sort = when (sort) {
                                        SortOrder.NEWEST -> TimelineSort.NEWEST
                                        SortOrder.OLDEST -> TimelineSort.OLDEST
                                        SortOrder.LARGEST -> TimelineSort.LARGEST
                                        SortOrder.SMALLEST -> TimelineSort.SMALLEST
                                        SortOrder.RANDOM -> TimelineSort.RANDOM
                                    },
                                    gridState = timelineGridState,
                                    onOpen = { openViewer(visible, it.uri) },
                                    viewKey = "photos",
                                    busy = busy || sortPending || albumSelectionPending,
                                    catalogueEpoch = publishedGallery.epoch,
                                    onDisplayedSlots = { timelineDisplayed = it },
                                    header = if (headerScrollsWithGrid) ({
                                        Box(Modifier.fillMaxWidth().background(DeepBg)) {
                                            val selecting = batchSelection
                                            // #556: the selection top bar takes the header's place.
                                            if (selecting != null && photosSelecting) {
                                                SamsungSelectionTopBar(
                                                    count = selecting.count,
                                                    allSelected = visible.isNotEmpty() &&
                                                        selecting.count >= visible.size,
                                                    enabled = !batchOperating,
                                                    onToggleAll = {
                                                        val now = batchSelection
                                                        if (now != null && !batchOperating &&
                                                            now.isCurrentFor(visible,
                                                                publishedGallery.epoch,
                                                                galleryAccessCurrent())) {
                                                            batchSelection = now.replaced(toggleAll(
                                                                visible.size, now.uris
                                                            ) { visible.getOrNull(it)?.uri })
                                                        }
                                                    }
                                                )
                                            } else galleryHeader()
                                        }
                                    }) else null,
                                    pinHeader = photosSelecting,
                                    // G zoom ladder (#494): pinch out past three columns
                                    // enters uncropped justified rows; there is no button.
                                    onOriginalProportionsRequested = { request ->
                                        if (!originalProportions && galleryAccessCurrent() &&
                                            request.destination == GalleryZoomStop.ORIGINAL &&
                                            request.isCurrentFor(publishedGallery.epoch,
                                                visible.size) { visible.getOrNull(it)?.uri }) {
                                            batchSelection = null
                                            pendingRatioReturn = null
                                            // The existing deferred effect re-checks grant,
                                            // route and URI before scrolling the ratio list.
                                            pendingRatioAnchor = request.focal?.let {
                                                it.itemIndex to it.mediaKey
                                            }
                                            ratioReturnToTimeline = true
                                            originalProportions = true
                                        }
                                    },
                                    entryDensity = timelineEntryDensity,
                                    selectedMediaUris = batchSelection?.takeIf {
                                        it.isCurrentFor(visible, publishedGallery.epoch,
                                            galleryAccessCurrent())
                                    }?.uris,
                                    onSelectionChange = { uris ->
                                        val selection = batchSelection
                                        if (!batchOperating && galleryAccessCurrent()) {
                                            if (selection == null) {
                                                selectionAlbumRoute = albumRoute
                                                batchSelection = GalleryBatchSelection.of(
                                                    visible, publishedGallery.epoch, uris)
                                            } else if (selection.isCurrentFor(visible,
                                                    publishedGallery.epoch, true)) {
                                                batchSelection = selection.replaced(uris)
                                            }
                                        }
                                    },
                                    onMediaToggle = { media ->
                                        val selection = batchSelection
                                        if (selection != null && !batchOperating &&
                                            selection.isCurrentFor(visible,
                                                publishedGallery.epoch, galleryAccessCurrent())) {
                                            // Samsung: unchecking the last item keeps selection open.
                                            batchSelection = selection.toggledKeepingMode(media)
                                        }
                                    }
                                )
                            }
                        } else {
                            // Key by identity, not Compose's structural List.equals over 150k rows.
                            key(CatalogSourceKey(visible)) {
                                AtlasFeatureGrid(
                                    items = visible, state = photosGridState,
                                    busy = busy || sortPending || albumSelectionPending,
                                    onOpen = { openViewer(visible, it.uri) },
                                    viewKey = "photos",
                                    catalogueEpoch = publishedGallery.epoch,
                                    onOriginalProportionsRequested = { request ->
                                        if (!originalProportions && !timelineEnabled &&
                                            galleryAccessCurrent() &&
                                            request.destination == GalleryZoomStop.ORIGINAL &&
                                            request.isCurrentFor(publishedGallery.epoch,
                                                visible.size) { visible.getOrNull(it)?.uri }) {
                                            batchSelection = null
                                            pendingRatioReturn = null
                                            pendingRatioAnchor = request.focal?.let {
                                                it.itemIndex to it.mediaKey
                                            }
                                            ratioReturnToTimeline = false
                                            originalProportions = true
                                        }
                                    },
                                    entryDensity = timelineEntryDensity
                                )
                            }
                        }
                    }
                    tab == 1 && hideAlbumsOpen -> {
                        HideAlbumsScreen(
                            albums = if (busy || albumsPending) null else allAlbums,
                            hidden = hiddenAlbums,
                            onToggle = ::toggleHiddenAlbum,
                            onClose = { hideAlbumsOpen = false },
                            coverRenderer = { record, modifier ->
                                MediaPreview(record, modifier, ContentScale.Crop, 512)
                            }
                        )
                    }
                    tab == 1 && storiesOpen -> {
                        AtlasStoriesScreen(
                            deck = storyDeck,
                            currentGrantEpoch = storyTokens?.grantEpoch,
                            currentCatalogRevision = storyTokens?.catalogRevision,
                            onBack = { storiesOpen = false },
                            playback = storyPlayback,
                            onOpenMedia = { uri ->
                                // Re-check the live Android grant and publication at
                                // click time, not only the last composed frame.
                                val live = storyTokens.takeIf {
                                    readGalleryMediaGrant(context) == grants &&
                                        vm.publishedInventory.value.epoch ==
                                            publishedGallery.epoch &&
                                        galleryAccessCurrent()
                                }
                                val target = authorizedStoryViewerUriOrNull(
                                    uri, storyDeck, live, visible)
                                if (target != null) openViewer(visible, target)
                                else Toast.makeText(context,
                                    "This moment is no longer available",
                                    Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    tab == 1 && photoDnaOpen -> {
                        PhotoDnaRoute(
                            result = currentPhotoDna(photoDna, photoDnaTokens),
                            verifying = photoDnaVerifying,
                            onBack = { photoDnaOpen = false },
                            onOpenPhoto = { uri ->
                                // Re-check the live grant and publication at click
                                // time; the viewer only opens the current list.
                                val live = currentPhotoDna(photoDna, photoDnaLiveTokensOrNull())
                                if (live != null && uri in live.records &&
                                    visible.any { it.uri == uri }) openViewer(visible, uri)
                                else Toast.makeText(context,
                                    "This photo is no longer available",
                                    Toast.LENGTH_SHORT).show()
                            },
                            onReviewRequested = ::requestPhotoDnaTrash,
                            // No tokens: a no-access message, never an endless spinner (#445).
                            accessible = photoDnaTokens != null
                        )
                    }
                    tab == 1 -> Column(Modifier.fillMaxSize()) {
                        AtlasAlbumsScreen(
                            modifier = Modifier.weight(1f),
                            albums = albums,
                            sort = albumSort,
                            onSort = { choice ->
                                if (choice == AlbumSort.CUSTOM && albumCustomOrder.isEmpty()) {
                                    albumCustomOrder = customAlbumOrder(albums)
                                    persistAlbumCustomOrder(albumCustomOrder)
                                    albumOrderRevision++
                                }
                                albumSort = choice
                                persistAlbumSort(choice)
                            },
                            onReorderAlbums = { reordered ->
                                // A drag in any sort switches to Custom, like Samsung.
                                albumCustomOrder = customAlbumOrder(reordered)
                                persistAlbumCustomOrder(albumCustomOrder)
                                albumOrderRevision++
                                albumSort = AlbumSort.CUSTOM
                                persistAlbumSort(AlbumSort.CUSTOM)
                            },
                            onOpenStories = { storiesOpen = true },
                            onOpenPhotoDna = { photoDnaOpen = true },
                            onOpenHideAlbums = { hideAlbumsOpen = true },
                            onOpenAlbum = { route ->
                                album = route.relativePath
                                albumVolume = route.volumeName
                                albumSelectedFilter = route.mediaFilter
                                albumReturnToAlbums = true
                                tab = 0
                            },
                            mediaFilter = albumMediaFilter,
                            onMediaFilterChange = { albumMediaFilter = it },
                            coverRenderer = { record, modifier ->
                                // D only passes the current authorized coverMedia reference,
                                // while MediaPreview adds M's revision/permission cache key.
                                MediaPreview(record, modifier, ContentScale.Crop, 512)
                            },
                            isLoading = busy || albumsPending,
                            onOpenLibraryStatus = { settingsOpen = true },
                            loadIssue = if (albumFailed.value && !busy)
                                AlbumLoadIssue.READ_FAILED else null,
                            onRetry = { albumRetryEpoch++ },
                            onChooseCover = { route ->
                                if (!busy && !albumsPending &&
                                    currentCoverSourceOrNull() === media) {
                                    coverSession = AlbumCoverPageSession.start(
                                        media, publishedGallery.epoch, route)
                                }
                            }
                        )
                    }
                    tab == 3 -> {
                        LocalSearchControls(
                            // #654: no title bar above Search (#644), so the field
                            // keeps a little air under the status bar.
                            modifier = Modifier.padding(top = 12.dp),
                            filters = searchFacets,
                            people = people,
                            history = searchHistory,
                            onlyTheseFacesAvailable = false,
                            suggestionSources = searchChoices?.choices.orEmpty(),
                            currentPermissionEpoch = searchGrant,
                            hasResults = searchActive && !searched.isNullOrEmpty(),
                            onOpenLibraryStatus = { settingsOpen = true },
                            onFiltersChange = { incoming ->
                                searchSession = searchSession.changeFacets(
                                    incoming,
                                    currentSearchSession?.photoScopeEpoch,
                                    currentSearchSession?.peopleGeneration,
                                    latestSearchGrant.value,
                                    latestPeopleGeneration.value)
                            },
                            onSubmittedQuery = { term ->
                                searchSession = searchSession.submit(
                                    term,
                                    currentSearchSession?.photoScopeEpoch,
                                    currentSearchSession?.peopleGeneration,
                                    latestSearchGrant.value,
                                    latestPeopleGeneration.value)
                            },
                            onClearHistory = {
                                searchSession = searchSession.clearRecents(
                                    currentSearchSession?.photoScopeEpoch,
                                    currentSearchSession?.peopleGeneration,
                                    latestSearchGrant.value,
                                    latestPeopleGeneration.value)
                            },
                            onSearchByFace = {
                                faceSession.clear()
                                faceOrigin = null
                                faceStep = FaceSearchStep.ChooseReference
                            },
                            onFindSimilar = {
                                similarSession.clear()
                                similarOrigin = null
                                similarStep = FindSimilarStep.ChooseReference
                            },
                            namedPeople = namedSearchPeople,
                            personAvatar = { person ->
                                val face = nativeSearchPeople
                                    .firstOrNull { it.first.key == person.key }?.second
                                if (face != null) {
                                    FaceCropPreview(discoveryHost,
                                        discovery.status?.displayEpoch, face.localHex,
                                        person.name,
                                        imageModifier = Modifier.fillMaxSize(),
                                        fallback = {
                                            Icon(Icons.Filled.Person, null,
                                                Modifier.fillMaxSize().padding(6.dp))
                                        })
                                } else {
                                    Icon(Icons.Filled.Person, null,
                                        Modifier.fillMaxSize().padding(6.dp))
                                }
                            }
                        )
                        // Preserve direct reviewed-People album navigation alongside
                        // the richer multi-person AND filter, not automatic identity inference.
                        PeopleSearchShortcuts(people, searchFacets.text, media) { selectedPerson ->
                            // Reuse the existing Person album, B&W folder filter,
                            // original media ordering and filmstrip viewer.
                            personBwFolderOnly = false
                            personFilter = selectedPerson
                        }
                        // #644: at rest Search shows only the field, recents and
                        // suggestions; results appear once there is a query.
                        // Key by identity, not Compose's structural List.equals over 150k rows.
                        if (searchActive) key(CatalogSourceKey(visible)) {
                        AtlasFeatureGrid(
                            items = visible,
                            state = searchGridState,
                            busy = busy || sortPending || searchPending,
                            onOpen = { openViewer(visible, it.uri) },
                            viewKey = "search",
                            catalogueEpoch = publishedGallery.epoch,
                            // Pinch-only, as on Samsung's grids: no View as row.
                            showViewAs = false,
                            emptyText = "No results"
                        )
                        }
                    }
                    tab == 2 -> {
                        // People portraits are derived from the already permission-gated
                        // inventory. The async cover projection is the sole 150k scan;
                        // Compose never builds an all-media lookup here.
                        val peopleEpoch = publishedGallery.epoch.toString()
                        val permittedCovers = rememberAsyncReviewedPeopleCards(
                            media, people, peopleEpoch, active = true)
                        val nativeAlbum = nativePersonAlbum
                        if (peopleReviewOpen) {
                            PeopleReviewRoute(discoveryHost, searchGrant,
                                onClose = { peopleReviewOpen = false })
                        } else if (nativeAlbum != null) {
                            BackHandler { nativePersonAlbum = null }
                            NativePersonAlbumRoute(
                                host = discoveryHost,
                                galleryGrant = searchGrant,
                                request = nativeAlbum,
                                people = discovery.status?.people,
                                sorted = sorted,
                                gridState = nativePersonGridState,
                                catalogueEpoch = publishedGallery.epoch,
                                onBack = { nativePersonAlbum = null },
                                onOpen = { items, record -> openViewer(items, record.uri) },
                                // #551: a merge or first name follows the surviving person.
                                onRetarget = { nativePersonAlbum = it }
                            )
                        } else if (hiddenPeopleOpen) {
                            BackHandler { hiddenPeopleOpen = false }
                            HiddenPeopleRoute(
                                host = discoveryHost,
                                people = discovery.status?.people,
                                onBack = { hiddenPeopleOpen = false },
                                onOpen = { nativePersonAlbum = it }
                            )
                        } else if (people.isNotEmpty() && permittedCovers == null) {
                            Text("Matching your reviewed People to accessible photos…",
                                Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            // U returns only reviewed names and covers from the
                            // same authorized, primary-volume publication.
                            val profiles = permittedCovers?.projection?.profiles.orEmpty()
                            // #516/#643: People is the plain Samsung grid. ⋮ holds
                            // People actions only; the visual index, People numbers,
                            // photo details and the V4 import sit in Settings.
                            var samePersonQueueOpen by remember { mutableStateOf(false) }
                            val peopleMenu = listOf(
                                // #584: Samsung's People ⋮ → Hidden; Show this person
                                // on a hidden person's page brings it back.
                                AtlasHeaderMenuItem("Hidden", "atlas-people-hidden-open",
                                    onClick = { hiddenPeopleOpen = true }),
                                // #580: every open "Same person?" question in a row.
                                AtlasHeaderMenuItem("Same person?", "atlas-people-same-open",
                                    onClick = { samePersonQueueOpen = true }))
                            if (samePersonQueueOpen) SamePersonQueueRoute(
                                discoveryHost, discovery.status?.people) {
                                samePersonQueueOpen = false
                            }
                            PeopleHomeScreen(
                                state = PeopleHomeUiState(
                                    discovery = discovery.status?.ui ?: FaceDiscoveryUiState(),
                                    profiles = profiles,
                                    v4SyncSummary = v4SyncState
                                ),
                                actions = discovery.status?.actions ?: PeopleActionAvailability(),
                                onOpenProfile = { id ->
                                    personBwFolderOnly = false
                                    personFilter = id
                                },
                                // The host's own single-use face epoch, never the gallery
                                // refresh: a refresh is not face discovery.
                                currentPermissionEpoch = discovery.status?.displayEpoch,
                                onStartDiscovery = discovery.onStart,
                                onPauseDiscovery = discovery.onPause,
                                onResumeDiscovery = discovery.onResume,
                                onOpenReview = { peopleReviewOpen = true },
                                // Native reviewed people (#434): the host's snapshot of
                                // names that still hold a confirmed face, opened as a
                                // grant-resolved person album.
                                nativePeopleReview = discovery.status?.people,
                                nativePeopleSourceGeneration =
                                    discovery.status?.people?.sourceGeneration,
                                onOpenNativePersonAlbum = { person ->
                                    discovery.status?.people?.people?.get(person)?.name?.let { name ->
                                        nativePersonAlbum = NativePersonAlbumRequest(person, name)
                                    }
                                },
                                liveNativePeopleAtAction = {
                                    discoveryHost.liveHomePeople(discovery.status?.displayEpoch)
                                },
                                menuItems = peopleMenu,
                                onOpenLibraryStatus = { settingsOpen = true },
                                portrait = { profile ->
                                    val record = profile.coverUri?.let {
                                        permittedCovers?.authorizedCoversByUri?.get(it)
                                    }
                                    if (record != null && isAuthorizedReviewedPrimaryImage(record) &&
                                        record.uri == profile.coverUri) {
                                        MediaPreview(record, Modifier.fillMaxSize(), ContentScale.Crop, 400)
                                    } else {
                                        Icon(Icons.Filled.Person, "No verified portrait",
                                            Modifier.fillMaxSize().padding(28.dp))
                                    }
                                },
                                nativePortrait = { face ->
                                    FaceCropPreview(discoveryHost,
                                        discovery.status?.displayEpoch, face.localHex,
                                        "No verified portrait",
                                        imageModifier = Modifier.fillMaxSize(),
                                        fallback = {
                                            Icon(Icons.Filled.Person, "No verified portrait",
                                                Modifier.fillMaxSize().padding(28.dp))
                                        })
                                }
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        }
    }
}

@Composable
private fun AlbumStrip(
    albums: List<String>, selected: String?, indexing: Boolean,
    onSelect: (String?) -> Unit
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected == null, onClick = { onSelect(null) }, label = { Text("All") }) }
        if (indexing) item {
            Text("Indexing albums locally…",
                modifier = Modifier.padding(vertical = 11.dp)
                    .testTag("atlas-albums-indexing"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
        }
        items(albums, key = { it }) { path ->
            val label = path.trimEnd('/').substringAfterLast('/').ifBlank { "Root" }
            FilterChip(selected == path, onClick = { onSelect(path) },
                label = { Text(label, maxLines = 1) })
        }
    }
}

/**
 * The same native reviewed-People album content is used after Search and
 * from the People tab. Keep the B&W location filter, per-album grid state,
 * media viewer and Back behavior as one testable navigation surface.
 */
@Composable
internal fun PeopleAlbumContent(
    visible: List<MediaRecord>,
    gridState: LazyGridState,
    busy: Boolean,
    blackAndWhiteFolderOnly: Boolean,
    onBack: () -> Unit,
    onToggleBlackAndWhite: () -> Unit,
    catalogueEpoch: Long = 0L,
    onOpen: (MediaRecord) -> Unit
) {
    // Explicitly own vertical layout. Previously the component relied on the
    // enclosing screen's Column; in an isolated route its filter, count and
    // grid could overlap and misroute a tap to an image beneath the filter.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("atlas-person-album-back")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Text("All people")
            }
            FilterChip(
                selected = blackAndWhiteFolderOnly,
                onClick = onToggleBlackAndWhite,
                label = { Text("B&W folder") },
                modifier = Modifier.testTag("atlas-person-album-bw")
            )
        }
        Text(
            "${visible.size} linked " +
                if (blackAndWhiteFolderOnly) "B&W-folder photos" else "photos and videos",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                .testTag("atlas-person-album-count")
        )
        // #527: a person album pinches into the same bar-free real-ratio rows
        // as Photos and albums, and pinches back to the square grid. The Back
        // and B&W controls above stay in place at every zoom stop.
        var originalProportions by rememberSaveable { mutableStateOf(false) }
        var returnDensity by remember { mutableStateOf<GridDensity?>(null) }
        // Key by identity, not Compose's structural List.equals over 150k rows.
        key(CatalogSourceKey(visible)) {
        if (originalProportions) {
            AtlasRatioGallery(
                items = visible,
                aspectRatioOf = { it.displayAspectRatioOrNull() },
                onOpen = onOpen,
                catalogueEpoch = catalogueEpoch,
                onCompactGridRequested = { request ->
                    if (request.catalogueEpoch == catalogueEpoch) {
                        returnDensity = request.destination?.squareDensityOrNull()
                            ?: GridDensity.LARGE
                        originalProportions = false
                    }
                }
            )
        } else {
        AtlasFeatureGrid(
            items = visible,
            state = gridState,
            busy = busy,
            onOpen = onOpen,
            viewKey = "people",
            catalogueEpoch = catalogueEpoch,
            onOriginalProportionsRequested = { request ->
                if (request.catalogueEpoch == catalogueEpoch &&
                    request.destination == GalleryZoomStop.ORIGINAL) {
                    originalProportions = true
                }
            },
            entryDensity = returnDensity
        )
        }
        }
    }
}

@Composable
private fun GalleryGrid(
    items: List<MediaRecord>, state: LazyGridState, busy: Boolean,
    onOpen: (MediaRecord) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (busy) "Loading media…" else "No photos here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp), state = state,
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.uri }) { media ->
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                .background(Surface)
                .testTag("atlas-grid-media-${media.id}-${if (media.isVideo) "video" else "photo"}")
                .clickable { onOpen(media) }) {
                MediaPreview(media, Modifier.fillMaxSize(), ContentScale.Crop, 400)
                if (media.isVideo) Icon(Icons.Filled.PlayCircle, "Video",
                    Modifier.align(Alignment.BottomEnd).padding(6.dp), tint = Color.White)
            }
        }
    }
}

@Composable
fun MediaPreview(
    media: MediaRecord, modifier: Modifier, scale: ContentScale, pixels: Int,
    onLoaded: (() -> Unit)? = null, observeLoaded: Boolean = false,
    forceFresh: Boolean = false, refreshRevision: Long = 0L,
    /**
     * #527: called with the decoded image's oriented pixel size. Only the
     * real-ratio grid passes it, and only for media whose MediaStore WIDTH or
     * HEIGHT is missing, so normal cells keep the callback-free path below.
     */
    onImageSize: ((width: Int, height: Int) -> Unit)? = null,
    /**
     * #555: a Photos/album grid tile. It draws the photo's micro thumbnail
     * under the sharp tile, so a tile that is still decoding during a fling
     * shows the photo instead of a grey cell (Samsung T8: small first, sharp
     * later). With [sharp] false (fast-scroll handle dragged) only the micro
     * thumbnail is requested, like Samsung's scrub thumbnails.
     */
    gridTile: Boolean = false,
    sharp: Boolean = true
) {
    val context = LocalContext.current
    val publicationEpoch = LocalGalleryPublicationEpoch.current
    val imageRevision = if (forceFresh) refreshRevision else 0L
    val request = remember(media, pixels, publicationEpoch, forceFresh, imageRevision) {
        mediaPreviewRequest(context, media, pixels, publicationEpoch, forceFresh, imageRevision)
    }
    if (gridTile && !observeLoaded && !forceFresh) {
        GridTilePreview(media, request, modifier, scale, pixels, publicationEpoch, onImageSize, sharp)
    } else if (onImageSize != null && !observeLoaded) {
        val currentOnImageSize by rememberUpdatedState(onImageSize)
        AsyncImage(model = request, contentDescription = media.name,
            contentScale = scale, modifier = modifier,
            onSuccess = { success ->
                val image = success.result.image
                if (image.width > 0 && image.height > 0) currentOnImageSize(image.width, image.height)
            })
    } else if (!observeLoaded) {
        // Normal gallery path stays identical to the original: no extra
        // state/effects or image callbacks in a 150,000-photo catalogue.
        AsyncImage(model = request, contentDescription = media.name,
            contentScale = scale, modifier = modifier)
    } else {
        // A neighboring page may finish decoding while still offscreen.
        // Instrumented viewing can observe that existing loaded state
        // when the page gets focused without counting a second decode.
        var previewLoaded by remember(request) { mutableStateOf(false) }
        val currentOnLoaded by rememberUpdatedState(onLoaded)
        LaunchedEffect(previewLoaded, onLoaded != null) {
            if (previewLoaded) currentOnLoaded?.invoke()
        }
        AsyncImage(model = request, contentDescription = media.name,
            contentScale = scale, modifier = modifier,
            onSuccess = { previewLoaded = true },
            onError = { previewLoaded = false })
    }
}

/**
 * #555 grid tile: micro thumbnail underneath, sharp tile on top. The micro
 * layer comes from ATLAS's own cache, checked synchronously here, so a tile
 * the prefetcher warmed shows its photo on its first frame. It is skipped
 * when the sharp tile is already in memory, and when the micro tier is off
 * (partial media access, unknown revision). While the grid flings fast (or
 * the fast-scroll handle is dragged) the sharp request waits, so it does not
 * compete with the micro thumbnails (Samsung T8). No layout change: both
 * layers fill the cell.
 */
@Composable
private fun GridTilePreview(
    media: MediaRecord, request: ImageRequest, modifier: Modifier, scale: ContentScale,
    pixels: Int, publicationEpoch: Long,
    onImageSize: ((width: Int, height: Int) -> Unit)?, sharp: Boolean
) {
    val context = LocalContext.current
    val probe = ThumbnailPipeline.probe
    val sharpCached = remember(request) {
        request.memoryCacheKey?.let { key ->
            SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(key)) != null
        } == true
    }
    val microKey = remember(media, publicationEpoch, sharpCached) {
        if (sharpCached || !MicroThumbnails.active) null
        else microThumbnailMemoryKey(media, publicationEpoch)
    }
    var micro by remember(microKey) { mutableStateOf(microKey?.let { MicroThumbnails.cached(it) }) }
    val fastScroll = ThumbnailPipeline.fastScroll.value
    // Once asked for, a tile keeps its sharp layer: a fling starting again
    // must never swap a sharp photo back to its micro thumbnail.
    val sharpRequested = remember(request) { BooleanArray(1) }
    if (sharpCached || microKey == null || (sharp && !fastScroll)) sharpRequested[0] = true
    val showSharp = sharpRequested[0]
    if (microKey != null && micro == null) {
        // A stored micro copy is a cheap first frame. Making a new one is only
        // worth it while the sharp tile waits (fast fling, scrub); otherwise
        // it would decode the photo twice and take CPU from layout work.
        LaunchedEffect(microKey, showSharp) {
            micro = runCatching { MicroThumbnails.load(media, publicationEpoch, generate = !showSharp) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
        }
    }
    val currentOnImageSize by rememberUpdatedState(onImageSize)
    if (probe != null) {
        SideEffect { probe.record(media.uri, isMicroLayer = true, hasImage = micro != null) }
    }
    Box(modifier) {
        val shown = micro
        if (shown != null) {
            val image = remember(shown) { shown.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = scale,
                modifier = Modifier.matchParentSize())
        }
        if (showSharp) {
            AsyncImage(model = request, contentDescription = media.name,
                contentScale = scale, modifier = Modifier.matchParentSize(),
                onState = { state ->
                    probe?.record(media.uri, isMicroLayer = false, hasImage = state.painter != null)
                    if (state is AsyncImagePainter.State.Success) {
                        val image = state.result.image
                        if (image.width > 0 && image.height > 0) currentOnImageSize?.invoke(image.width, image.height)
                    }
                })
        } else if (probe != null) {
            SideEffect { probe.record(media.uri, isMicroLayer = false, hasImage = false) }
        }
    }
}

/**
 * The one image request behind every [MediaPreview], shared with the grid
 * prefetcher (#555) so a warmed tile hits the exact same memory-cache key.
 */
internal fun mediaPreviewRequest(
    context: android.content.Context, media: MediaRecord, pixels: Int,
    publicationEpoch: Long, forceFresh: Boolean = false, imageRevision: Long = 0L
): ImageRequest {
    // An unknown provider revision cannot justify reusing a URI-only decoded
    // bitmap. Never use thumbnail keys as authorization; the caller's published
    // inventory and permission barrier remain the source of truth.
    val cacheKey = media.presentationCacheKeyOrNull(
        requestedPixels = pixels,
        catalogueEpoch = publicationEpoch,
        editorRevision = imageRevision
    )
    return ImageRequest.Builder(context).data(media.uri).size(pixels, pixels)
        .apply {
            if (cacheKey != null) {
                memoryCacheKey(cacheKey)
                diskCacheKey(cacheKey)
            } else {
                memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                diskCachePolicy(coil3.request.CachePolicy.DISABLED)
            }
            // The external editor can update content before MediaStore's
            // generation catches up. Bypass disk on its first return.
            if (forceFresh) diskCachePolicy(coil3.request.CachePolicy.DISABLED)
        }
        .crossfade(false).build()
}

@Composable
private fun PeopleScreen(
    people: List<PersonEntry>, media: List<MediaRecord>,
    onImport: () -> Unit, onLink: () -> Unit,
    syncStatus: String, onSelect: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text("${people.size} named people", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onImport) { Icon(Icons.Filled.FileUpload, null); Text("Import once") }
                TextButton(onClick = onLink) { Icon(Icons.Filled.Sync, null); Text("Link live V4") }
            }
            Text(syncStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        if (people.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bring your existing names into ATLAS", style = MaterialTheme.typography.titleLarge)
                Text("Export confirmed labels from Monochrome Studio V4 as CSV, then import that file here. This keeps V4 untouched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport) { Text("Select V4 CSV") }
            }
        } else {
            val snapshot = rememberAsyncPeopleCovers(media, people)
            val covers = currentPeopleCoversOrNull(snapshot, media, people)
            val matched = covers?.accessibleCounts.orEmpty()
            Text(
                "Only photos accessible at their exported V4 paths appear in this gallery.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
            LazyColumn(contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(people, key = { it.id }) { person ->
                    val cover = covers?.coversByPersonId?.get(person.id)
                    val linked = matched[person.id] ?: 0
                    val unmatched = person.count - linked
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Surface).clickable { onSelect(person.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(DeepBg),
                            contentAlignment = Alignment.Center) {
                            if (cover != null) MediaPreview(cover, Modifier.fillMaxSize(), ContentScale.Crop, 180)
                            else Icon(Icons.Filled.Person, null, tint = Mint)
                        }
                        Column {
                            Text(person.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (covers == null) "Matching confirmed photos locally…" else
                                    "${linked} accessible" +
                                        if (unmatched > 0) " · ${unmatched} unmatched" else " photos",
                                color = if (covers != null && unmatched > 0) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(
    total: Int, people: Int, busy: Boolean, error: String?, refresh: () -> Unit,
    peopleTools: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("On-device library", style = MaterialTheme.typography.titleLarge)
        Text("$total accessible media · $people imported people profiles", color = Mint)
        Text(if (busy) "Refreshing the gallery index…" else "Ready to browse",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = refresh) { Text("Refresh photos") }
        HorizontalDivider()
        HorizontalDivider()
        peopleTools()
    }
}
