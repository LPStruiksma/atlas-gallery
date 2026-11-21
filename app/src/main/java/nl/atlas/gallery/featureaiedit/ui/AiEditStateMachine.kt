package nl.atlas.gallery.featureaiedit.ui

import nl.atlas.gallery.featureaiedit.engine.AiEditIssue

/**
 * No Android, IO, Compose, provider, or wall-clock dependency. The UI drives this reducer with
 * explicit events; a late job cannot change the state after Cancel or a newer Generate.
 */
enum class AiEditPhase { Idle, Composing, Generating, Result, Refused, Failed, Accepting, Saved, Cancelled }

/** [fileName] is a basename in the current session's private cache, never an arbitrary path. */
data class AiEditWorkingCandidate(
    val id: String,
    val fileName: String,
    val parentId: String?,
    val prompt: String,
    val providerId: String,
    val model: String,
    val modelVersion: String
)

data class AiEditUiState(
    val phase: AiEditPhase = AiEditPhase.Idle,
    val prompt: String = "",
    val providerId: String = "atlas-synthetic-mock",
    val progress: Float? = null,
    val generation: Long = 0L,
    val candidates: List<AiEditWorkingCandidate> = emptyList(),
    val selectedId: String? = null,
    val branchParentId: String? = null,
    val refusalCode: String? = null,
    val error: String? = null,
    /** The selected service's own name, e.g. "Google Gemini" (#431). Null for the offline mock. */
    val serviceName: String? = null,
    /** Model label for the chip, e.g. "Nano Banana 2". */
    val modelName: String? = null,
    /** Which state card is shown (#431 rev 4 D); null while composing, working or on a result. */
    val issue: AiEditIssue? = null,
    /** The service's own reason for a decline, if it gave one. */
    val issueDetail: String? = null,
    /** Working: true until the paid request starts ("Sending photo…"), then "Editing with …". */
    val sending: Boolean = false,
    /** Durable flag mirror: the paid request may have been sent for the current attempt. */
    val submitSent: Boolean = false,
    /** Working with a remote progress fraction the service actually reported. */
    val determinate: Boolean = false
) {
    val selectedCandidate: AiEditWorkingCandidate?
        get() = candidates.firstOrNull { it.id == selectedId }
    val hasUnsavedCandidates: Boolean get() = candidates.isNotEmpty() && phase != AiEditPhase.Saved
}

class AiEditStateMachine(initial: AiEditUiState = AiEditUiState()) {
    var state: AiEditUiState = initial
        private set

    fun compose(prompt: String = state.prompt): Boolean {
        if (state.phase in setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        state = state.copy(
            phase = AiEditPhase.Composing, prompt = prompt.take(16_384),
            progress = null, refusalCode = null, error = null,
            issue = null, issueDetail = null, sending = false, submitSent = false
        )
        return true
    }

    /**
     * Provider changes never dispatch work. The user must tap Generate again. Only ids in
     * [available] (configured, supported services) can be chosen; never an inferred fallback.
     */
    fun chooseProvider(
        id: String,
        available: Set<String> = setOf("atlas-synthetic-mock"),
        serviceName: String? = state.serviceName,
        modelName: String? = state.modelName
    ): Boolean {
        if (id !in available || state.phase in
            setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        state = state.copy(providerId = id, serviceName = serviceName, modelName = modelName)
        return true
    }

    /** Null means branch from the unchanged original. */
    fun selectBranch(candidateId: String?): Boolean {
        if (state.phase in setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        if (candidateId != null && state.candidates.none { it.id == candidateId }) return false
        state = state.copy(branchParentId = candidateId,
            selectedId = candidateId ?: state.selectedId)
        return true
    }

    fun selectCandidate(id: String): Boolean {
        if (state.phase in setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        if (state.candidates.none { it.id == id }) return false
        state = state.copy(selectedId = id, branchParentId = id,
            phase = AiEditPhase.Result, error = null, refusalCode = null)
        return true
    }

    /** Unique generation token fences a late poll, refusal or output from an old job. */
    fun generate(): Long? {
        if (state.phase !in setOf(AiEditPhase.Composing, AiEditPhase.Result,
                AiEditPhase.Refused, AiEditPhase.Failed, AiEditPhase.Cancelled) ||
            state.prompt.isBlank() || state.generation == Long.MAX_VALUE) return null
        val token = state.generation + 1
        state = state.copy(phase = AiEditPhase.Generating, generation = token,
            progress = 0f, refusalCode = null, error = null, issue = null, issueDetail = null,
            sending = true, submitSent = false, determinate = false)
        return token
    }

    /**
     * Check again, or reopening after process death with a saved remote id: Working again for the
     * same request, with no new submit. Allowed only from a recoverable outcome-unknown state or
     * straight after restore.
     */
    fun resume(): Long? {
        val allowed = state.issue == AiEditIssue.UNKNOWN_RECOVERABLE || state.phase in
            setOf(AiEditPhase.Idle, AiEditPhase.Composing)
        if (!allowed || state.generation == Long.MAX_VALUE) return null
        val token = state.generation + 1
        state = state.copy(phase = AiEditPhase.Generating, generation = token, progress = null,
            refusalCode = null, error = null, issue = null, issueDetail = null,
            sending = false, submitSent = true, determinate = false)
        return token
    }

    /** The paid request is about to start: from here on it may have been sent. */
    fun markSent(token: Long): Boolean {
        if (!current(token)) return false
        state = state.copy(sending = false, submitSent = true)
        return true
    }

    /** A classified outcome (#431 rev 4 D, addendum A2–A4). Cancel goes through [cancel]. */
    fun onIssue(token: Long, issue: AiEditIssue, detail: String? = null): Boolean {
        if (!current(token) || issue == AiEditIssue.CANCELLED) return false
        val refused = issue == AiEditIssue.DECLINED
        state = state.copy(
            phase = if (refused) AiEditPhase.Refused else AiEditPhase.Failed,
            progress = null, sending = false, issue = issue,
            issueDetail = detail?.take(160), refusalCode = if (refused) "declined" else null,
            error = null
        )
        return true
    }

    /** Outcome unknown shown directly, e.g. after process death with nothing to look up. */
    fun showIssue(issue: AiEditIssue): Boolean {
        if (state.phase in setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        state = state.copy(phase = AiEditPhase.Failed, progress = null, sending = false,
            issue = issue, issueDetail = null, error = null, submitSent = issue.mayHaveCharged)
        return true
    }

    private fun current(token: Long) =
        state.phase == AiEditPhase.Generating && state.generation == token

    fun onProgress(token: Long, fraction: Float?): Boolean {
        if (!current(token)) return false
        val value = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        state = state.copy(progress = value)
        return true
    }

    fun onResult(token: Long, produced: List<AiEditWorkingCandidate>): Boolean {
        if (!current(token) || produced.isEmpty() || produced.size > 4 ||
            produced.any { it.fileName.isBlank() || it.id.isBlank() ||
                it.providerId != state.providerId || it.prompt != state.prompt } ||
            produced.map { it.id }.distinct().size != produced.size ||
            produced.any { next -> state.candidates.any { it.id == next.id } }) return false
        state = state.copy(phase = AiEditPhase.Result, progress = null, sending = false,
            candidates = state.candidates + produced, selectedId = produced.first().id,
            branchParentId = produced.first().id, issue = null, issueDetail = null)
        return true
    }

    fun onRefused(token: Long, code: String): Boolean {
        if (!current(token)) return false
        state = state.copy(phase = AiEditPhase.Refused, progress = null,
            refusalCode = code.filter { it.isLetterOrDigit() || it in "-_" }.take(80)
                .ifBlank { "unspecified" })
        return true
    }

    fun onFailed(token: Long, description: String): Boolean {
        if (!current(token)) return false
        state = state.copy(phase = AiEditPhase.Failed, progress = null,
            error = description.take(180))
        return true
    }

    fun cancel(): Boolean {
        if (state.phase != AiEditPhase.Generating) return false
        state = state.copy(phase = AiEditPhase.Cancelled, progress = null, sending = false)
        return true
    }

    fun beginAccept(): AiEditWorkingCandidate? {
        if (state.phase !in setOf(AiEditPhase.Result, AiEditPhase.Failed)) return null
        val candidate = state.selectedCandidate ?: return null
        state = state.copy(phase = AiEditPhase.Accepting, error = null)
        return candidate
    }

    fun saveFailed(): Boolean {
        if (state.phase != AiEditPhase.Accepting) return false
        state = state.copy(phase = AiEditPhase.Failed,
            error = "Could not save a new copy. The original is unchanged.")
        return true
    }

    fun saved(): Boolean {
        if (state.phase != AiEditPhase.Accepting) return false
        state = state.copy(phase = AiEditPhase.Saved, error = null)
        return true
    }

    companion object {
        /**
         * Resume only candidates still present in the session cache. An interrupted request is
         * NOT secretly retried after process death (a remote provider might charge again).
         * An interrupted Accept returns to Result so the user can explicitly save again.
         */
        fun restore(snapshot: AiEditUiState, availableFiles: Set<String>): AiEditStateMachine {
            val keep = snapshot.candidates.filter { it.fileName in availableFiles }
            val selected = snapshot.selectedId?.takeIf { id -> keep.any { it.id == id } }
                ?: keep.lastOrNull()?.id
            val branch = snapshot.branchParentId?.takeIf { id -> keep.any { it.id == id } }
            val phase = when (snapshot.phase) {
                AiEditPhase.Generating -> AiEditPhase.Failed
                AiEditPhase.Accepting -> if (selected != null) AiEditPhase.Result else AiEditPhase.Failed
                AiEditPhase.Result -> if (selected != null) AiEditPhase.Result else AiEditPhase.Composing
                else -> snapshot.phase
            }
            val message = if (snapshot.phase == AiEditPhase.Generating)
                "Generation was interrupted. Choose Retry to start a new request."
            else if (snapshot.phase == AiEditPhase.Accepting && selected == null)
                "The temporary result is no longer available."
            else snapshot.error
            return AiEditStateMachine(snapshot.copy(
                phase = phase, candidates = keep, selectedId = selected,
                branchParentId = branch, progress = null, error = message))
        }
    }
}
