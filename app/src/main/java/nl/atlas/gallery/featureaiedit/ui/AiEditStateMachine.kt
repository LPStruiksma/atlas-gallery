package nl.atlas.gallery.featureaiedit.ui

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
    val error: String? = null
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
            progress = null, refusalCode = null, error = null
        )
        return true
    }

    /** Provider changes never dispatch work. The user must tap Generate again. */
    fun chooseProvider(id: String): Boolean {
        if (id != "atlas-synthetic-mock" || state.phase in
            setOf(AiEditPhase.Generating, AiEditPhase.Accepting, AiEditPhase.Saved)) return false
        state = state.copy(providerId = id)
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
            progress = 0f, refusalCode = null, error = null)
        return token
    }

    private fun current(token: Long) =
        state.phase == AiEditPhase.Generating && state.generation == token

    fun onProgress(token: Long, fraction: Float?): Boolean {
        if (!current(token)) return false
        state = state.copy(progress = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f))
        return true
    }

    fun onResult(token: Long, produced: List<AiEditWorkingCandidate>): Boolean {
        if (!current(token) || produced.isEmpty() || produced.size > 4 ||
            produced.any { it.fileName.isBlank() || it.id.isBlank() ||
                it.providerId != state.providerId || it.prompt != state.prompt } ||
            produced.map { it.id }.distinct().size != produced.size ||
            produced.any { next -> state.candidates.any { it.id == next.id } }) return false
        state = state.copy(phase = AiEditPhase.Result, progress = null,
            candidates = state.candidates + produced, selectedId = produced.first().id,
            branchParentId = produced.first().id)
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
        state = state.copy(phase = AiEditPhase.Cancelled, progress = null)
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
