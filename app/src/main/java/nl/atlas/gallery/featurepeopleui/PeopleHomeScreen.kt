package nl.atlas.gallery.featurepeopleui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/**
 * People tab in Samsung Gallery style (#516, #643, OWNER_INTENT): the slim
 * shared header, a thin progress line under it while a scan runs, then only
 * a 3-column grid of round face crops with names, or a plain empty state.
 * No banners, status text, free buttons or import blocks. ⋮ holds People
 * actions only (sort, Hidden, discover and review); pause/resume, the
 * Monochrome Studio import, visual index and People numbers live in Settings
 * (#643, #664).
 *
 * The screen never scans, imports or queries media itself. The caller owns
 * async work and all grants. A start/pause/resume/review control is offered
 * ONLY when the backend reports the capability and supplies a real callback.
 */
@Composable
fun PeopleHomeScreen(
    state: PeopleHomeUiState,
    actions: PeopleActionAvailability = PeopleActionAvailability(),
    onOpenProfile: (Long) -> Unit,
    /** Supplied by I alongside its current authorized MediaStore inventory. */
    currentPermissionEpoch: String? = null,
    /** X additive merge must actually be wired before changing this default. */
    @Suppress("UNUSED_PARAMETER")
    v4OneTimeImportMode: ReviewedV4ImportMode = ReviewedV4ImportMode.REPLACES,
    modifier: Modifier = Modifier,
    onStartDiscovery: (() -> Unit)? = null,
    onPauseDiscovery: (() -> Unit)? = null,
    onResumeDiscovery: (() -> Unit)? = null,
    onOpenReview: (() -> Unit)? = null,
    /** Native confirmed People, kept separate from imported V4 numeric IDs. */
    nativePeopleReview: PeopleHomeSummary? = null,
    nativePeopleSourceGeneration: String? = null,
    onOpenNativePersonAlbum: ((PersonKey) -> Unit)? = null,
    liveNativePeopleAtAction: (() -> PeopleHomeSummary?)? = null,
    /** Host-owned People ⋮ entries (Hidden). */
    menuItems: List<AtlasHeaderMenuItem> = emptyList(),
    onOpenLibraryStatus: (() -> Unit)? = null,
    /**
     * #31: a kept opt-in whose catalogue is still being restored. Shows a
     * quiet page, never "No people" and "Discover faces" for a library that
     * already has people.
     */
    restoring: Boolean = false,
    portrait: @Composable (PeopleProfileUi) -> Unit = {
        // NEVER issue an independent Coil request from a URI-only V4 cover.
        // The hosting lane must supply an authorized MediaRecord and M's
        // permission-epoch/provider-revision-aware MediaPreview. Without it,
        // the safe default is a neutral icon, including during regrant.
        DefaultPortrait()
    },
    /**
     * A confirmed face of a native person, drawn by the host from the live
     * grant (round crop). The default is the neutral icon.
     */
    nativePortrait: @Composable (NativeFaceObservationId) -> Unit = { DefaultPortrait() }
) {
    val discovery = state.discovery.restrictedToGrant(currentPermissionEpoch)
    // This remembers only a generic layout choice, never a private person name.
    var profileOrder by remember { mutableStateOf(ReviewedPeopleOrder.AS_PROVIDED) }
    val orderedProfiles = remember(state.profiles, profileOrder) {
        // Stable partition: unnamed profiles trail named ones without erasing
        // the chosen Original order, Most photos, or Name ordering.
        val selectedOrder = orderReviewedPeopleProfiles(state.profiles, profileOrder)
        val named = selectedOrder.filterNot { it.name.isBlank() }
        val unnamed = selectedOrder.filter { it.name.isBlank() }
        named + unnamed
    }
    // The native review snapshot is grant-scoped and never mixed with imported
    // V4 numeric IDs. Its confirmed names appear in the same People grid.
    val currentNative = nativePeopleReview?.takeIf {
        it.permissionEpoch == currentPermissionEpoch &&
            it.sourceGeneration == nativePeopleSourceGeneration &&
            !currentPermissionEpoch.isNullOrBlank() &&
            !nativePeopleSourceGeneration.isNullOrBlank()
    }
    // Lean home summary (no full cluster state): name plus one cover face each.
    val nativeTiles = remember(currentNative, currentPermissionEpoch, nativePeopleSourceGeneration) {
        peopleHomeAlbumsForGrant(currentNative, currentPermissionEpoch, nativePeopleSourceGeneration)
            .map { entry ->
                val cover = currentNative?.people?.get(entry.person)?.coverFaceUid
                    ?.let { uid -> runCatching { NativeFaceObservationId(uid) }.getOrNull() }
                NativePersonTile(entry.person, entry.name, cover)
            }
    }
    // #579: unnamed groups of 3+ faces follow the named people, largest
    // first, like Samsung's "Add name" circles. Smaller groups stay in review
    // behind "Show all".
    val unnamedTiles = remember(currentNative) {
        currentNative?.unnamed.orEmpty().mapNotNull { group ->
            runCatching { NativeFaceObservationId(group.anchorFaceUid) }.getOrNull()
                ?.let { PeopleGridTile.Unnamed(it, group.faceCount) }
        }
    }
    // Both sources retain their own click contract and identity type.
    val tiles = orderedProfiles.map { PeopleGridTile.Imported(it) } +
        nativeTiles.map { PeopleGridTile.Native(it) } + unnamedTiles
    val scan = peopleScanControls(discovery, actions, onStartDiscovery,
        onPauseDiscovery, onResumeDiscovery, onOpenReview)
    val overflow = buildList {
        if (state.profiles.size > 1) {
            ReviewedPeopleOrder.entries.forEach { option ->
                add(AtlasHeaderMenuItem(
                    label = when (option) {
                        ReviewedPeopleOrder.AS_PROVIDED -> "Original order"
                        ReviewedPeopleOrder.MOST_PHOTOS -> "Most photos"
                        ReviewedPeopleOrder.NAME -> "Name"
                    },
                    testTag = "atlas-people-sort-${option.name}",
                    checked = profileOrder == option,
                    onClick = { profileOrder = option }
                ))
            }
        }
        addAll(menuItems)
        // #643: scan and review are People actions, so they sit in ⋮ rather
        // than as buttons on the page. The empty page keeps "Discover faces".
        if (scan.start != null && tiles.isNotEmpty()) add(AtlasHeaderMenuItem(
            "Discover faces", "atlas-people-start", onClick = scan.start))
        // #664: pause/resume have one home, under Settings > People.
        scan.review?.let { add(AtlasHeaderMenuItem("Review unknown faces", "atlas-people-review", onClick = it)) }
    }
    Column(modifier.fillMaxSize().testTag("atlas-people-screen")) {
        AtlasGalleryHeader(
            title = "People",
            menuItems = overflow,
            onOpenLibraryStatus = onOpenLibraryStatus
        )
        PeopleScanProgress(discovery)
        if (tiles.isEmpty()) {
            if (restoring) {
                Box(Modifier.fillMaxSize().testTag("atlas-people-restoring"))
            } else {
                PeopleEmptyState(scan.start)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("atlas-people-home"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(tiles.chunked(3), key = { row ->
                row.joinToString(":") { tile -> when (tile) {
                    is PeopleGridTile.Imported -> "v4-${tile.profile.personId}"
                    is PeopleGridTile.Native -> "native-${tile.tile.person.value}"
                    // Position only: a private face uid is never a lazy (saveable) key.
                    is PeopleGridTile.Unnamed -> "unnamed-${unnamedTiles.indexOf(tile)}"
                } }
            }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { tile ->
                        when (tile) {
                            is PeopleGridTile.Imported ->
                                ProfileTile(tile.profile, onOpenProfile, portrait, Modifier.weight(1f))
                            is PeopleGridTile.Native ->
                                NativeProfileTile(tile.tile, currentNative,
                                    onOpenNativePersonAlbum, liveNativePeopleAtAction,
                                    nativePortrait, Modifier.weight(1f))
                            is PeopleGridTile.Unnamed ->
                                UnnamedGroupTile(tile, onOpenReview, nativePortrait,
                                    Modifier.weight(1f))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class NativePersonTile(
    val person: PersonKey,
    val name: String,
    val face: NativeFaceObservationId?
)

private sealed interface PeopleGridTile {
    data class Imported(val profile: PeopleProfileUi) : PeopleGridTile
    data class Native(val tile: NativePersonTile) : PeopleGridTile
    data class Unnamed(val face: NativeFaceObservationId, val faceCount: Int) : PeopleGridTile
}

/** #579: an unnamed group; tapping opens review, where it can be named. */
@Composable
private fun UnnamedGroupTile(
    tile: PeopleGridTile.Unnamed,
    onOpenReview: (() -> Unit)?,
    nativePortrait: @Composable (NativeFaceObservationId) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .then(if (onOpenReview != null) Modifier.clickable { onOpenReview() } else Modifier)
            .testTag("atlas-people-unnamed"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PortraitCircle { nativePortrait(tile.face) }
        Text("Add name", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Samsung's plain empty People page: one icon, one line, and the opt-in when real. */
@Composable
private fun PeopleEmptyState(onStart: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("atlas-people-home"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.PeopleOutline, contentDescription = null,
            modifier = Modifier.size(64.dp).testTag("atlas-people-empty"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text("No people", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onStart != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onStart) { Text("Discover faces") }
        }
    }
}

/** The scan and review callbacks the current phase and backend really allow. */
private class PeopleScanControls(
    val start: (() -> Unit)?,
    val pause: (() -> Unit)?,
    val resume: (() -> Unit)?,
    val review: (() -> Unit)?
)

private fun peopleScanControls(
    state: FaceDiscoveryUiState,
    actions: PeopleActionAvailability,
    onStart: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onReview: (() -> Unit)?
) = PeopleScanControls(
    start = onStart.takeIf { state.phase == FaceDiscoveryPhase.READY && actions.mayStartScan },
    pause = onPause.takeIf { state.phase == FaceDiscoveryPhase.SCANNING && actions.mayPauseScan },
    resume = onResume.takeIf { state.phase == FaceDiscoveryPhase.PAUSED && actions.mayResumeScan },
    review = onReview.takeIf { state.unknownObservations > 0 && actions.mayOpenReview }
)

/**
 * #643: while a scan is queued, runs or is paused, one thin line under the
 * title and nothing else: no text, no button. The count stays readable for
 * TalkBack as the line's state ("Finding people · 3 of 10").
 */
@Composable
private fun PeopleScanProgress(state: FaceDiscoveryUiState) {
    val line = peopleProgressLine(state) ?: return
    Box(Modifier.fillMaxWidth().semantics { stateDescription = line }
        .testTag("atlas-people-progress-line")) {
        val progress = state.progress
        val bar = Modifier.fillMaxWidth().height(2.dp).testTag("atlas-people-progress")
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar)
        else LinearProgressIndicator(bar)
    }
}

/** The scan state as words, "Finding people · 3 of 10", or null when idle. */
internal fun peopleProgressLine(state: FaceDiscoveryUiState): String? {
    val count = if (state.eligible > 0) " · ${state.processed} of ${state.eligible}" else ""
    return when (state.phase) {
        FaceDiscoveryPhase.QUEUED, FaceDiscoveryPhase.SCANNING -> "Finding people$count"
        FaceDiscoveryPhase.PAUSED -> "Paused$count"
        else -> null
    }
}

@Composable
private fun PortraitCircle(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun NativeProfileTile(
    tile: NativePersonTile,
    renderedReview: PeopleHomeSummary?,
    onOpenAlbum: ((PersonKey) -> Unit)?,
    livePeopleAtAction: (() -> PeopleHomeSummary?)?,
    nativePortrait: @Composable (NativeFaceObservationId) -> Unit,
    modifier: Modifier
) {
    val canOpen = renderedReview != null && onOpenAlbum != null &&
        livePeopleAtAction != null
    Column(
        modifier = modifier
            .then(if (canOpen) Modifier.clickable {
                if (livePeopleAtAction?.invoke() === renderedReview) {
                    onOpenAlbum?.invoke(tile.person)
                }
            } else Modifier)
            .testTag("atlas-native-person-album"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PortraitCircle {
            val face = tile.face
            if (face != null) nativePortrait(face) else DefaultPortrait()
        }
        Text(tile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfileTile(
    person: PeopleProfileUi,
    onOpen: (Long) -> Unit,
    portrait: @Composable (PeopleProfileUi) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.clickable { onOpen(person.personId) }
            .testTag("atlas-people-profile-${person.personId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PortraitCircle { portrait(person) }
        Text(person.name.ifBlank { "Add name" },
            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (person.name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DefaultPortrait() {
    Icon(Icons.Filled.PersonOutline, "No verified portrait",
        Modifier.fillMaxSize(0.6f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A real, reversible Lane P detection-review operation. There is NO
 * "Same person" button: locating a face is not identifying its owner.
 * I/P must supply a confirmed, grant-scoped observation and journal callbacks.
 */
@Composable
fun UnknownFaceReviewCard(
    candidate: DetectedFaceReviewCandidate,
    onDecide: (NativeFaceReviewCommand) -> Unit,
    /** Current grant supplied by I; wrong epoch hides the entire review card. */
    currentPermissionEpoch: String?,
    modifier: Modifier = Modifier,
    sourcePreview: @Composable () -> Unit = {
        Icon(Icons.Filled.PhotoLibrary, "Photo preview not available",
            Modifier.size(56.dp))
    }
) {
    if (!candidate.key.validFor(currentPermissionEpoch)) return
    // The source epoch and native UID are rechecked at click time; X checks
    // current committed epoch + processed face UID atomically in SQLite.
    fun sendDecision(decision: DetectionReviewChoice) {
        nativeReviewCommandOrNull(candidate.key, decision, currentPermissionEpoch)
            ?.let(onDecide)
    }
    // Stable, nonsensitive test tag. Never log a private MediaStore source key.
    Card(modifier = modifier.fillMaxWidth().testTag("atlas-unknown-face-review"),
        shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(168.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) { sourcePreview() }
            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
            Text(candidate.explanation, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Detection is not recognition. No person will be named or merged.",
                style = MaterialTheme.typography.labelSmall)
            if (candidate.decision == DetectionReviewChoice.UNREVIEWED) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { sendDecision(DetectionReviewChoice.LATER) }
                    ) { Text("Later") }
                    Button(onClick = {
                        sendDecision(DetectionReviewChoice.NOT_A_FACE)
                    }) { Text("Not a face") }
                }
            } else {
                Text(
                    if (candidate.decision == DetectionReviewChoice.LATER) "Postponed"
                    else "Marked as not a face",
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(onClick = {
                    sendDecision(DetectionReviewChoice.UNREVIEWED)
                }) { Text("Undo decision") }
            }
        }
    }
}
