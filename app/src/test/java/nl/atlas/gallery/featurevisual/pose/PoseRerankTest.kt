package nl.atlas.gallery.featurevisual.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PoseRerank] against the "emphasise pose" ranking of `bench.rerank_p10` (via `pose_golden.py`):
 * `semantic + 0.15 × (pose − 0.3)` over the semantic top 50, semantic order without a query body.
 */
class PoseRerankTest {
    private val descriptors = BlazePoseGolden.worlds.map(PoseDescriptor::fromWorld)
    private val candidates = BlazePoseGolden.semantic.indices.map { i ->
        val p = BlazePoseGolden.poseOf[i]
        PoseRerank.Candidate(i.toLong(), BlazePoseGolden.semantic[i], if (p < 0) null else descriptors[p])
    }

    @Test
    fun rerankMatchesPythonGolden() {
        val ranked = PoseRerank.rerank(descriptors[0], candidates.shuffled(java.util.Random(7)))
        assertArrayEquals(BlazePoseGolden.rerankOrder.map(Int::toLong).toLongArray(), ranked.map { it.id }.toLongArray())
        assertArrayEquals(BlazePoseGolden.rerankScores, ranked.map { it.score }.toFloatArray(), 1e-5f)
    }

    @Test
    fun queryWithoutBodyKeepsSemanticOrder() {
        val ranked = PoseRerank.rerank(null, candidates)
        assertArrayEquals(BlazePoseGolden.semanticOrder.map(Int::toLong).toLongArray(), ranked.map { it.id }.toLongArray())
    }

    @Test
    fun onlyTheTopKMoves() {
        val ranked = PoseRerank.rerank(descriptors[0], candidates)
        val semantic = BlazePoseGolden.semanticOrder.map(Int::toLong)
        assertEquals(semantic.take(PoseRerank.TOP_K).toSet(), ranked.take(PoseRerank.TOP_K).map { it.id }.toSet())
        assertEquals(semantic.drop(PoseRerank.TOP_K), ranked.drop(PoseRerank.TOP_K).map { it.id })
    }

    @Test
    fun matchingPoseLiftsACloseSemanticRival() {
        val q = descriptors[0]
        val inverted = descriptors[3]
        val ranked = PoseRerank.rerank(q, listOf(
            PoseRerank.Candidate(1, 0.60f, inverted),
            PoseRerank.Candidate(2, 0.58f, q),
            PoseRerank.Candidate(3, 0.59f, null),
        ))
        assertEquals(listOf(2L, 3L, 1L), ranked.map { it.id })
        assertEquals(0.58f + 0.15f * (1f - 0.3f), ranked[0].score, 1e-5f)
        assertEquals(0.59f - 0.15f * 0.3f, ranked[1].score, 1e-5f)
    }
}
