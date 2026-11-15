package nl.atlas.gallery.featureaiedit.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage

private const val OFFLINE_MOCK = "atlas-synthetic-mock"
private const val PREFERENCES = "atlas-ai-edit"

/**
 * Photo-first full-screen route. On recreation, the ViewModel restores from SavedStateHandle and
 * session-private working files. No Generate occurs until the button is pressed.
 */
@Composable
internal fun AiEditScreen(
    sourceUri: Uri,
    sessionId: String,
    onRefresh: () -> Unit,
    onSaved: (Uri) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(sessionId) { AiEditWorkingStore(context.applicationContext, sessionId) }
    val vm: AiEditViewModel = viewModel(key = "ai-edit:$sessionId")
    val preferences = remember(context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    LaunchedEffect(sessionId) {
        vm.initialize(store, preferences.getString("provider", OFFLINE_MOCK) ?: OFFLINE_MOCK)
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun back() {
        when {
            vm.ui.phase == AiEditPhase.Accepting -> Unit // AIE-4 journal must finish.
            vm.ui.hasUnsavedCandidates -> confirmDiscard = true
            else -> vm.discard(store, onExit)
        }
    }
    BackHandler { back() }
    val selectedUri = vm.ui.selectedCandidate?.let { ref ->
        runCatching { Uri.fromFile(store.candidateFile(ref.fileName)) }.getOrNull()
    }
    AiEditScreenContent(
        state = vm.ui, originalUri = sourceUri,
        selectedCandidateUri = selectedUri,
        candidateUri = { item ->
            runCatching { Uri.fromFile(store.candidateFile(item.fileName)) }.getOrNull()
        },
        onBack = ::back,
        onPromptChanged = vm::promptChanged,
        onGenerate = { vm.generate(context.applicationContext, sourceUri, store) },
        onCancel = vm::cancel,
        onAccept = {
            vm.accept(context.applicationContext, sourceUri, store, onRefresh, onSaved)
        },
        onChangePrompt = vm::changePrompt,
        onSelectCandidate = vm::selectCandidate,
        onBranchOriginal = { vm.selectBranch(null) },
        onChooseProvider = { id ->
            // Never infer a new provider, fail over, or dispatch on selection.
            if (id == OFFLINE_MOCK) {
                preferences.edit().putString("provider", id).apply()
                vm.chooseProvider(id)
            }
        }
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard AI edits?") },
        text = { Text("Your original photo will stay unchanged. Unsaved results from this session will be removed.") },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; vm.discard(store, onExit) },
                modifier = Modifier.testTag("atlas-ai-edit-discard")) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
        },
        modifier = Modifier.testTag("atlas-ai-edit-discard-confirm")
    )
}

/**
 * Stateless screen body, intentionally usable by synthetic emulator evidence fixtures. Both
 * before/after layers share ONE zoom/pan transform, and the split clips the after layer only.
 */
@Composable
internal fun AiEditScreenContent(
    state: AiEditUiState,
    originalUri: Uri,
    selectedCandidateUri: Uri?,
    candidateUri: (AiEditWorkingCandidate) -> Uri?,
    onBack: () -> Unit,
    onPromptChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    onChangePrompt: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onBranchOriginal: () -> Unit,
    onChooseProvider: (String) -> Unit
) {
    var providerOpen by remember { mutableStateOf(false) }
    val hasPreview = selectedCandidateUri != null
    var split by remember { mutableFloatStateOf(.5f) }
    var showOriginal by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var translation by remember { mutableStateOf(Offset.Zero) }
    val mayGenerate = state.prompt.isNotBlank() && state.phase in setOf(
        AiEditPhase.Composing, AiEditPhase.Cancelled, AiEditPhase.Failed,
        AiEditPhase.Refused, AiEditPhase.Result
    )

    Surface(Modifier.fillMaxSize().testTag("atlas-ai-edit-screen"),
        color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = state.phase != AiEditPhase.Accepting,
                    modifier = Modifier.testTag("atlas-ai-edit-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("AI Edit", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = { providerOpen = true },
                    label = { Text("Synthetic Mock · offline") },
                    modifier = Modifier.testTag("atlas-ai-edit-provider"),
                    enabled = state.phase !in setOf(AiEditPhase.Generating,
                        AiEditPhase.Accepting, AiEditPhase.Saved))
            }
            // The original is a read-only Coil decode from the single selected media URI.
            // The mock candidate is a PNG from this session's app-private cache.
            Box(Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .testTag("atlas-ai-edit-compare")
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                        translation = if (zoom <= 1f) Offset.Zero
                            else translation + pan
                    }
                }
                .pointerInput(hasPreview) {
                    detectTapGestures(onPress = {
                        if (hasPreview) showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = translation.x; translationY = translation.y
                }) {
                    AsyncImage(model = originalUri, contentDescription = "Original photo",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    if (hasPreview && !showOriginal) {
                        Box(Modifier.fillMaxSize().drawWithContent {
                            clipRect(right = size.width * split) { drawContent() }
                        }) {
                            AsyncImage(model = selectedCandidateUri,
                                contentDescription = "AI edit candidate preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                if (hasPreview) Text(
                    if (showOriginal) "Original" else "Before / after · hold to see original",
                    Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .90f),
                            RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("atlas-ai-edit-compare-hint"),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (hasPreview) {
                Slider(value = split, onValueChange = { split = it }, valueRange = 0f..1f,
                    modifier = Modifier.testTag("atlas-ai-edit-split"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Session results", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBranchOriginal,
                        modifier = Modifier.testTag("atlas-ai-edit-branch-original")) {
                        Text("Branch from original")
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("atlas-ai-edit-candidates")) {
                    items(state.candidates, key = { it.id }) { candidate ->
                        val uri = candidateUri(candidate)
                        Column(Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (candidate.id == state.selectedId)
                                MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onSelectCandidate(candidate.id) }
                            .padding(6.dp).testTag("atlas-ai-edit-candidate-${candidate.id}"),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(model = uri, contentDescription = "Previous AI candidate",
                                contentScale = ContentScale.Crop, modifier = Modifier.size(62.dp))
                            Text(if (candidate.parentId == null) "Original → edit"
                                 else "Candidate → edit",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            HorizontalDivider()
            when (state.phase) {
                AiEditPhase.Idle, AiEditPhase.Composing, AiEditPhase.Cancelled -> {
                    if (state.phase == AiEditPhase.Cancelled) Text(
                        "Generation cancelled. No change was saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("atlas-ai-edit-cancelled"))
                    AiEditPrompt(state.prompt, onPromptChanged)
                    Button(onClick = onGenerate, enabled = mayGenerate,
                        modifier = Modifier.fillMaxWidth().testTag("atlas-ai-edit-generate")) {
                        Text("Generate")
                    }
                }
                AiEditPhase.Generating -> {
                    Text("Generating with Synthetic Mock (offline)…",
                        modifier = Modifier.testTag("atlas-ai-edit-generating"))
                    LinearProgressIndicator(progress = { state.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().testTag("atlas-ai-edit-progress"))
                    TextButton(onClick = onCancel,
                        modifier = Modifier.testTag("atlas-ai-edit-cancel")) { Text("Cancel") }
                }
                AiEditPhase.Result -> {
                    Text("Compare the result before saving a new copy.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("atlas-ai-edit-result"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onChangePrompt) { Text("Change prompt") }
                        TextButton(onClick = onGenerate, enabled = mayGenerate,
                            modifier = Modifier.testTag("atlas-ai-edit-retry")) { Text("Retry") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onAccept, enabled = state.selectedCandidate != null,
                            modifier = Modifier.testTag("atlas-ai-edit-accept")) { Text("Accept") }
                    }
                }
                AiEditPhase.Refused -> {
                    Text("Synthetic Mock (offline) refused this request. No edit was saved.",
                        modifier = Modifier.testTag("atlas-ai-edit-refused"))
                    Text("Provider code: ${state.refusalCode ?: "unspecified"}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { providerOpen = true },
                            modifier = Modifier.testTag("atlas-ai-edit-try-another-provider")) {
                            Text("Try another provider")
                        }
                        TextButton(onClick = onChangePrompt) { Text("Change prompt") }
                        TextButton(onClick = onGenerate, enabled = mayGenerate) { Text("Retry") }
                    }
                }
                AiEditPhase.Failed -> {
                    Text(state.error ?: "The provider could not complete this request.",
                        modifier = Modifier.testTag("atlas-ai-edit-failed"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onChangePrompt) { Text("Change prompt") }
                        TextButton(onClick = onGenerate, enabled = mayGenerate) { Text("Retry") }
                        if (state.selectedCandidate != null) Button(onClick = onAccept) {
                            Text("Accept existing result")
                        }
                    }
                }
                AiEditPhase.Accepting -> {
                    CircularProgressIndicator(Modifier.testTag("atlas-ai-edit-accepting"))
                    Text("Saving a new copy. The original stays untouched.")
                }
                AiEditPhase.Saved -> {
                    Text("Saved as a new photo. Your original is unchanged.",
                        modifier = Modifier.testTag("atlas-ai-edit-saved"))
                    Button(onClick = onBack) { Text("Done") }
                }
            }
        }
    }
    if (providerOpen) AlertDialog(
        onDismissRequest = { providerOpen = false },
        title = { Text("Choose provider") },
        text = {
            Column {
                Text("Synthetic Mock (offline) is the only configured provider.")
                Text("No photo is uploaded, and selecting a provider never generates an edit.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                providerOpen = false
                onChooseProvider(OFFLINE_MOCK)
            }, modifier = Modifier.testTag("atlas-ai-edit-choose-mock")) {
                Text("Use Synthetic Mock")
            }
        },
        dismissButton = {
            TextButton(onClick = { providerOpen = false }) { Text("Cancel") }
        }
    )
}

@Composable
private fun AiEditPrompt(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { onChange(it.take(16_384)) },
        modifier = Modifier.fillMaxWidth().testTag("atlas-ai-edit-prompt"),
        placeholder = { Text("Describe what to change") },
        label = { Text("Your instruction") }, minLines = 2, maxLines = 3)
}
