package nl.atlas.gallery.featuresearch.visual

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import nl.atlas.gallery.featuresearch.AtlasSearchScope
import nl.atlas.gallery.featuresearch.ScopedMediaSearchSnapshot
import nl.atlas.gallery.featuresearch.currentScopedMediaSearchOrNull
import nl.atlas.gallery.featuresearch.scopedMediaMatches
import nl.atlas.gallery.featuresearch.ScopedRankOrder
import nl.atlas.gallery.featuresearch.scopeRankedHits
import nl.atlas.gallery.CatalogSourceKey
import nl.atlas.gallery.featurevisual.search.VisualSearchUiState
import nl.atlas.gallery.featuresearch.face.FaceReferenceChooser
import nl.atlas.gallery.featurevisual.search.VisualSearchResultsScreen

/** Where Find similar is; the host keeps it in memory only, never in saved state. */
sealed interface FindSimilarStep {
    /** Search: choose a reference photo from the authorized gallery. */
    data object ChooseReference : FindSimilarStep

    /** The session's ranked results. */
    data object Results : FindSimilarStep
}

/**
 * VS-S2b (#468): the Find similar flow as one route, used by MainActivity and
 * by the API35 journey test. The results are the merged, stateless
 * VisualSearchResultsScreen (with its pose switch); a reference that can't be
 * searched yet gets its own typed message instead. [portVersion] is the host's
 * change counter: every change re-validates the shown results. [onOpen] gets
 * the viewer list, the tapped row and each video's start position (frame_ms).
 */
@Composable
fun FindSimilarRoute(
    session: FindSimilarSession,
    portVersion: Long,
    step: FindSimilarStep,
    photos: List<MediaRecord>,
    onStep: (FindSimilarStep) -> Unit,
    onOpen: (List<MediaRecord>, MediaRecord, Map<String, Long>) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    facetPeople: List<PersonEntry> = emptyList(),
    onSearchWholeLibrary: (() -> Unit)? = null,
    screenMedia: List<MediaRecord>? = null,
    thumbnail: suspend (MediaRecord) -> ImageBitmap?
) {
    val scope = rememberCoroutineScope()
    var resultQuery by remember(step) { mutableStateOf("") }
    var screenSort by remember(screenMedia?.let(::CatalogSourceKey)) { mutableStateOf(false) }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(session, portVersion) {
        withContext(Dispatchers.IO) { session.revalidate() }
    }
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("atlas-find-similar")) {
        when (step) {
            FindSimilarStep.ChooseReference -> {
                BackHandler { close() }
                FaceReferenceChooser(photos,
                    onChoose = { photo ->
                        onStep(FindSimilarStep.Results)
                        scope.launch { session.search(FindSimilarReference(photo)) }
                    },
                    onBack = onClose,
                    title = "Find similar",
                    description = "Choose a photo to find others like it")
            }
            FindSimilarStep.Results -> {
                BackHandler { if (screenMedia != null) onStep(FindSimilarStep.ChooseReference) else close() }
                val state by session.state.collectAsState()
                val ui = state.ui()
                if (ui == null) {
                    Problem(state, onOpenSettings, onClose)
                } else {
                    val results = state as? FindSimilarState.Results
                    // The visual engine is still global and owns the ranking.
                    // Intersect its real hits with the authorized origin on a worker,
                    // never widen results based on the chosen reference.
                    val projection by produceState<Pair<FindSimilarState.Results, List<SimilarHit>>?>(
                        null, results, resultQuery, facetPeople,
                        screenMedia?.let(::CatalogSourceKey), screenSort
                    ) {
                        value = null
                        if (results != null) {
                            val worker = currentCoroutineContext()
                            val narrowed = withContext(Dispatchers.Default) {
                                val textUris = if (resultQuery.isBlank()) null else
                                    scopedMediaMatches(results.records, results.records,
                                        facetPeople, resultQuery,
                                        cancellationCheckpoint = { worker.ensureActive() })
                                        .mapTo(HashSet()) { it.uri }
                                val hits = if (textUris == null) results.hits else
                                    results.hits.filter { it.record.uri in textUris }
                                scopeRankedHits(hits,
                                    (screenMedia ?: results.records).map { it.uri },
                                    { it.record.uri },
                                    if (screenSort) ScopedRankOrder.SCREEN_SORT
                                    else ScopedRankOrder.BEST_MATCHES)
                            }
                            worker.ensureActive()
                            value = results to narrowed
                        }
                    }
                    val shownHits = projection?.takeIf { it.first === results }?.second
                    val currentList = shownHits?.map { it.record }
                    val scopedUi = if (results != null && shownHits != null)
                        VisualSearchUiState.Results(results.shown.copy(hits = shownHits.map { it.hit }))
                        else ui
                    if (screenMedia != null && results != null) TextButton(
                        onClick = { screenSort = !screenSort },
                        modifier = Modifier.testTag("atlas-similar-screen-sort")) {
                        Text(if (screenSort) "Best matches" else "Screen sort")
                    }
                    VisualSearchResultsScreen(
                        state = scopedUi,
                        poseEnabled = results?.poseEnabled == true,
                        onPoseToggle = { on -> scope.launch { session.setPose(on) } },
                        onOpen = { media ->
                            val shown = session.state.value as? FindSimilarState.Results
                            val hit = shown?.hitFor(media) ?: return@VisualSearchResultsScreen
                            val actualList = currentList?.takeIf { results === shown }
                            scope.launch {
                                if (withContext(Dispatchers.IO) { session.revalidate() } &&
                                    session.state.value === shown && actualList != null &&
                                    actualList.any { it.uri == hit.record.uri }) {
                                    onOpen(actualList, hit.record, shown.startPositions)
                                }
                            }
                        },
                        onBack = { if (screenMedia != null) onStep(FindSimilarStep.ChooseReference)
                            else onClose() },
                        searchQuery = resultQuery,
                        onSearchQueryChange = { resultQuery = it },
                        onSearchWholeLibrary = onSearchWholeLibrary,
                        // VisualSearchResultsScreen also applies its text-query
                        // allowlist. Pass the exact projected media identities
                        // or it would erase every hit for a nonempty query.
                        visibleMedia = shownHits?.mapTo(HashSet()) { it.hit.media },
                        filtering = results != null && shownHits == null,
                        thumbnail = { media ->
                            (session.state.value as? FindSimilarState.Results)
                                ?.hitFor(media)?.record?.let { thumbnail(it) }
                        }
                    )
                }
            }
        }
    }
}

/** A typed "can't be searched (yet)" state: a message and a way on, never a spinner. */
@Composable
private fun Problem(state: FindSimilarState, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("atlas-find-similar-problem"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val problem = (state as? FindSimilarState.NotSearchable)?.problem
        val (tag, title, body) = when (problem) {
            FindSimilarProblem.INDEX_OFF -> Triple("index-off",
                "Similar-photo search is off.",
                "Turn on the Visual search index in Settings. ATLAS compares " +
                    "your photos on " +
                    "this device only.")
            FindSimilarProblem.INDEX_NOT_READY -> Triple("not-indexed",
                "Your photos are still being prepared for similar-photo search.",
                "Try again once the Visual search index in Settings has finished.")
            FindSimilarProblem.REFERENCE_UNREADABLE -> Triple("unreadable",
                "This photo can't be searched.",
                "ATLAS couldn't read it on this device. No photo was changed.")
            null -> Triple("failed", "Find similar couldn't finish.", "No photo was changed. Try again.")
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("atlas-find-similar-$tag"))
        Text(body)
        if (problem == FindSimilarProblem.INDEX_OFF || problem == FindSimilarProblem.INDEX_NOT_READY) {
            Button(onClick = onOpenSettings, modifier = Modifier.testTag("atlas-find-similar-open-people")) {
                Text("Open settings")
            }
        } else {
            OutlinedButton(onClick = onBack) { Text("Close") }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("atlas-find-similar-back")) {
            Text("Back")
        }
    }
}
