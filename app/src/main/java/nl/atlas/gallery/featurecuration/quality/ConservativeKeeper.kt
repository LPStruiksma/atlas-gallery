package nl.atlas.gallery.featurecuration.quality

data class KeeperCandidate(
    val id: String,
    val quality: WholePhotoQuality?,
    val face: FaceEvidence? = null,
    val isPreferredEditOrRaw: Boolean = false,
    val hardNegativeOrDistinct: Boolean = false
)

sealed class KeeperDecision {
    data class Suggest(val id: String) : KeeperDecision()
    data class Abstain(val reason: String) : KeeperDecision()
}

/** Advisory only. This must be called only for an already-known stack. */
object ConservativeKeeper {
    private const val MIN_BLUR_DELTA = .002

    fun choose(candidates: List<KeeperCandidate>): KeeperDecision {
        if (candidates.size < 2) return KeeperDecision.Abstain("fewer than two stack members")
        if (candidates.any { it.hardNegativeOrDistinct }) return KeeperDecision.Abstain("hard negative or distinct image")
        val preferred = candidates.filter { it.isPreferredEditOrRaw }
        if (preferred.size == 1) return KeeperDecision.Suggest(preferred.single().id)
        if (preferred.size > 1) return KeeperDecision.Abstain("multiple preferred edits or RAW files")
        if (candidates.any { it.quality?.blur == null || it.quality.exposure == null })
            return KeeperDecision.Abstain("missing whole-photo signal")
        val safe = candidates.filter {
            val exposure = it.quality!!.exposure!!
            exposure.clippedDarkFraction <= .25 && exposure.clippedLightFraction <= .25 && exposure.spread >= .08
        }
        if (safe.size != candidates.size) return KeeperDecision.Abstain("exposure could be intentional")
        val sorted = safe.sortedWith(compareByDescending<KeeperCandidate> { it.quality!!.blur!!.laplacianVariance }.thenBy { it.id })
        val delta = sorted[0].quality!!.blur!!.laplacianVariance - sorted[1].quality!!.blur!!.laplacianVariance
        if (delta < MIN_BLUR_DELTA) return KeeperDecision.Abstain("tie within provisional margin")
        return KeeperDecision.Suggest(sorted[0].id)
    }
}
