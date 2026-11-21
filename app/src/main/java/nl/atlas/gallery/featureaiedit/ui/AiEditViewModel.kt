package nl.atlas.gallery.featureaiedit.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import nl.atlas.gallery.featureaiedit.engine.AiEditGate
import nl.atlas.gallery.featureaiedit.engine.AiEditGates
import nl.atlas.gallery.featureaiedit.engine.AiEditIssue
import nl.atlas.gallery.featureaiedit.engine.AiEditRequestRecord
import nl.atlas.gallery.featureaiedit.engine.AiEditServices
import nl.atlas.gallery.featureaiedit.engine.AiEditStateCopy
import nl.atlas.gallery.featureaiedit.engine.AndroidRequestJournal
import nl.atlas.gallery.featureaiedit.engine.AndroidSourceImageLoader
import nl.atlas.gallery.featureaiedit.engine.KeyFingerprint
import nl.atlas.gallery.featureaiedit.engine.OutputPlanner
import nl.atlas.gallery.featureaiedit.engine.RestoreDecision
import nl.atlas.gallery.featureaiedit.provider.Admission
import nl.atlas.gallery.featureaiedit.provider.CostDisclosure
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequestValidator
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.OversizePolicy
import nl.atlas.gallery.featureaiedit.provider.RemoteHandle
import nl.atlas.gallery.featureaiedit.provider.ResizePlan
import nl.atlas.gallery.featureaiedit.provider.SubmitJournal
import nl.atlas.gallery.featureaiedit.save.AiEditCandidate
import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureaiedit.save.AiEditSaver
import nl.atlas.gallery.featureaiedit.settings.HostedInputs
import nl.atlas.gallery.featureaiedit.settings.ProviderCredentialVault
import nl.atlas.gallery.featureaiedit.settings.ProviderProfile
import nl.atlas.gallery.featureaiedit.settings.ProviderRegistry
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What the editor needs from the app; built once per screen from the application context. */
internal class AiEditEnvironment(
    val context: Context,
    val store: AiEditWorkingStore,
    val settings: ProviderSettingsStore,
    val vault: ProviderCredentialVault,
    val preferences: SharedPreferences,
    val registry: ProviderRegistry
)

/** One service the chip can offer. */
internal data class AiEditServiceChoice(val profileId: String, val serviceName: String, val modelName: String?)

/**
 * Real-service AI Edit (#431). SavedStateHandle holds only a small snapshot; the paid request's
 * durable record lives in the session folder ([AndroidRequestJournal]). No provider call is
 * launched from init or restore, except looking up an already accepted queue request by its saved
 * id (status/result only, never a new submit).
 */
internal class AiEditViewModel(private val handle: SavedStateHandle) : ViewModel() {
    private var machine = AiEditStateMachine()
    var ui by mutableStateOf(AiEditUiState())
        private set
    /** Confirmation that must be answered before Generate proceeds; never persisted. */
    var gate by mutableStateOf<AiEditGate?>(null)
        private set
    /** One-off message, e.g. the "may still charge" cancel note. */
    var toast by mutableStateOf<String?>(null)
        private set
    var services by mutableStateOf<List<AiEditServiceChoice>>(emptyList())
        private set
    /** "This photo will be sent at W×H." when the service needs a smaller copy. */
    var sizeNotice by mutableStateOf<String?>(null)
        private set
    var costLine by mutableStateOf<String?>(null)
        private set

    private var env: AiEditEnvironment? = null
    private var source: Uri? = null
    private var initialized = false
    private var activeCoroutine: Job? = null
    private var activeProviderJob: EditJob? = null
    private var chargeAgainConfirmed = false
    /**
     * Services whose last request may have charged and that no confirmed request has followed yet.
     * Kept apart from the shown issue, because editing or changing the prompt clears the card but
     * must not drop the "may charge again" confirmation (#769 review).
     */
    private val chargeRisk = LinkedHashSet<String>()
    private var journal: AndroidRequestJournal? = null

    fun initialize(environment: AiEditEnvironment, sourceUri: Uri) {
        env = environment
        source = sourceUri
        journal = AndroidRequestJournal(environment.store.sessionDirectory())
        refreshServices()
        if (initialized) {
            // Back from Settings: a service may have been connected, removed or re-keyed.
            reconcileService()
            return
        }
        initialized = true
        handle.get<ArrayList<String>>(CHARGE_RISK)?.let { chargeRisk.addAll(it) }
        val saved = handle.get<String>("snapshot")
        machine = if (saved == null) AiEditStateMachine() else
            AiEditStateMachine.restore(readState(saved), environment.store.existing())
        val profiles = usableProfiles()
        val current = profiles.firstOrNull { it.id == machine.state.providerId }
            ?: environment.settings.defaultProvider()?.takeIf { p -> profiles.any { it.id == p.id } }
            ?: profiles.firstOrNull()
        if (current != null) choose(current)
        val decision = RestoreDecision.of(journal?.read())
        when (decision) {
            RestoreDecision.Normal -> {
                // Interrupted before the paid request started: nothing was sent, prompt kept.
                if (machine.state.phase == AiEditPhase.Failed && machine.state.issue == null &&
                    saved != null && readState(saved).phase == AiEditPhase.Generating) machine.compose()
                if (machine.state.phase == AiEditPhase.Idle) machine.compose()
            }
            is RestoreDecision.Unknown -> {
                machine.compose(decision.record.prompt.ifBlank { machine.state.prompt })
                machine.showIssue(AiEditIssue.UNKNOWN_NONRECOVERABLE)
            }
            is RestoreDecision.Resume -> {
                machine.compose(decision.record.prompt.ifBlank { machine.state.prompt })
                publish()
                checkAgain(decision.record)
                return
            }
        }
        publish()
        refreshEstimate()
    }

    private fun usableProfiles(): List<ProviderProfile> {
        val e = env ?: return emptyList()
        return AiEditServices.usable(e.settings.profiles(), e.registry)
    }

    private fun refreshServices() {
        services = usableProfiles().map {
            AiEditServiceChoice(it.id, AiEditServices.serviceName(it), AiEditServices.modelName(it))
        }
    }

    /** Keeps the selection on a usable service, picking the default one when the current one is gone. */
    private fun reconcileService() {
        val e = env ?: return
        val profiles = usableProfiles()
        if (profiles.none { it.id == machine.state.providerId }) {
            val next = e.settings.defaultProvider()?.takeIf { p -> profiles.any { it.id == p.id } }
                ?: profiles.firstOrNull()
            if (next != null && choose(next)) {
                chargeAgainConfirmed = false
                publish()
            }
        }
        refreshEstimate()
    }

    private fun profile(): ProviderProfile? = usableProfiles().firstOrNull { it.id == ui.providerId }

    private fun choose(profile: ProviderProfile): Boolean = machine.chooseProvider(
        profile.id, usableProfiles().mapTo(HashSet()) { it.id },
        AiEditServices.serviceName(profile), AiEditServices.modelName(profile))

    private fun publish() {
        ui = machine.state
        if (ui.issue?.mayHaveCharged == true) chargeRisk.add(ui.providerId)
        handle["snapshot"] = writeState(ui)
        handle[CHARGE_RISK] = ArrayList(chargeRisk)
    }

    fun promptChanged(text: String) { if (machine.compose(text)) publish() }
    fun selectCandidate(id: String) { if (machine.selectCandidate(id)) publish() }
    fun selectBranch(id: String?) { if (machine.selectBranch(id)) publish() }
    fun changePrompt() { if (machine.compose()) { journal?.clear(); publish() } }
    fun dismissToast() { toast = null }

    /** Provider changes never dispatch work; the user must tap Generate again. */
    fun chooseService(profileId: String) {
        val p = usableProfiles().firstOrNull { it.id == profileId } ?: return
        if (choose(p)) {
            chargeAgainConfirmed = false
            publish()
            refreshEstimate()
        }
    }

    private fun providerFor(p: ProviderProfile, e: AiEditEnvironment, loader: AndroidSourceImageLoader): ImageEditProvider? =
        AiEditServices.create(p, e.registry, HostedInputs(
            apiKey = { e.vault.get(p.id) }, images = loader, outputDirectory = e.store.resultsDirectory()))

    private fun loader(e: AiEditEnvironment): AndroidSourceImageLoader? = source?.let {
        AndroidSourceImageLoader(e.context.contentResolver, it, e.settings.keepLocationInUploads)
    }

    /** Builds the exact request Generate would send; never sends it. */
    private fun buildRequest(provider: ImageEditProvider, loader: AndroidSourceImageLoader): ImageEditRequest? {
        val image = loader.editImage() ?: return null
        val output = OutputPlanner.plan(image.width, image.height, provider.capabilities()) ?: return null
        return ImageEditRequest(source = image, instruction = ui.prompt.ifBlank { " " }, output = output,
            oversizePolicy = OversizePolicy.DOWNSCALE)
    }

    /** Recomputes the cost line and size notice for the selected service. Local work only. */
    fun refreshEstimate() {
        val e = env ?: return
        val p = profile() ?: run { costLine = null; sizeNotice = null; return }
        viewModelScope.launch {
            val (line, notice) = withContext(Dispatchers.IO) {
                runCatching {
                    val l = loader(e) ?: return@runCatching null to null
                    val provider = providerFor(p, e, l) ?: return@runCatching null to null
                    val request = buildRequest(provider, l) ?: return@runCatching null to null
                    val disclosure = CostDisclosure.of(provider.estimateCost(request))
                    val admission = ImageEditRequestValidator.validate(request, provider.capabilities())
                    val down = (admission as? Admission.Accepted)?.plan?.source as? ResizePlan.Downscale
                    AiEditStateCopy.costLine(disclosure, AiEditServices.serviceName(p), AiEditServices.modelName(p)) to
                        down?.let { AiEditStateCopy.sentAtSize(it.width, it.height) }
                }.getOrDefault(null to null)
            }
            costLine = line
            sizeNotice = notice
        }
    }

    private fun disclosureNow(p: ProviderProfile, e: AiEditEnvironment): CostDisclosure = runCatching {
        val l = loader(e) ?: return CostDisclosure.Unknown
        val provider = providerFor(p, e, l) ?: return CostDisclosure.Unknown
        val request = buildRequest(provider, l) ?: return CostDisclosure.Unknown
        CostDisclosure.of(provider.estimateCost(request))
    }.getOrDefault(CostDisclosure.Unknown)

    /** Generate: answers the next open gate, or starts the request when none is left. */
    fun requestGenerate() {
        val e = env ?: return
        val p = profile() ?: return
        if (ui.prompt.isBlank()) return
        viewModelScope.launch {
            val disclosure = withContext(Dispatchers.IO) { disclosureNow(p, e) }
            val next = AiEditGates.next(
                firstUseAcknowledged = e.settings.firstUseAcknowledged,
                disclosure = disclosure,
                priceConsented = e.preferences.getBoolean(PRICE_CONSENT + p.id, false),
                lastIssue = ui.issue,
                chargeAgainConfirmed = chargeAgainConfirmed,
                chargeRiskPending = p.id in chargeRisk)
            gate = next
            if (next == null) generate()
        }
    }

    /** "I understand", "Continue" or "Try again" on the open gate; then the next gate or Generate. */
    fun confirmGate() {
        val e = env ?: return
        val p = profile() ?: return
        when (gate) {
            AiEditGate.FIRST_USE -> e.settings.firstUseAcknowledged = true
            AiEditGate.PRICE_UNKNOWN -> e.preferences.edit().putBoolean(PRICE_CONSENT + p.id, true).apply()
            AiEditGate.CHARGE_AGAIN -> chargeAgainConfirmed = true
            null -> return
        }
        gate = null
        requestGenerate()
    }

    /** "Not now", Cancel or "Open {Service}": closes the gate and sends nothing. */
    fun dismissGate() { gate = null }

    private fun generate() {
        val e = env ?: return
        val p = profile() ?: return
        val l = loader(e) ?: return
        val prior = machine.state
        val retryAfterUncertain = prior.issue?.mayHaveCharged == true || p.id in chargeRisk
        if (retryAfterUncertain && !chargeAgainConfirmed) return
        val token = machine.generate() ?: return
        chargeAgainConfirmed = false
        chargeRisk.remove(p.id)
        journal?.clear()
        publish()
        activeCoroutine = viewModelScope.launch {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                if (!online(e.context)) {
                    // Pre-flight connectivity check before anything is journalled: proven not sent.
                    if (machine.onIssue(token, AiEditIssue.OFFLINE)) publish()
                    return@launch
                }
                val provider = withContext(Dispatchers.IO) { providerFor(p, e, l) }
                val request = withContext(Dispatchers.IO) { buildRequest(provider ?: return@withContext null, l) }
                if (provider == null || request == null) {
                    if (machine.onIssue(token, AiEditIssue.FAILED)) publish()
                    return@launch
                }
                val record = AiEditRequestRecord(token, p.id, provider.id, AiEditServices.serviceName(p),
                    provider.capabilities().remoteRecovery, AiEditRequestRecord.Stage.SENT, prompt = prior.prompt)
                var saved: AiEditRequestRecord? = null
                val submitJournal = object : SubmitJournal {
                    override fun beforeSend(): Boolean {
                        if (ui.generation != token || machine.state.phase != AiEditPhase.Generating) return false
                        journal?.write(record)
                        saved = record
                        viewModelScope.launch { if (machine.markSent(token)) publish() }
                        return true
                    }
                    override fun remoteAccepted(handle: RemoteHandle) {
                        val key = e.vault.get(p.id)
                        val fp = key?.let { KeyFingerprint.of(AndroidRequestJournal.salt(e.preferences), it) }
                        val accepted = record.copy(stage = AiEditRequestRecord.Stage.ACCEPTED,
                            handle = handle.copy(keyFingerprint = fp))
                        journal?.write(accepted)
                        saved = accepted
                    }
                }
                val job = withContext(Dispatchers.IO) { provider.submit(request, submitJournal) }
                activeProviderJob = job
                follow(token, job, p, provider, prior) { saved?.recoverable == true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val issue = if (machine.state.submitSent) AiEditIssue.UNKNOWN_NONRECOVERABLE else AiEditIssue.FAILED
                if (machine.onIssue(token, issue)) publish()
            } finally {
                if (activeCoroutine === thisJob) { activeProviderJob = null; activeCoroutine = null }
            }
        }
    }

    /** Check again (or reopen) for a saved queue request: status/result only, never a new submit. */
    fun checkAgain(recordOverride: AiEditRequestRecord? = null) {
        val e = env ?: return
        val record = recordOverride ?: journal?.read() ?: return
        val p = usableProfiles().firstOrNull { it.id == record.profileId }
        val token = machine.resume() ?: return
        publish()
        activeCoroutine = viewModelScope.launch {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                val l = loader(e)
                val provider = if (p != null && l != null) withContext(Dispatchers.IO) { providerFor(p, e, l) } else null
                val handle = record.handle
                val keyMatches = p != null && handle != null && KeyFingerprint.matches(
                    handle.keyFingerprint, AndroidRequestJournal.salt(e.preferences), e.vault.get(p.id))
                val job = if (provider != null && handle != null && keyMatches &&
                    provider.id == record.providerId) provider.resume(handle) else null
                if (job == null || p == null || provider == null) {
                    // Key replaced or removed, service gone, or handle refused: can't check, never resubmit.
                    if (machine.onIssue(token, AiEditIssue.UNKNOWN_CANNOT_CHECK)) publish()
                    return@launch
                }
                activeProviderJob = job
                follow(token, job, p, provider, machine.state) { true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (machine.onIssue(token, AiEditIssue.UNKNOWN_RECOVERABLE)) publish()
            } finally {
                if (activeCoroutine === thisJob) { activeProviderJob = null; activeCoroutine = null }
            }
        }
    }

    private suspend fun follow(
        token: Long, job: EditJob, p: ProviderProfile, provider: ImageEditProvider,
        prior: AiEditUiState, remoteSaved: () -> Boolean
    ) {
        val parent = prior.branchParentId?.let { id -> prior.candidates.firstOrNull { it.id == id } }
        var done = false
        while (!done && kotlinx.coroutines.currentCoroutineContext().isActive) {
            when (val polled = withContext(Dispatchers.IO) { job.poll() }) {
                is JobSnapshot.Pending -> {
                    if (machine.onProgress(token, polled.progress?.toFloat())) publish()
                    delay(250)
                }
                is JobSnapshot.Completed -> {
                    done = true
                    val outcome = polled.outcome
                    if (outcome is EditOutcome.Success) {
                        val created = withContext(Dispatchers.IO) {
                            outcome.images.map { image ->
                                AiEditWorkingCandidate(
                                    id = UUID.randomUUID().toString(),
                                    fileName = env!!.store.adoptResult(image.handle),
                                    parentId = parent?.id,
                                    prompt = machine.state.prompt,
                                    providerId = p.id,
                                    model = outcome.providerModel,
                                    modelVersion = outcome.providerVersion
                                )
                            }
                        }
                        journal?.clear()
                        if (machine.onResult(token, created)) publish()
                    } else {
                        val issue = AiEditIssue.classify(outcome, remoteSaved()) ?: AiEditIssue.FAILED
                        // Final outcomes (including a charged decline) need no lookup; only unknowns keep it.
                        if (!issue.outcomeUnknown) journal?.clear()
                        if (issue == AiEditIssue.CANCELLED) {
                            if (machine.cancel()) publish()
                        } else {
                            val detail = (outcome as? EditOutcome.Refused)?.providerMessage
                            if (machine.onIssue(token, issue, detail)) publish()
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        val sent = machine.state.submitSent
        val service = machine.state.serviceName
        activeProviderJob?.cancel()
        activeCoroutine?.cancel()
        if (machine.cancel()) {
            journal?.clear()
            machine.compose()
            publish()
            if (sent && service != null) toast = AiEditStateCopy.cancelledToast(service)
        }
    }

    /** Discard on an outcome-unknown card: forget the request, keep the prompt, send nothing. */
    fun discardIssue() {
        journal?.clear()
        chargeAgainConfirmed = false
        if (machine.compose()) publish()
    }

    fun accept(context: Context, originalUri: Uri, store: AiEditWorkingStore,
               onRefresh: () -> Unit, onSaved: (Uri) -> Unit) {
        val chosen = machine.beginAccept() ?: return
        val p = usableProfiles().firstOrNull { it.id == chosen.providerId }
        publish()
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.candidateFile(chosen.fileName) }
                val format = when (file.extension.lowercase()) {
                    "jpg" -> AiEditImageFormat.JPEG
                    "webp" -> AiEditImageFormat.WEBP
                    else -> AiEditImageFormat.PNG
                }
                val settingsJson = JSONObject().apply {
                    put("service", p?.kind?.name ?: "unknown")
                    put("model", chosen.model)
                    put("keepLocation", env?.settings?.keepLocationInUploads ?: false)
                }.toString()
                val result = AiEditSaver(context).save(AiEditCandidate(
                    originalUri = originalUri,
                    candidateUri = Uri.fromFile(file),
                    originalRevision = sourceRevision(context, originalUri),
                    provider = p?.let { AiEditServices.serviceName(it) } ?: chosen.providerId,
                    model = chosen.model,
                    modelVersion = chosen.modelVersion,
                    prompt = chosen.prompt,
                    settingsJson = settingsJson,
                    format = format,
                    candidateParentUri = null
                ), onRefresh = onRefresh)
                if (machine.saved()) publish()
                onSaved(result.uri)
            } catch (cancelled: CancellationException) {
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
        activeProviderJob?.cancel()
        activeCoroutine?.cancel()
        machine.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.cleanUp() }
            handle.remove<String>("snapshot")
            onDone()
        }
    }

    private fun online(context: Context): Boolean = AiEditServices.onlineOverride?.invoke() ?: try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true } // Unknown: let the transport decide (it reports the phase).

    private fun writeState(s: AiEditUiState): String = JSONObject().apply {
        put("phase", s.phase.name)
        put("prompt", s.prompt)
        put("provider", s.providerId)
        put("generation", s.generation)
        put("selected", s.selectedId)
        put("parent", s.branchParentId)
        put("issue", s.issue?.name)
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
        val issue = AiEditIssue.entries.firstOrNull { it.name == json.optString("issue") }
        AiEditUiState(
            phase = runCatching { AiEditPhase.valueOf(json.optString("phase")) }
                .getOrDefault(AiEditPhase.Composing),
            prompt = json.optString("prompt").take(16_384),
            // Re-validated against configured services in initialize(); never an implicit target.
            providerId = json.optString("provider"),
            generation = json.optLong("generation").coerceAtLeast(0),
            candidates = candidates,
            selectedId = json.optString("selected").ifBlank { null },
            branchParentId = json.optString("parent").ifBlank { null },
            // A card that isn't backed by the journal can't claim "may have charged" after restore.
            issue = issue?.takeUnless { it.mayHaveCharged }
        )
    } catch (_: Exception) { AiEditUiState(phase = AiEditPhase.Composing) }

    companion object {
        private const val PRICE_CONSENT = "priceConsent:"
        private const val CHARGE_RISK = "chargeRisk"
    }
}

