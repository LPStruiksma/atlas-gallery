package nl.atlas.gallery.featurepeopleui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuredesign.AtlasTokens
import nl.atlas.gallery.peopleStatusLine
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuresearch.AtlasSearch
import nl.atlas.gallery.featuresearch.SearchHistoryScope
import nl.atlas.gallery.featuresearch.AtlasSearchScope
import nl.atlas.gallery.featuresearch.scopedNameMatches
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleSortChoice
import nl.atlas.gallery.featurepeopleui.PeopleSortKey
import nl.atlas.gallery.featurepeopleui.PeopleSortStats
import nl.atlas.gallery.featurepeopleui.orderPeopleEntries
import nl.atlas.gallery.featurepeopleui.PeopleSortEntry
import java.util.Locale

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
    onScanNow: (() -> Unit)? = null,
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
    onSearchWholeLibrary: (() -> Unit)? = null,
    /** Persisted by the owning Activity; preferences contain only the enum. */
    sortChoice: PeopleSortChoice = PeopleSortChoice(),
    onSortChoice: (PeopleSortChoice) -> Unit = {},
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
    val locale = LocalConfiguration.current.locales[0]
    var searchText by remember(currentPermissionEpoch) { mutableStateOf("") }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
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
    val tiles = remember(state.profiles, nativeTiles, unnamedTiles, currentNative, sortChoice) {
        val all: List<PeopleGridTile> = buildList {
            addAll(state.profiles.map { PeopleGridTile.Imported(it) })
            addAll(nativeTiles.map { PeopleGridTile.Native(it) })
            addAll(unnamedTiles)
        }
        val keyed = all.mapIndexed { index, tile ->
            val stats = when (tile) {
                is PeopleGridTile.Imported -> PeopleSortStats(tile.profile.accessiblePhotos,
                    tile.profile.firstAppearanceMs, tile.profile.mostRecentAppearanceMs)
                is PeopleGridTile.Native -> currentNative?.people?.get(tile.tile.person)?.let {
                    PeopleSortStats(it.mediaCount, it.firstAppearanceMs, it.mostRecentAppearanceMs)
                } ?: PeopleSortStats(0, null, null)
                is PeopleGridTile.Unnamed -> PeopleSortStats(0, null, null)
            }
            PeopleSortEntry(index, when (tile) {
                is PeopleGridTile.Imported -> tile.profile.name
                is PeopleGridTile.Native -> tile.tile.name
                is PeopleGridTile.Unnamed -> ""
            }, stats, randomKey = when (tile) {
                is PeopleGridTile.Imported -> "v4:${tile.profile.personId}"
                is PeopleGridTile.Native -> "native:${tile.tile.person.value}"
                is PeopleGridTile.Unnamed -> "unnamed:${tile.face.localHex}"
            })
        }
        orderPeopleEntries(keyed, sortChoice).map { all[it.id] }
    }
    val filteredTiles = tiles.filter { tile ->
        if (searchText.isBlank()) true
        else when (tile) {
            is PeopleGridTile.Imported -> tile.profile.name.isNotBlank() &&
                scopedNameMatches(tile.profile.name, searchText)
            is PeopleGridTile.Native -> tile.tile.name.isNotBlank() &&
                scopedNameMatches(tile.tile.name, searchText)
            is PeopleGridTile.Unnamed -> false
        }
    }
    val scan = peopleScanControls(discovery, actions, onStartDiscovery,
        onPauseDiscovery, onResumeDiscovery, onOpenReview)
    val overflow = buildList {
        addAll(menuItems)
        if (onScanNow != null && !currentPermissionEpoch.isNullOrBlank() &&
            discovery.phase != FaceDiscoveryPhase.UNAVAILABLE &&
            discovery.phase != FaceDiscoveryPhase.PERMISSION_REQUIRED) {
            add(AtlasHeaderMenuItem("Scan now", "atlas-people-scan-now", onClick = onScanNow))
        }
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
            onOpenLibraryStatus = onOpenLibraryStatus,
            actions = {
                IconButton(onClick = { sortSheetOpen = true },
                    modifier = Modifier.size(48.dp).testTag("atlas-people-sort-open")) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort people")
                }
                AtlasSearch(query = searchText, onQueryChange = { searchText = it },
                    historyScope = SearchHistoryScope.PEOPLE,
                    onScopeChange = { scope ->
                        if (scope == AtlasSearchScope.LIBRARY) onSearchWholeLibrary?.invoke()
                    })
            }
        )
        if (sortSheetOpen) PeopleSortSheet(sortChoice, onSortChoice) { sortSheetOpen = false }
        PeopleScanProgress(discovery)
        if (filteredTiles.isEmpty()) {
            if (tiles.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching people")
                }
            } else if (restoring) {
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
            items(filteredTiles.chunked(3), key = { row ->
                row.joinToString(":") { tile -> when (tile) {
                    is PeopleGridTile.Imported -> "v4-${tile.profile.personId}"
                    is PeopleGridTile.Native -> "native-${tile.tile.person.value}"
                    // An opaque face id is not a saved UI key; use the source row index.
                    is PeopleGridTile.Unnamed -> "unnamed-${unnamedTiles.indexOf(tile)}"
                } }
            }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { tile ->
                        when (tile) {
                            is PeopleGridTile.Imported ->
                                ProfileTile(tile.profile, onOpenProfile, portrait, locale, Modifier.weight(1f))
                            is PeopleGridTile.Native ->
                                NativeProfileTile(tile.tile, currentNative,
                                    onOpenNativePersonAlbum, liveNativePeopleAtAction,
                                    nativePortrait, locale, Modifier.weight(1f))
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

/** Compact sheet; the sort icon is in the header, not the overflow menu. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PeopleSortSheet(
    selected: PeopleSortChoice,
    onSelect: (PeopleSortChoice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("atlas-people-sort-sheet")) {
        Text("Sort people", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            PeopleSortKey.entries.forEach { key ->
                listOf(false, true).forEach { descending ->
                    val option = PeopleSortChoice(key, descending)
                    val label = when (key) {
                        PeopleSortKey.MEDIA_COUNT -> if (descending)
                            "Media count · most first" else "Media count · fewest first"
                        PeopleSortKey.NAME -> if (descending) "Name · Z–A" else "Name · A–Z"
                        PeopleSortKey.MOST_RECENT -> if (descending)
                            "Most recent · newest first" else "Most recent · oldest first"
                        PeopleSortKey.FIRST_APPEARANCE -> if (descending)
                            "First appearance · newest first" else "First appearance · oldest first"
                        PeopleSortKey.RANDOM -> if (descending) "Random · reverse" else "Random"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSelect(option)
                            onDismiss()
                        }.testTag("atlas-people-sort-${key.name}-${if (descending) "desc" else "asc"}")
                            .padding(horizontal = 24.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
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
 * #46: show a readable status line, not an animated scan bar for queued or
 * charger-held work. Accessible status text and counters share one formatter.
 */
@Composable
private fun PeopleScanProgress(state: FaceDiscoveryUiState) {
    val line = state.statusNote ?: peopleProgressLine(state) ?: return
    Column(Modifier.fillMaxWidth().semantics { stateDescription = line }
        .testTag("atlas-people-progress-line")) {
        Text(line,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { stateDescription = line }.testTag("atlas-people-status"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.statusNote == null && (state.phase == FaceDiscoveryPhase.SCANNING ||
            state.phase == FaceDiscoveryPhase.GROUPING)) {
            val bar = Modifier.fillMaxWidth().height(2.dp).testTag("atlas-people-progress")
            val progress = if (state.phase == FaceDiscoveryPhase.GROUPING) null else state.progress
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar)
            else LinearProgressIndicator(modifier = bar)
        }
    }
}

/** The same words shown in Settings and People, with scoped progress counts. */
internal fun peopleProgressLine(state: FaceDiscoveryUiState): String? = peopleStatusLine(state)

@Composable
private fun PortraitCircle(decorative: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (decorative) Modifier.clearAndSetSemantics { } else Modifier),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun MediaCountSubtitle(photos: Int, videos: Int, locale: Locale) {
    Text(
        peopleMediaCountLabel(photos, videos, locale),
        modifier = Modifier.fillMaxWidth().testTag("atlas-people-count"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun NativeProfileTile(
    tile: NativePersonTile,
    renderedReview: PeopleHomeSummary?,
    onOpenAlbum: ((PersonKey) -> Unit)?,
    livePeopleAtAction: (() -> PeopleHomeSummary?)?,
    nativePortrait: @Composable (NativeFaceObservationId) -> Unit,
    locale: Locale,
    modifier: Modifier
) {
    val canOpen = renderedReview != null && onOpenAlbum != null &&
        livePeopleAtAction != null
    val counts = renderedReview?.people?.get(tile.person)
    val photos = counts?.photoCount ?: 0
    val videos = counts?.videoCount ?: 0
    Column(
        modifier = modifier
            .then(if (canOpen) Modifier.clickable {
                if (livePeopleAtAction?.invoke() === renderedReview) {
                    onOpenAlbum?.invoke(tile.person)
                }
            } else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = peopleMediaCountContentDescription(tile.name, photos, videos, locale)
            }
            .testTag("atlas-native-person-album"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The named tile speaks the person's name and media counts; portrait alt text
        // must not be appended to its merged TalkBack description.
        PortraitCircle(decorative = true) {
            val face = tile.face
            if (face != null) nativePortrait(face) else DefaultPortrait()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tile.name, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            MediaCountSubtitle(photos, videos, locale)
        }
    }
}

@Composable
private fun ProfileTile(
    person: PeopleProfileUi,
    onOpen: (Long) -> Unit,
    portrait: @Composable (PeopleProfileUi) -> Unit,
    locale: Locale,
    modifier: Modifier
) {
    val semantics = if (person.name.isNotBlank()) {
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = peopleMediaCountContentDescription(
                person.name, person.accessiblePhotos, 0, locale)
        }
    } else Modifier
    Column(
        modifier = modifier.clickable { onOpen(person.personId) }
            .then(semantics)
            .testTag("atlas-people-profile-${person.personId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PortraitCircle(decorative = true) { portrait(person) }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(person.name.ifBlank { "Add name" }, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (person.name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface)
            if (person.name.isNotBlank()) MediaCountSubtitle(person.accessiblePhotos, 0, locale)
        }
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
        shape = RoundedCornerShape(AtlasTokens.Radii.PersonCard)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(168.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(AtlasTokens.Radii.PersonCover)),
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
