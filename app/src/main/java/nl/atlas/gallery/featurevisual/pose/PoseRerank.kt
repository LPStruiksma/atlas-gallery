package nl.atlas.gallery.featurevisual.pose

/**
 * The opt-in "emphasise pose" control (goal #468, VS-S2p): re-ranks the semantic top [TOP_K] by
 * `semantic + WEIGHT × (pose − POSE_PRIOR)`, as `rerank_p10` in
 * `tools/visual_search_bench/bench.py` measured it (VS-S0: cartwheel P@10 0.329 → 0.350,
 * handstand 0.575 → 0.600; it is not a default fusion because it hurts yoga and hug).
 *
 * - A query without a body ([query] null) keeps the semantic order.
 * - A candidate without a body counts as pose similarity 0, i.e. `semantic − WEIGHT × POSE_PRIOR`.
 * - Items below the semantic top [TOP_K] keep their semantic order after the re-ranked block.
 *
 * Pure function over plain values, so it runs in JVM tests and in any caller thread.
 */
object PoseRerank {
    const val TOP_K = 50
    const val WEIGHT = 0.15f
    const val POSE_PRIOR = 0.3f

    /** One search hit: [semantic] is the cosine to the query, [pose] its stored descriptor, if any. */
    class Candidate(val id: Long, val semantic: Float, val pose: FloatArray?)

    /** A ranked hit with the score it was ranked by. */
    data class Ranked(val id: Long, val score: Float)

    /**
     * Ranks [candidates] (any order) for a query whose pose descriptor is [query].
     * Ties keep the semantic order, and among equal semantic scores the input order.
     */
    fun rerank(
        query: FloatArray?,
        candidates: List<Candidate>,
        topK: Int = TOP_K,
        weight: Float = WEIGHT,
        posePrior: Float = POSE_PRIOR,
    ): List<Ranked> {
        require(topK >= 0) { "topK must not be negative" }
        val semantic = candidates.sortedByDescending { it.semantic }
        if (query == null) return semantic.map { Ranked(it.id, it.semantic) }
        val head = semantic.take(topK).map { c ->
            val pose = c.pose?.let { PoseDescriptor.similarity(query, it) } ?: 0f
            Ranked(c.id, c.semantic + weight * (pose - posePrior))
        }.sortedByDescending { it.score }
        return head + semantic.drop(topK).map { Ranked(it.id, it.semantic) }
    }
}
