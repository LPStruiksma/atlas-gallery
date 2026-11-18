package nl.atlas.gallery.featureaiedit.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.MockImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.MockProviderConfig
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import nl.atlas.gallery.featureaiedit.save.AiEditCandidate
import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureaiedit.save.AiEditSaver
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SavedStateHandle holds only a small event snapshot and cache basenames. Full candidate pixels
 * live in private cache. No provider call is launched from init or restoration.
 */
internal class AiEditViewModel(private val handle: SavedStateHandle) : ViewModel() {
    private var machine = AiEditStateMachine()
    var ui by mutableStateOf(AiEditUiState())
        private set
    private var initialized = false
    private var activeCoroutine: Job? = null
    private var activeProviderJob: EditJob? = null

    fun initialize(store: AiEditWorkingStore, defaultProvider: String) {
        if (initialized) return
        initialized = true
        val saved = handle.get<String>("snapshot")
        machine = if (saved == null) AiEditStateMachine() else
            AiEditStateMachine.restore(readState(saved), store.existing())
        if (saved == null) machine.chooseProvider(defaultProvider)
        if (machine.state.phase == AiEditPhase.Idle) machine.compose()
        publish()
    }

    private fun publish() {
        ui = machine.state
        handle["snapshot"] = writeState(ui)
    }
    fun promptChanged(text: String) { if (machine.compose(text)) publish() }
    fun chooseProvider(id: String) { if (machine.chooseProvider(id)) publish() }
    fun selectCandidate(id: String) { if (machine.selectCandidate(id)) publish() }
    fun selectBranch(id: String?) { if (machine.selectBranch(id)) publish() }
    fun changePrompt() { if (machine.compose()) publish() }

    fun cancel() {
        activeProviderJob?.cancel()
        activeCoroutine?.cancel()
        if (machine.cancel()) publish()
    }

    fun generate(context: Context, source: Uri, store: AiEditWorkingStore) {
        val prior = machine.state
        val parent = prior.branchParentId?.let { id ->
            prior.candidates.firstOrNull { it.id == id }
        }
        val token = machine.generate() ?: return
        publish()
        activeCoroutine = viewModelScope.launch {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                // AIE-2 provider is fully offline and synthetic. Source is deliberately reduced
                // to at most 4096 pixels, no EXIF, gallery database or other photo included.
                val input = withContext(Dispatchers.IO) {
                    if (parent == null) store.mockSource(source)
                    else store.mockSourceFromCandidate(parent.fileName)
                }
                val provider = MockImageEditProvider(MockProviderConfig(
                    pendingPolls = 3, maxPixels = 4096L, maxCandidates = 2))
                val request = ImageEditRequest(source = input, instruction = prior.prompt,
                    output = OutputSpec(input.width, input.height, ImageFormat.PNG, 2))
                val job = provider.submit(request)
                activeProviderJob = job
                var done = false
                while (!done && isActive) {
                    when (val polled = withContext(Dispatchers.Default) { job.poll() }) {
                        is JobSnapshot.Pending -> {
                            machine.onProgress(token, polled.progress?.toFloat())
                            publish()
                            delay(180)
                        }
                        is JobSnapshot.Completed -> {
                            done = true
                            when (val outcome = polled.outcome) {
                                is EditOutcome.Success -> {
                                    val created = withContext(Dispatchers.IO) {
                                        outcome.images.map { image ->
                                            AiEditWorkingCandidate(
                                                id = UUID.randomUUID().toString(),
                                                fileName = store.writeCandidate(image),
                                                parentId = parent?.id,
                                                prompt = prior.prompt,
                                                providerId = provider.id,
                                                model = outcome.providerModel,
                                                modelVersion = outcome.providerVersion
                                            )
                                        }
                                    }
                                    if (machine.onResult(token, created)) publish()
                                }
                                is EditOutcome.Refused -> {
                                    if (machine.onRefused(token, outcome.providerCode)) publish()
                                }
                                is EditOutcome.Failed -> {
                                    if (machine.onFailed(token,
                                            "The provider could not finish this edit. Please retry.")) publish()
                                }
                                EditOutcome.Cancelled -> if (machine.cancel()) publish()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // UI cancel already transitioned; never turn it into a failure.
            } catch (_: Exception) {
                if (machine.onFailed(token,
                        "The selected photo or temporary result is unavailable.")) publish()
            } finally {
                // A cancelled generation may finish cleanup after a user starts another.
                // Its finally block must never erase the newer job's cancellation handles.
                if (activeCoroutine === thisJob) {
                    activeProviderJob = null
                    activeCoroutine = null
                }
            }
        }
    }

    fun accept(context: Context, originalUri: Uri, store: AiEditWorkingStore,
               onRefresh: () -> Unit, onSaved: (Uri) -> Unit) {
        val chosen = machine.beginAccept() ?: return
        publish()
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.candidateFile(chosen.fileName) }
                val result = AiEditSaver(context).save(AiEditCandidate(
                    originalUri = originalUri,
                    candidateUri = Uri.fromFile(file),
                    originalRevision = sourceRevision(context, originalUri),
                    provider = chosen.providerId,
                    model = chosen.model,
                    modelVersion = chosen.modelVersion,
                    prompt = chosen.prompt,
                    settingsJson = "{\"mode\":\"synthetic-mock\"}",
                    format = AiEditImageFormat.PNG,
                    candidateParentUri = null
                ), onRefresh = onRefresh)
                if (machine.saved()) publish()
                onSaved(result.uri)
            } catch (cancelled: CancellationException) {
                // AIE-4's journal handles interrupted inserts; never claim Saved on cancellation.
                throw cancelled
            } catch (_: Exception) {
                if (machine.saveFailed()) publish()
            }
        }
    }

    private suspend fun sourceRevision(context: Context, uri: Uri): String =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
            context.contentResolver.query(uri, arrayOf(
                MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE
            ), null, null, null)?.use {
                check(it.moveToFirst()) { "Selected original disappeared" }
                "mediastore:${it.getLong(0)}:${it.getLong(1)}"
            } ?: error("Cannot verify original revision")
        }

    /** Explicit exit only. onCleared must NOT purge work: process death needs it. */
    fun discard(store: AiEditWorkingStore, onDone: () -> Unit) {
        cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.cleanUp() }
            handle.remove<String>("snapshot")
            onDone()
        }
    }

    private fun writeState(s: AiEditUiState): String = JSONObject().apply {
        put("phase", s.phase.name)
        put("prompt", s.prompt)
        put("provider", s.providerId)
        put("progress", s.progress)
        put("generation", s.generation)
        put("selected", s.selectedId)
        put("parent", s.branchParentId)
        put("refusal", s.refusalCode)
        put("error", s.error)
        put("candidates", JSONArray().also { arr ->
            s.candidates.take(24).forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id); put("file", c.fileName); put("parent", c.parentId)
                    put("prompt", c.prompt); put("provider", c.providerId)
                    put("model", c.model); put("version", c.modelVersion)
                })
            }
        })
    }.toString()

    private fun readState(serialized: String): AiEditUiState = try {
        val json = JSONObject(serialized)
        val arr = json.optJSONArray("candidates") ?: JSONArray()
        val candidates = (0 until minOf(arr.length(), 24)).mapNotNull {
            arr.optJSONObject(it)?.let { c ->
                AiEditWorkingCandidate(c.optString("id"), c.optString("file"),
                    c.optString("parent").ifBlank { null }, c.optString("prompt"),
                    c.optString("provider"), c.optString("model"), c.optString("version"))
            }
        }
        AiEditUiState(
            phase = runCatching { AiEditPhase.valueOf(json.optString("phase")) }
                .getOrDefault(AiEditPhase.Composing),
            prompt = json.optString("prompt").take(16_384),
            providerId = "atlas-synthetic-mock", // Unknown providers never become implicit targets.
            progress = null,
            generation = json.optLong("generation").coerceAtLeast(0),
            candidates = candidates,
            selectedId = json.optString("selected").ifBlank { null },
            branchParentId = json.optString("parent").ifBlank { null },
            refusalCode = json.optString("refusal").ifBlank { null },
            error = json.optString("error").ifBlank { null }
        )
    } catch (_: Exception) { AiEditUiState(phase = AiEditPhase.Composing) }
}
