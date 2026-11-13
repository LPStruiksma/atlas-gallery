package nl.atlas.gallery.featureorganization

/** The host owns and revalidates media membership, grants and actual operations. */
enum class BatchSelectionMode { LIBRARY, TRASH }
enum class BatchSelectionAction { SHARE, FAVORITE, COPY, MOVE, TRASH, RESTORE }

data class BatchSelectionUiState(
    val selectedCount: Int,
    val eligibleCount: Int,
    /** False until the integrator verifies the live authorized publication. */
    val sourceCurrent: Boolean = false,
    val isOperating: Boolean = false,
    val mode: BatchSelectionMode = BatchSelectionMode.LIBRARY
)

data class BatchSelectionPlan(
    val caption: String,
    val canSelectAll: Boolean,
    val actions: List<BatchSelectionAction>,
    /** Offered actions stay visible, but disabled, if access is stale or busy. */
    val presentedActions: List<BatchSelectionAction> = actions,
    /** Consent is requested by the host through Android, not granted by UI. */
    val consentRequired: Set<BatchSelectionAction> = emptySet()
)

/**
 * Conservative presentation, not authorization to mutate MediaStore.
 * The caller provides only actions with real callbacks; the host must still
 * verify current grants, IDs and Android's explicit consent at click time.
 */
fun planBatchSelection(
    state: BatchSelectionUiState,
    provided: Set<BatchSelectionAction>
): BatchSelectionPlan {
    val valid = state.sourceCurrent && !state.isOperating &&
        state.selectedCount >= 0 && state.eligibleCount >= 0 &&
        state.selectedCount <= state.eligibleCount
    val caption = when {
        !state.sourceCurrent -> "Library changed · select again"
        state.isOperating -> "Working with selection…"
        state.selectedCount < 0 || state.eligibleCount < 0 ||
            state.selectedCount > state.eligibleCount ->
            "Selection unavailable"
        state.selectedCount == 1 -> "1 selected"
        else -> "${state.selectedCount} selected"
    }
    val allowed = when (state.mode) {
        BatchSelectionMode.LIBRARY -> listOf(
            BatchSelectionAction.SHARE, BatchSelectionAction.FAVORITE,
            BatchSelectionAction.COPY, BatchSelectionAction.MOVE,
            BatchSelectionAction.TRASH
        )
        BatchSelectionMode.TRASH -> listOf(BatchSelectionAction.RESTORE)
    }
    val presented = if (state.selectedCount > 0)
        allowed.filter { it in provided } else emptyList()
    val enabled = if (valid && state.selectedCount > 0) presented.filter { action ->
        when (action) {
            BatchSelectionAction.SHARE -> state.selectedCount <= MAX_BATCH_SHARE_ITEMS
            BatchSelectionAction.TRASH -> state.selectedCount <= MAX_BATCH_TRASH_ITEMS
            else -> true
        }
    } else emptyList()
    return BatchSelectionPlan(
        caption = caption,
        canSelectAll = valid && state.selectedCount < state.eligibleCount,
        actions = enabled,
        presentedActions = presented,
        consentRequired = if (BatchSelectionAction.TRASH in presented)
            setOf(BatchSelectionAction.TRASH) else emptySet()
    )
}
