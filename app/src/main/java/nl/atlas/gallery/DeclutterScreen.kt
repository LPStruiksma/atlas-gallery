package nl.atlas.gallery

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurecuration.cleanupSize
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasPageColor
import nl.atlas.gallery.featuresearch.AtlasSearch
import nl.atlas.gallery.featuresearch.AtlasSearchScope
import nl.atlas.gallery.featuresearch.scopedNamedMatches
import kotlinx.coroutines.delay

/** #663: the same phone-style sizes as Photo DNA ("1 KB", "4.2 MB"). */
private fun readableBytes(size: Long): String = cleanupSize(size)

/**
 * Source-level wrapper (TIDY-1): with a [keepStore] and [scopeKey] it offers to
 * resume where saved Keeps end and records new Keeps. Without them it behaves
 * exactly like the plain folder review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun DeclutterScreen(
    folder: String,
    deck: List<MediaRecord>,
    onExit: () -> Unit,
    onSearchWholeLibrary: () -> Unit = {},
    onTrashRequest: (List<MediaRecord>) -> Unit,
    verifying: Boolean = false,
    scopeKey: String? = null,
    keepStore: DeclutterKeepStore? = null
) {
    if (scopeKey == null || keepStore == null) {
        DeclutterReviewScreen(folder, deck, onExit, onSearchWholeLibrary,
            onTrashRequest, verifying, 0, deck.size, null, null, null)
        return
    }
    val sourceKey = CatalogSourceKey(deck)
    // Bumped when the Keeps are cleared so progress and the resume choice rebuild.
    var generation by remember(sourceKey, scopeKey) { mutableIntStateOf(0) }
    val progress = remember(sourceKey, scopeKey, generation) {
        DeclutterProgress.of(deck, keepStore.load(scopeKey))
    }
    // null = still asking; false = start again; true = resume.
    var resume by remember(sourceKey, scopeKey, generation) {
        mutableStateOf<Boolean?>(if (progress.reviewed == 0) false else null)
    }
    var confirmingReset by remember(sourceKey, scopeKey, generation) { mutableStateOf(false) }
    val chosen = resume
    if (chosen == null) {
        if (confirmingReset) {
            DeclutterResetDialog(
                kept = progress.reviewed,
                onConfirm = {
                    keepStore.clear(scopeKey)
                    confirmingReset = false
                    generation++
                },
                onCancel = { confirmingReset = false }
            )
        }
        if (progress.complete) {
            // Every item is kept: a deliberate finished state, never a resume offer
            // for a photo that does not exist. Nothing resets without a confirmation.
            Surface(Modifier.fillMaxSize(), color = AtlasPageColor,
                contentColor = MaterialTheme.colorScheme.onBackground) {
                Column(
                    Modifier.fillMaxSize()
                        .testTag("atlas-declutter-complete")
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AtlasGalleryHeader(
                        title = "Declutter",
                        onBack = onExit,
                        backDescription = "Back to Gallery"
                    )
                    DeclutterCompletedBody(
                        total = progress.total,
                        onExit = onExit,
                        onRestart = { confirmingReset = true },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        } else if (!confirmingReset) {
            ModalBottomSheet(
                onDismissRequest = onExit,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                modifier = Modifier.testTag("atlas-declutter-resume-dialog")
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Resume review?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Reviewed ${progress.reviewed} of ${progress.total}. " +
                            "Resume with the first photo you have not kept yet.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = { resume = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("atlas-declutter-resume")
                    ) { Text("Resume") }
                    OutlinedButton(
                        onClick = { confirmingReset = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("atlas-declutter-start-again")
                    ) { Text("Start again") }
                    TextButton(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("atlas-declutter-not-now")
                    ) { Text("Not now") }
                }
            }
        }
        if (!progress.complete) Box(Modifier.fillMaxSize())
        return
    }
    // `progress` is frozen per source, so new Keeps never reshuffle this deck.
    // Fresh start keeps the full source; resume leaves only unkept items.
    val reviewDeck = remember(sourceKey, scopeKey, chosen, generation) {
        sourceDeclutterDeck(if (chosen) progress.unreviewed else deck)
    }
    DeclutterReviewScreen(
        folder, reviewDeck, onExit, onSearchWholeLibrary, onTrashRequest, verifying,
        reviewedBefore = if (chosen) progress.reviewed else 0,
        sourceTotal = progress.total,
        onKeep = { keepStore.add(scopeKey, declutterKeepIdentity(it)) },
        onUnkeep = { keepStore.remove(scopeKey, declutterKeepIdentity(it)) },
        onRestartSource = { keepStore.clear(scopeKey); generation++ }
    )
}

/** Completed source: every item kept. Safe exit, restart only via a confirmation. */
@Composable
private fun DeclutterCompletedBody(
    total: Int,
    onExit: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("All $total items reviewed.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("You kept every photo here. Nothing was deleted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("atlas-declutter-complete-back")
        ) { Text("Back to album") }
        TextButton(
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("atlas-declutter-complete-restart")
        ) { Text("Start this review again") }
    }
}

/** Own confirmation for clearing a source's Keeps. Nothing is deleted. */
@Composable
private fun DeclutterResetDialog(kept: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Start this review again?") },
        text = { Text("Your $kept kept photos here will come back for review. Nothing is deleted.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-declutter-reset-confirm")
            ) { Text("Start again") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-declutter-reset-cancel")
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("atlas-declutter-reset-dialog")
    )
}

/**
 * Left = stage for trash, right = keep, both reversible until the Android OS
 * approval request. No direct file or MediaStore mutations occur here.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
private fun DeclutterReviewScreen(
    folder: String,
    deck: List<MediaRecord>,
    onExit: () -> Unit,
    onSearchWholeLibrary: () -> Unit,
    onTrashRequest: (List<MediaRecord>) -> Unit,
    verifying: Boolean,
    reviewedBefore: Int,
    sourceTotal: Int,
    onKeep: ((MediaRecord) -> Unit)?,
    onUnkeep: ((MediaRecord) -> Unit)?,
    onRestartSource: (() -> Unit)?
) {
    // The review deck may contain 150k entries; never ask Compose to
    // structurally compare List contents in remember/effect keys. Fresh
    // immutable deck generations reset safely even with equal metadata.
    var searchText by remember(CatalogSourceKey(deck)) { mutableStateOf("") }
    var session by remember(CatalogSourceKey(deck)) { mutableStateOf(DeclutterSession()) }
    var reviewing by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    var confirming by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    // FIND-5 (#341): the panel's scope, and the D1 "leave Declutter?" step.
    var searchScope by remember(CatalogSourceKey(deck)) { mutableStateOf(AtlasSearchScope.THIS_SCREEN) }
    var confirmingLeave by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    var confirmingRestart by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    var dragX by remember(CatalogSourceKey(deck), session.position) { mutableFloatStateOf(0f) }
    // A held swipe is not a settled card: postpone hires/player allocation.
    var dragging by remember(CatalogSourceKey(deck), session.position) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val context = LocalContext.current
    // Status stays O(1) on each swipe. Build the actual bounded Trash
    // review list only on review/confirm, not after every Keep decision.
    val pending = remember(CatalogSourceKey(deck), session, reviewing, confirming) {
        if (reviewing || confirming) session.staged() else emptyList()
    }
    val pendingCount = session.stagedCount
    val pendingBytes = session.stagedBytes()
    val current = deck.getOrNull(session.position)
    // Persist only positive Keeps; a stage or skip is never saved.
    fun decide(discard: Boolean) {
        val media = deck.getOrNull(session.position) ?: return
        session = session.decide(deck, discard)
        if (!discard) onKeep?.invoke(media)
    }
    fun undoLast() {
        val last = session.lastDecision ?: return
        session = session.undo()
        // The item returns to review, so its saved Keep (if any) must go.
        onUnkeep?.invoke(last.media)
    }
    // Rapid left/right swipes should not instantiate a new ExoPlayer
    // for each briefly focused video. Show the existing thumbnail and
    // instantiate the actual player only after the video settles.
    // Card-local state: an earlier card's settled=true MUST NEVER cause
    // an eagerly allocated ExoPlayer (or 1600px decode) on the next card's
    // first Compose frame, before its LaunchedEffect has restarted.
    var mediaSettled by remember(CatalogSourceKey(deck), current?.uri, reviewing, dragging, verifying) {
        mutableStateOf(false)
    }
    // Source replacement with the same URI resets mediaSettled above; its
    // settle effect must restart for that same new deck generation as well.
    // Otherwise a fresh deck can remain permanently stuck on the low-res
    // poster (or never mount the paused video player).
    LaunchedEffect(CatalogSourceKey(deck), current?.uri, reviewing, dragging, verifying) {
        if (current != null && !reviewing && !dragging && !verifying) {
            // Low-res poster appears immediately. Only decode full-size
            // photos or allocate Media3 after the user's swipe settles.
            delay(if (current.isVideo) 180L else 110L)
            mediaSettled = true
        }
    }
    LaunchedEffect(CatalogSourceKey(deck), session.position, reviewing, verifying, dragging) {
        if (reviewing || verifying || dragging) return@LaunchedEffect
        val neighbors = nextDeclutterPreviewIndices(session.position, deck.size)
        if (neighbors.isEmpty()) return@LaunchedEffect
        // The pending request is canceled when the user moves to another
        // card. Never decode a whole album or pre-create ExoPlayers.
        delay(65)
        val loader = SingletonImageLoader.get(context)
        prewarmDeclutterPreviewIndices(neighbors) { index ->
            loader.execute(
                ImageRequest.Builder(context)
                    .data(deck[index].uri)
                    .size(800, 800)
                    .crossfade(false)
                    .build()
            )
        }
    }
    val heavyReady = shouldRenderDeclutterHeavy(
        mediaSettled, dragging, reviewing, verifying
    )
    val maxTrashBatch = 200
    // Whole library leaves this transient route. It never submits Trash; the
    // staged session ends exactly as Back ends it today.
    val searchWholeLibrary = {
        confirmingLeave = false
        searchText = ""
        onSearchWholeLibrary()
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = {
                confirmingLeave = false
                searchScope = AtlasSearchScope.THIS_SCREEN
            },
            title = { Text("Search whole library?") },
            text = { Text(
                "This leaves Declutter. Your " +
                (if (pendingCount == 1) "1 staged choice" else "$pendingCount staged choices") +
                " will be cleared. Nothing is moved to Trash."
            ) },
            confirmButton = {
                TextButton(
                    onClick = searchWholeLibrary,
                    modifier = Modifier.testTag("atlas-declutter-leave-search")
                ) { Text("Search library") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmingLeave = false
                        searchScope = AtlasSearchScope.THIS_SCREEN
                    },
                    modifier = Modifier.testTag("atlas-declutter-leave-stay")
                ) { Text("Stay") }
            },
            modifier = Modifier.testTag("atlas-declutter-leave-dialog")
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move to Android Trash?") },
            text = { Text(
                "Stage complete: ${pendingCount} photos/videos, approximately " +
                "${readableBytes(pendingBytes)}. Android will ask for permission " +
                "before moving anything to Trash. You can cancel without changes."
            ) },
            confirmButton = {
                TextButton(
                    enabled = !verifying,
                    onClick = {
                        confirming = false
                        onTrashRequest(pending)
                    },
                    modifier = Modifier.testTag("atlas-declutter-confirm")
                ) { Text("Continue to Android confirmation") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }

    // The page and its header must inherit the same light/dark ink as other
    // gallery routes. A background modifier alone does not set LocalContentColor.
    Surface(Modifier.fillMaxSize(), color = AtlasPageColor,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(
            Modifier.fillMaxSize()
                .testTag("atlas-declutter-surface")
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        AtlasGalleryHeader(
            title = "Declutter",
            onBack = onExit,
            backDescription = "Back to Gallery",
            actions = {
                AtlasSearch(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    initialScope = AtlasSearchScope.THIS_SCREEN,
                    selectedScope = searchScope,
                    onScopeChange = { scope ->
                        if (scope == AtlasSearchScope.LIBRARY) {
                            // A scope choice navigates to a separate host destination.
                            // It never changes the session, staged list or Trash request.
                            if (pendingCount > 0) {
                                searchScope = AtlasSearchScope.LIBRARY
                                confirmingLeave = true
                            } else {
                                searchWholeLibrary()
                            }
                        } else {
                            searchScope = AtlasSearchScope.THIS_SCREEN
                        }
                    },
                    // Frame E: while swiping, search never filters the deck.
                    suggestionsTool = if (reviewing) null else {
                        {
                            Column(
                                Modifier.padding(horizontal = 12.dp)
                                    .testTag("atlas-declutter-search-staged-note")
                            ) {
                                Text(
                                    when (pendingCount) {
                                        0 -> "Searches your staged files. None yet."
                                        1 -> "Searches your 1 staged file."
                                        else -> "Searches your $pendingCount staged files."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (pendingCount > 0) TextButton(
                                    onClick = { reviewing = true },
                                    enabled = !verifying,
                                    modifier = Modifier.testTag("atlas-declutter-search-show-staged")
                                ) { Text("Show staged files") }
                            }
                        }
                    }
                )
            }
        )
        if (verifying) {
            LinearProgressIndicator(Modifier.fillMaxWidth()
                .testTag("atlas-declutter-verifying"))
        }
        if (onKeep != null && sourceTotal > 0) {
            Text(
                "Reviewed ${reviewedBefore + session.position} of $sourceTotal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("atlas-declutter-progress")
            )
        }
        if (reviewing) {
            val visiblePending = scopedNamedMatches(pending, searchText) { it.name + " " + it.album }
            // #702: only the body scrolls. The Move action is a sibling
            // anchored at the bottom of the remaining IME-safe area, rather
            // than the last child measured after a weighted list/Return button.
            // safeDrawing above already accounts for the keyboard; do not add
            // imePadding here or the inset will be applied twice.
            // The search popup owns a separate IME window. On S24+ landscape,
            // its visible top is 57px above the Activity review viewport bottom
            // even after safeDrawing; reserve a small non-IME clearance for the
            // anchored action rather than stacking imePadding on safeDrawing.
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp)) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("atlas-declutter-review-body"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 56.dp)
                ) {
                    item {
                        Text("Review staged files", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                    // S1/S2: a plain centered message, never a fake search hit.
                    if (visiblePending.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxHeight(0.65f).fillMaxWidth(),
                                contentAlignment = Alignment.Center) {
                                Column(Modifier.testTag("atlas-declutter-search-empty"),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (pending.isEmpty()) "No staged files yet"
                                        else "No matching staged files",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        if (pending.isEmpty()) "Swipe left on a photo to stage it."
                                        else "Clear the search to see all ${pending.size}.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(visiblePending, key = { it.uri }) { media ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MediaPreview(media, Modifier.size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .testTag("atlas-declutter-thumbnail"), ContentScale.Crop, 160)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(media.name, maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(readableBytes(media.sizeBytes),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    enabled = !verifying,
                                    onClick = {
                                        session = session.unstage(media.uri)
                                        onKeep?.invoke(media)
                                    }
                                ) { Text("Keep") }
                            }
                        }
                    }
                    if (pending.size > maxTrashBatch) {
                        item {
                            Text("Review at most $maxTrashBatch files per Trash request. " +
                                 "Unstage others or split into another session.")
                        }
                    }
                    // In short landscape windows this action stays reachable
                    // via the same scroll, instead of compressing the primary CTA.
                    item {
                        TextButton(onClick = { reviewing = false }) {
                            Text("Return to swiping")
                        }
                    }
                }
                // The review viewport can shrink to zero visible rows with the
                // landscape IME, but the 48dp counted Move target must not.
                Button(
                    onClick = { confirming = true },
                    enabled = !verifying && pending.isNotEmpty() &&
                        pending.size <= maxTrashBatch,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("atlas-declutter-trash")
                ) { Text("Move ${pendingCount} to Android Trash…") }
            }
        } else if (current == null && pendingCount == 0 && onKeep != null && onRestartSource != null) {
            // The last photo was kept in this session: same completed state as a reopen.
            DeclutterCompletedBody(
                total = sourceTotal,
                onExit = onExit,
                onRestart = { confirmingRestart = true },
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("atlas-declutter-complete")
            )
            if (confirmingRestart) {
                DeclutterResetDialog(
                    kept = sourceTotal,
                    onConfirm = { confirmingRestart = false; onRestartSource() },
                    onCancel = { confirmingRestart = false }
                )
            }
        } else if (current == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Folder review complete", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("${pendingCount} staged for review",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { reviewing = true },
                modifier = Modifier.fillMaxWidth().testTag("atlas-declutter-review")) {
                Text("Review staged (${pendingCount})")
            }
            TextButton(onClick = { undoLast() },
                enabled = !verifying && session.position > 0) { Text("Undo last decision") }
        } else {
            Text(
                "${current.name} · ${readableBytes(current.sizeBytes)}" +
                (if (current.isVideo) " · video" else ""),
                maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .graphicsLayer { translationX = dragX }
                        .pointerInput(current.uri, session.position, verifying) {
                            if (!verifying) detectHorizontalDragGestures(
                                onDragStart = { dragging = true },
                                onDragCancel = { dragX = 0f; dragging = false },
                                onDragEnd = {
                                    val delta = dragX
                                    dragX = 0f
                                    dragging = false
                                    if (delta < -threshold) decide(true)
                                    else if (delta > threshold) decide(false)
                                },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    dragX += amount
                                }
                            )
                        }
                        .testTag("atlas-declutter-card")
                ) {
                    if (current.isVideo) {
                        if (heavyReady) {
                            VideoPage(current.uri)
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("atlas-declutter-video-poster"),
                                contentAlignment = Alignment.Center
                            ) {
                                MediaPreview(current, Modifier.fillMaxSize(),
                                    ContentScale.Fit, 800)
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Video preview",
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        // Let Coil show the inexpensive prefetched preview
                        // immediately. A stationary card upgrades to a
                        // sharper image after the gesture settles.
                        Box(Modifier.fillMaxSize()
                            .testTag("atlas-declutter-photo-progressive")) {
                            MediaPreview(current, Modifier.fillMaxSize(),
                                ContentScale.Fit, 800)
                            if (heavyReady) {
                                MediaPreview(current, Modifier.fillMaxSize()
                                    .testTag("atlas-declutter-photo-hires"),
                                    ContentScale.Fit, 1600)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { decide(true) },
                    enabled = !verifying,
                    modifier = Modifier.weight(1f).testTag("atlas-declutter-discard")
                ) {
                    Text("← Stage Trash")
                }
                Button(
                    onClick = { decide(false) },
                    enabled = !verifying,
                    modifier = Modifier.weight(1f).testTag("atlas-declutter-keep")
                ) {
                    Text("Keep →")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = !verifying && session.position > 0,
                    onClick = { undoLast() },
                    modifier = Modifier.testTag("atlas-declutter-undo")
                ) { Text("Undo") }
                TextButton(onClick = { reviewing = true },
                    enabled = !verifying,
                    modifier = Modifier.testTag("atlas-declutter-review")) {
                    Text("Review staged (${pendingCount})")
                }
            }
        }
        }
    }
}
