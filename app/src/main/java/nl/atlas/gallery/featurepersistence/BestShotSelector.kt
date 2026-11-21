package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.FaceQualityScorer

/** One present, authorized, published member of a *single* person. Never compare identities. */
internal data class BestShotCandidate(
    val faceUid: String,
    val qualityScore: Float?,
    val qualityVersion: Int?,
    val belowSizeFloor: Boolean?,
    val frameMs: Long?
)

/**
 * COVER-2: photos above the source-pixel size floor take precedence over video
 * frames above it. A legacy/undersized observation remains a viable fallback.
 * Preserve the previous winner when another shot in the same tier improves by
 * at most [SWITCH_MARGIN]. A missing winner is never kept after a split/delete.
 * Only observed scores participate; null is unknown, not numerical zero.
 */
internal object BestShotSelector {
    const val SWITCH_MARGIN = 0.05f

    private fun tier(candidate: BestShotCandidate): Int {
        val eligible = candidate.qualityScore != null &&
            candidate.qualityVersion == FaceQualityScorer.VERSION &&
            candidate.belowSizeFloor == false
        return when {
            !eligible -> 0
            candidate.frameMs == null -> 2
            else -> 1
        }
    }

    private val ranking = compareByDescending<BestShotCandidate> { tier(it) }
        .thenByDescending { it.qualityScore != null }
        .thenByDescending { it.qualityScore }
        .thenBy { it.faceUid }

    fun choose(candidates: List<BestShotCandidate>, incumbentUid: String?): BestShotCandidate? {
        val best = candidates.minWithOrNull(ranking) ?: return null
        val incumbent = candidates.firstOrNull { it.faceUid == incumbentUid } ?: return best
        if (best.faceUid == incumbent.faceUid) return incumbent
        val priority = tier(best).compareTo(tier(incumbent))
        if (priority > 0) return best
        if (priority < 0) return incumbent
        val better = best.qualityScore
        val current = incumbent.qualityScore
        // A newly *known* score is evidence, not an arithmetic comparison to zero.
        if (better != null && current == null) return best
        if (better == null || current == null) return incumbent
        // Float scores are stored as REAL and decoded as Float. Keep the exact
        // 0.05 boundary stable despite binary rounding of e.g. 0.70 - 0.65.
        return if (better.toDouble() - current.toDouble() >
            SWITCH_MARGIN.toDouble() + 0.000001) best
            else incumbent
    }
}
