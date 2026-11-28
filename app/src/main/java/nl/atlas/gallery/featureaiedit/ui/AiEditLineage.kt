package nl.atlas.gallery.featureaiedit.ui

/**
 * Local refine lineage (AIE-21 rev 5, gesture and data contract item 4). Pure: no Android, IO or
 * Compose. Candidates carry `id`, `parentId` and `prompt`; this walks that graph with protection
 * against cycles, missing parents and runaway depth, and names steps without internal ids.
 */
object AiEditLineage {
    /** A chain longer than this is cut; the trail then says earlier steps are hidden. */
    const val MAX_DEPTH = 32

    /** One step of the trail. A null [candidateId] is the untouched original photo. */
    data class Step(val candidateId: String?, val label: String)

    /**
     * The trail from the original to the selected result.
     * [broken] is true when an ancestor is missing (e.g. its file was gone after a restore), the
     * graph has a cycle, or the chain was cut at [MAX_DEPTH]: the earliest shown step is then not a
     * direct child of the original.
     */
    data class Trail(val steps: List<Step>, val broken: Boolean) {
        /** The selected step (the last one), or the original when nothing is selected. */
        val current: Step get() = steps.last()
    }

    /** What the trail row shows in one line: at most Original › parent › current. */
    data class Compact(val steps: List<Step>, val hiddenSteps: Int, val broken: Boolean) {
        val needsHistory: Boolean get() = hiddenSteps > 0 || broken
    }

    val ORIGINAL = Step(null, "Original")

    /** "Result N" by position in the session, so a result keeps its name while others are added. */
    fun label(candidates: List<AiEditWorkingCandidate>, id: String): String {
        val index = candidates.indexOfFirst { it.id == id }
        return if (index < 0) "Earlier result" else "Result ${index + 1}"
    }

    /** Ancestors of [selectedId] (oldest first, selected last). Empty when nothing is selected. */
    fun chain(candidates: List<AiEditWorkingCandidate>, selectedId: String?): Pair<List<AiEditWorkingCandidate>, Boolean> {
        val byId = HashMap<String, AiEditWorkingCandidate>(candidates.size)
        for (c in candidates) byId.putIfAbsent(c.id, c)
        val out = ArrayList<AiEditWorkingCandidate>()
        val seen = HashSet<String>()
        var cursor = selectedId?.let(byId::get)
        var broken = false
        while (cursor != null) {
            if (!seen.add(cursor.id) || out.size >= MAX_DEPTH) { broken = true; break }
            out += cursor
            val parent = cursor.parentId ?: break
            cursor = byId[parent]
            if (cursor == null) broken = true
        }
        return out.reversed() to broken
    }

    fun trail(candidates: List<AiEditWorkingCandidate>, selectedId: String?): Trail {
        val (chain, broken) = chain(candidates, selectedId)
        return Trail(listOf(ORIGINAL) + chain.map { Step(it.id, label(candidates, it.id)) }, broken)
    }

    fun compact(trail: Trail): Compact {
        val steps = trail.steps
        if (steps.size <= 3) return Compact(steps, 0, trail.broken)
        return Compact(listOf(steps.first(), steps[steps.size - 2], steps.last()), steps.size - 3, trail.broken)
    }
}

/** What the next Generate actually sends as its photo. */
sealed interface AiEditRefineSource {
    /** The photo the user opened, unchanged. */
    data object Original : AiEditRefineSource
    /** A result from this session, by its private cache basename. */
    data class Candidate(val id: String, val fileName: String) : AiEditRefineSource

    companion object {
        /**
         * The branch parent chosen with "Edit this result" (or by selecting a result) when it still
         * exists in the session; otherwise the original. Never an unrelated or newest result.
         */
        fun of(state: AiEditUiState): AiEditRefineSource {
            val id = state.branchParentId ?: return Original
            val candidate = state.candidates.firstOrNull { it.id == id } ?: return Original
            return Candidate(candidate.id, candidate.fileName)
        }
    }
}
