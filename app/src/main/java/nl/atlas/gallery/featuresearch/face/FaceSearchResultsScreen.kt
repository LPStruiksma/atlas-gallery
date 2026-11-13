package nl.atlas.gallery.featuresearch.face

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.CatalogSourceKey
import nl.atlas.gallery.MediaPreview
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/** Where search by face is; the host keeps it in memory only, never in saved state. */
sealed interface FaceSearchStep {
    /** Search: choose a reference photo from the authorized gallery. */
    data object ChooseReference : FaceSearchStep

    /** Pick the face to search for on [photo]; Back returns to the chooser if [fromChooser]. */
    data class PickFace(val photo: MediaRecord, val fromChooser: Boolean) : FaceSearchStep

    /** The session's ranked results. */
    data object Results : FaceSearchStep
}

/**
 * VS-F2b (#468): the whole search-by-face flow as one route, used by
 * MainActivity and by the API35 journey test. All port calls run on IO;
 * every step re-reads the live epoch when [portVersion] changes (the host's
 * change counter), so a revoke empties the screen instead of showing old
 * faces. [photos] is the permission-gated gallery list the grid shows.
 */
@Composable
fun FaceSearchRoute(
    port: FaceSearchPort,
    portVersion: Long,
    session: FaceSearchSession,
    step: FaceSearchStep,
    photos: List<MediaRecord>,
    onStep: (FaceSearchStep) -> Unit,
    onOpen: (List<MediaRecord>, MediaRecord) -> Unit,
    onOpenAlbum: (PersonKey, String) -> Unit,
    onOpenPeople: () -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    // A revoke or replaced scope must drop results on the next host change.
    LaunchedEffect(session, portVersion) {
        withContext(Dispatchers.IO) { session.revalidate() }
    }
    val epoch by produceState<String?>(null, port, portVersion) {
        value = withContext(Dispatchers.IO) { port.liveEpoch() } ?: NO_EPOCH
    }
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("atlas-face-search")) {
        when (step) {
            FaceSearchStep.ChooseReference -> {
                BackHandler { close() }
                when (epoch) {
                    null -> Busy("Checking face search…")
                    NO_EPOCH -> StateScreen(FaceSearchState.NotIndexed, onOpenPeople, onClose)
                    else -> FaceReferenceChooser(photos,
                        onChoose = { onStep(FaceSearchStep.PickFace(it, fromChooser = true)) },
                        onBack = onClose)
                }
            }
            is FaceSearchStep.PickFace -> {
                val back = {
                    if (step.fromChooser) onStep(FaceSearchStep.ChooseReference) else onClose()
                }
                BackHandler { back() }
                val prepared by produceState<FaceReferencePreparation>(
                    FaceReferencePreparation.Loading, port, epoch, step.photo
                ) {
                    val live = epoch
                    value = when (live) {
                        null -> FaceReferencePreparation.Loading
                        NO_EPOCH -> FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED)
                        else -> withContext(Dispatchers.IO) { prepareFaceReference(port, live, step.photo) }
                    }
                }
                when (val ready = prepared) {
                    FaceReferencePreparation.Loading -> Busy("Preparing this photo…")
                    is FaceReferencePreparation.Unavailable ->
                        if (ready.problem == FaceReferenceProblem.NOT_INDEXED)
                            StateScreen(FaceSearchState.NotIndexed, onOpenPeople, back)
                        else ReferenceProblem(ready.problem, onOpenPeople, back)
                    is FaceReferencePreparation.Ready -> QueryFacePickerScreen(ready.source, ready.capture,
                        onFaceChosen = { face ->
                            onStep(FaceSearchStep.Results)
                            scope.launch { session.search(face) }
                        },
                        onBack = back)
                }
            }
            FaceSearchStep.Results -> {
                BackHandler { close() }
                val state by session.state.collectAsState()
                FaceSearchResultsScreen(state,
                    onOpen = { records, record ->
                        scope.launch {
                            // Re-check the grant at tap time, not only the last frame.
                            if (withContext(Dispatchers.IO) { session.revalidate() })
                                onOpen(records, record)
                        }
                    },
                    onOpenAlbum = onOpenAlbum, onOpenPeople = onOpenPeople, onBack = onClose)
            }
        }
    }
}

private const val NO_EPOCH = "\u0000none"

/** A reference photo that can't be searched yet: a message, never a spinner. */
@Composable
private fun ReferenceProblem(problem: FaceReferenceProblem, onOpenPeople: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("atlas-face-reference-unavailable"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (tag, title, body) = when (problem) {
            FaceReferenceProblem.NOT_IN_CATALOGUE -> Triple("not-in-catalogue",
                "This photo isn't in face search yet.",
                "People scans new and edited photos on this device first. Try again once it has, " +
                    "or choose another photo.")
            FaceReferenceProblem.MODELS_NOT_READY -> Triple("models-not-ready",
                "Face search is still starting.",
                "The on-device face models load with the next People scan. Try again shortly.")
            FaceReferenceProblem.FAILED -> Triple("failed",
                "This photo couldn't be prepared for face search.", "No photo was changed. Try again.")
            FaceReferenceProblem.NOT_INDEXED -> Triple("not-indexed",
                "No faces are indexed yet.", "Turn on face discovery in People.")
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("atlas-face-reference-$tag"))
        Text(body)
        if (problem != FaceReferenceProblem.FAILED) OutlinedButton(onClick = onOpenPeople) {
            Text("Go to People")
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("atlas-face-reference-back")) {
            Text("Back")
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("atlas-face-search-busy"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text)
    }
}

/**
 * Choose a reference photo from the authorized gallery (photos only, gallery
 * order). Find similar (VS-S2b) reuses it with its own [title] and [description].
 */
@Composable
fun FaceReferenceChooser(
    photos: List<MediaRecord>,
    onChoose: (MediaRecord) -> Unit,
    onBack: () -> Unit,
    title: String = "Search by face",
    description: String = "Choose a photo of the person you're looking for"
) {
    // 150k rows are filtered off main, keyed by list identity, not equals().
    val images by produceState<List<MediaRecord>?>(null, CatalogSourceKey(photos)) {
        value = withContext(Dispatchers.Default) { photos.filter { !it.isVideo } }
    }
    Column(Modifier.fillMaxSize().testTag("atlas-face-reference-chooser")) {
        Header(title, description)
        val list = images
        when {
            list == null -> Busy("Loading your photos…")
            list.isEmpty() -> Text("No photos to choose from.", Modifier.padding(20.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Positional keys: never a private content URI as a saveable key.
                itemsIndexed(list, key = { index, _ -> index }) { index, photo ->
                    Box(Modifier.aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .testTag("atlas-face-reference-$index")
                        .semantics { contentDescription = "Use ${photo.name} as reference" }
                        .clickable { onChoose(photo) }) {
                        MediaPreview(photo, Modifier.fillMaxSize(), ContentScale.Crop, 256)
                    }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("atlas-face-search-back")) {
            Text("Back")
        }
    }
}

@Composable
private fun Header(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Ranked results: same-person photos first, then PROBABLE ones under a
 * "Possible matches" header. Look-alikes are never shown. When the top
 * matches belong to a confirmed person, "Open <name>'s album" is offered.
 */
@Composable
fun FaceSearchResultsScreen(
    state: FaceSearchState,
    onOpen: (List<MediaRecord>, MediaRecord) -> Unit,
    onOpenAlbum: (PersonKey, String) -> Unit,
    onOpenPeople: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().testTag("atlas-face-results")) {
        Header("Search by face", "Matches found on this device, best first")
        when (state) {
            is FaceSearchState.Results -> ResultsGrid(state, onOpen, onOpenAlbum,
                Modifier.weight(1f))
            else -> Box(Modifier.weight(1f)) { StateScreen(state, onOpenPeople, onBack) }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("atlas-face-search-back")) {
            Text("Back")
        }
    }
}

@Composable
private fun StateScreen(state: FaceSearchState, onOpenPeople: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            FaceSearchState.Idle, FaceSearchState.Searching -> {
                CircularProgressIndicator(Modifier.testTag("atlas-face-results-searching"))
                Text("Searching your photos on this device…")
            }
            FaceSearchState.NotIndexed -> {
                Text("No faces are indexed yet.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-face-results-not-indexed"))
                Text("Turn on face discovery in People. ATLAS scans your photos on this " +
                    "device only, then you can search by face.")
                Button(onClick = onOpenPeople, modifier = Modifier.testTag("atlas-face-results-open-people")) {
                    Text("Go to People")
                }
            }
            FaceSearchState.VersionMismatch -> {
                Text("The face index was built by another face model version.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-face-results-version-mismatch"))
                Text("Let People finish rescanning your photos, then search again.")
                Button(onClick = onOpenPeople) { Text("Go to People") }
            }
            FaceSearchState.Revoked -> {
                Text("Photo access changed, so these results were cleared.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-face-results-revoked"))
                Text("Nothing was kept. Search again once photo access and face discovery are on.")
                OutlinedButton(onClick = onBack) { Text("Close") }
            }
            FaceSearchState.Failed -> {
                Text("Face search couldn't finish.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-face-results-failed"))
                Text("No photo was changed. Try again.")
                OutlinedButton(onClick = onBack) { Text("Close") }
            }
            is FaceSearchState.Empty -> {
                Text("No matching faces found.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-face-results-empty"))
                Text("Only photos People has scanned are searched. Look-alikes are never shown.")
            }
            is FaceSearchState.Results -> Unit
        }
    }
}

@Composable
private fun ResultsGrid(
    state: FaceSearchState.Results,
    onOpen: (List<MediaRecord>, MediaRecord) -> Unit,
    onOpenAlbum: (PersonKey, String) -> Unit,
    modifier: Modifier
) {
    val sure = state.sure
    val possible = state.possible
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.testTag("atlas-face-results-grid"),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val person = state.person
        val name = state.personName
        if (person != null && name != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { onOpenAlbum(person, name) },
                    modifier = Modifier.fillMaxWidth().testTag("atlas-face-results-album")) {
                    Text("Open $name's album")
                }
            }
        }
        if (sure.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Same person · ${sure.size}", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(4.dp).testTag("atlas-face-results-sure-header"))
        }
        itemsIndexed(sure, key = { index, _ -> "s$index" }) { index, hit ->
            ResultTile(hit, "atlas-face-result-$index") { onOpen(state.records, hit.record) }
        }
        if (possible.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Possible matches · ${possible.size}", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, bottom = 4.dp)
                    .testTag("atlas-face-results-possible-header"))
        }
        itemsIndexed(possible, key = { index, _ -> "p$index" }) { index, hit ->
            ResultTile(hit, "atlas-face-result-${sure.size + index}") {
                onOpen(state.records, hit.record)
            }
        }
        if (state.truncated) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Showing the best ${state.hits.size} photos.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ResultTile(hit: FaceSearchHit, tag: String, onClick: () -> Unit) {
    val possible = hit.tier == FaceMatchTier.PROBABLE
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
        .then(if (possible) Modifier.border(1.dp,
            MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)) else Modifier)
        .testTag(tag)
        .semantics {
            contentDescription = (if (possible) "Possible match: " else "Same person: ") + hit.record.name
        }
        .clickable(onClick = onClick)) {
        MediaPreview(hit.record, Modifier.fillMaxSize(), ContentScale.Crop, 256)
    }
}
