package nl.atlas.gallery.featurepeopleui

/**
 * One-time V4 CSV import mode actually wired by the owner of MainActivity and
 * PeopleRepository. This is DISPLAY ONLY: supplying ADDITIVE is never proof
 * that the repository really has a transactional merge implementation.
 */
enum class ReviewedV4ImportMode {
    /** Existing native imported rows may be replaced (legacy path). */
    REPLACES,
    /**
     * Requires X PeopleRepository.mergeFromV4Export, owner-approved import
     * preview and atomic conflict rejection. Not automatically enabled.
     */
    ADDITIVE_CONFLICT_REJECTING
}

data class ReviewedV4ImportCopy(
    val actionLabel: String,
    val currentAssignmentsExplanation: String?,
    val linkedRefreshExplanation: String
)

/**
 * The two workflows must never share a claim: a safe manual merge does NOT
 * make linked V4 refresh non-destructive. Never hide the live-link warning.
 */
fun reviewedV4ImportCopy(
    mode: ReviewedV4ImportMode,
    alreadyHaveConfirmedPeople: Boolean
): ReviewedV4ImportCopy {
    val linked = "Connecting a live V4 export can REPLACE native People assignments " +
        "on refresh. Confirm its completeness and make a verified backup first."
    if (!alreadyHaveConfirmedPeople) {
        return ReviewedV4ImportCopy(
            actionLabel = "Choose V4 export",
            currentAssignmentsExplanation = null,
            linkedRefreshExplanation = linked
        )
    }
    return when (mode) {
        ReviewedV4ImportMode.REPLACES -> ReviewedV4ImportCopy(
            actionLabel = "Review replacement",
            currentAssignmentsExplanation =
                "Important: this CSV import REPLACES existing native People assignments, " +
                    "even when the selected export is partial. Make a verified backup first.",
            linkedRefreshExplanation = linked
        )
        ReviewedV4ImportMode.ADDITIVE_CONFLICT_REJECTING -> ReviewedV4ImportCopy(
            actionLabel = "Review additive import",
            currentAssignmentsExplanation =
                "This one-time CSV import adds only new reviewed assignments. " +
                    "Conflicting labels abort without changing existing People. " +
                    "It does not change how connected V4 exports refresh.",
            linkedRefreshExplanation = linked
        )
    }
}
