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
    thumbnail: suspend (MediaRecord) -> ImageBitmap?
) {
    val scope = rememberCoroutineScope()
    var resultQuery by remember(step) { mutableStateOf("") }
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
                BackHandler { close() }
                val state by session.state.collectAsState()
                val ui = state.ui()
                if (ui == null) {
                    Problem(state, onOpenSettings, onClose)
                } else {
                    val results = state as? FindSimilarState.Results
                    val source = results?.records
                    val searchSnapshot by produceState<ScopedMediaSearchSnapshot?>(
                        null, results, resultQuery, facetPeople
                    ) {
                        value = null
                        if (results != null && source != null && resultQuery.isNotBlank()) {
                            val worker = currentCoroutineContext()
                            val matches = withContext(Dispatchers.Default) {
                                scopedMediaMatches(source, source, facetPeople, resultQuery,
                                    cancellationCheckpoint = { worker.ensureActive() })
                            }
                            value = ScopedMediaSearchSnapshot(source, source, facetPeople,
                                results.scope, resultQuery, AtlasSearchScope.THIS_SCREEN, matches)
                        }
                    }
                    val filteredRecords = when {
                        results == null -> null
                        resultQuery.isBlank() -> source
                        source == null -> null
                        else -> currentScopedMediaSearchOrNull(searchSnapshot, source, source,
                            facetPeople, results.scope, resultQuery, AtlasSearchScope.THIS_SCREEN)
                    }
                    val visibleMedia = filteredRecords?.let { records ->
                        val allowed = records.mapTo(HashSet()) { it.uri }
                        results?.hits?.filter { it.record.uri in allowed }
                            ?.mapTo(HashSet()) { it.hit.media }
                    }
                    VisualSearchResultsScreen(
                        state = ui,
                        poseEnabled = results?.poseEnabled == true,
                        onPoseToggle = { on -> scope.launch { session.setPose(on) } },
                        onOpen = { media ->
                            val shown = session.state.value as? FindSimilarState.Results
                            val hit = shown?.hitFor(media) ?: return@VisualSearchResultsScreen
                            val currentList = if (resultQuery.isBlank()) shown.records
                                else filteredRecords?.takeIf { results === shown }
                            scope.launch {
                                // Revalidate the grant AND identity of the ranked result.
                                if (withContext(Dispatchers.IO) { session.revalidate() } &&
                                    session.state.value === shown && currentList != null &&
                                    currentList.any { it.uri == hit.record.uri }) {
                                    onOpen(currentList, hit.record, shown.startPositions)
                                }
                            }
                        },
                        onBack = onClose,
                        searchQuery = resultQuery,
                        onSearchQueryChange = { resultQuery = it },
                        onSearchWholeLibrary = onSearchWholeLibrary,
                        visibleMedia = visibleMedia,
                        filtering = resultQuery.isNotBlank() && filteredRecords == null,
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
