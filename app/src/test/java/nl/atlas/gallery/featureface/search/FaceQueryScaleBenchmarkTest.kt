package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * VS-F1 (#468) scale evidence: exact brute force over 300k synthetic 128-d
 * faces (no real faces). It reports build and query wall-clock time on the
 * JVM that runs it (not a phone) and never fails on time; it does fail if a
 * planted identity is not found. Output: the test's system-out in the Gradle
 * test report, line `VS-F1 scale:`.
 */
class FaceQueryScaleBenchmarkTest {
    private val dim = SFaceModel.DIMENSION
    private val model = SFaceModel.EMBEDDER_VERSION
    private val rng = Random(300_000)

    private fun gaussian(): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
    private fun random() = SFaceModel.l2Normalize(FloatArray(dim) { gaussian() })
    private fun at(base: FloatArray, c: Float): FloatArray {
        val r = random()
        var p = 0f
        for (k in 0 until dim) p += r[k] * base[k]
        val orth = SFaceModel.l2Normalize(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return SFaceModel.l2Normalize(FloatArray(dim) { c * base[it] + s * orth[it] })
    }

    @Test fun bruteForceOver300kFaces() {
        val total = 300_000
        val planted = 40
        val target = random()
        val box = IndexedFaceBox(0.2f, 0.2f, 0.5f, 0.6f)
        val buildStart = System.nanoTime()
        val builder = FaceSearchCorpus.Builder(model, total)
        val plantedIds = HashSet<NativeFaceObservationId>()
        for (i in 0 until total) {
            val id = NativeFaceObservationId("%032x".format(i + 1))
            // About 2 faces per photo: 150k media for 300k faces.
            val media = IndexedMedia("content://media/external/images/media/${i / 2}", "r")
            val plant = i % (total / planted) == 7
            val vector = if (plant) at(target, 0.55f + 0.4f * rng.nextFloat()) else random()
            if (plant) plantedIds += id
            builder.add(id, media, box, null, vector)
        }
        val corpus = builder.build(ClusterReviewFacts.NONE, clustersPublished = true)
        val buildMs = (System.nanoTime() - buildStart) / 1e6
        assertEquals(total, corpus.faceCount)

        val query = FaceQuery(model, listOf(target))
        FaceQueryEngine.search(corpus, query) // warm-up
        val runs = 7
        val times = DoubleArray(runs)
        var result: FaceQueryResult? = null
        for (r in 0 until runs) {
            val t = System.nanoTime()
            result = FaceQueryEngine.search(corpus, query)
            times[r] = (System.nanoTime() - t) / 1e6
        }
        times.sort()
        val res = result!!
        val same = res.hits.flatMap { it.faces }.filter { it.tier == FaceMatchTier.SAME_PERSON }
        val probable = res.hits.flatMap { it.faces }.count { it.tier == FaceMatchTier.PROBABLE }
        println("VS-F1 scale: faces=$total media=${total / 2} build_ms=%.0f query_ms_median=%.1f query_ms_min=%.1f query_ms_max=%.1f same_person=%d probable=%d lookalikes=%d planted=%d"
            .format(buildMs, times[runs / 2], times[0], times[runs - 1], same.size, probable,
                res.lookalikeCount, planted))
        assertTrue("every planted identity face found as SAME_PERSON",
            same.map { it.face }.containsAll(plantedIds))
        assertEquals(planted, plantedIds.size)
    }
}
