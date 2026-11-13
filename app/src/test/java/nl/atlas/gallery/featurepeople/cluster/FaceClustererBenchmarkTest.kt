package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * D2-3b scale benchmark on synthetic 128-d vectors (no real faces). Skipped
 * unless ATLAS_CLUSTER_BENCH=1, because it takes minutes:
 *
 *     ATLAS_CLUSTER_BENCH=1 gradle :app:testDebugUnitTest \
 *         --tests '*FaceClustererBenchmarkTest'
 *
 * Timings are wall clock on whatever JVM runs it, not on a phone.
 */
class FaceClustererBenchmarkTest {
    private val model = "sface-2021dec-int8"
    private val dim = 128
    private val policy = ClusterPolicy.SFACE_CONSERVATIVE
    private var nextId = 1

    private fun Random.gaussian(): Float {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }

    private fun unit(rng: Random) = FloatArray(dim) { rng.gaussian() }

    private fun face(v: FloatArray) =
        FaceEmbedding(NativeFaceObservationId("%032x".format(nextId++)), model, v)

    /** Every face a stranger: the worst case, one cluster per face. */
    private fun strangers(n: Int, rng: Random) = List(n) { face(unit(rng)) }

    /**
     * A gallery-like mix: [people] recurring identities with a long-tailed
     * face count (a few with thousands, most with a handful), plus strangers.
     */
    private fun gallery(total: Int, people: Int, strangerShare: Double, rng: Random):
        List<FaceEmbedding> {
        val strangers = (total * strangerShare).toInt()
        val known = total - strangers
        val weights = DoubleArray(people) { 1.0 / (it + 1) }
        val wSum = weights.sum()
        val out = ArrayList<FaceEmbedding>(total)
        val sigma = 0.8f / sqrt(dim.toFloat())
        for (p in 0 until people) {
            val c = unit(rng)
            val n = sqrt(c.sumOf { it.toDouble() * it }).toFloat()
            val count = maxOf(1, (known * weights[p] / wSum).toInt())
            repeat(count) { out.add(face(FloatArray(dim) { c[it] / n + sigma * rng.gaussian() })) }
        }
        while (out.size < total) out.add(face(unit(rng)))
        return out.shuffled(rng)
    }

    private fun empty() = FaceClusterState.empty(model, dim, policy)

    private inline fun <T> timed(label: String, block: () -> T): T {
        System.gc()
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BENCH $label: ${ms} ms")
        return r
    }

    private fun clusters(s: FaceClusterState) = s.unknownClusters.size

    @Test fun benchmark() {
        assumeTrue(System.getenv("ATLAS_CLUSTER_BENCH") == "1")
        println("BENCH jvm ${System.getProperty("java.version")}, " +
            "${Runtime.getRuntime().availableProcessors()} cpus, single-threaded")
        // Warm-up so the JIT has compiled the hot loops.
        FaceClusterer.update(empty(), add = strangers(3_000, Random(0)))

        val s5k = strangers(5_000, Random(1))
        val fast5k = timed("5k strangers, one update, new") { FaceClusterer.update(empty(), add = s5k) }
        val ref5k = timed("5k strangers, one update, #409 reference") {
            ReferenceFaceClusterer.update(empty(), add = s5k)
        }
        assertEquals(ref5k.unknownClusters, fast5k.unknownClusters)

        val g5k = gallery(5_000, people = 150, strangerShare = 0.2, rng = Random(2))
        val fastG5k = timed("5k gallery mix, one update, new") { FaceClusterer.update(empty(), add = g5k) }
        val refG5k = timed("5k gallery mix, one update, #409 reference") {
            ReferenceFaceClusterer.update(empty(), add = g5k)
        }
        assertEquals(refG5k.unknownClusters, fastG5k.unknownClusters)
        println("BENCH 5k gallery mix: ${clusters(fastG5k)} clusters, equal to reference")

        val g50k = gallery(50_000, people = 1_500, strangerShare = 0.2, rng = Random(3))
        val one = timed("50k gallery mix, one update, new") { FaceClusterer.update(empty(), add = g50k) }
        println("BENCH 50k gallery mix: ${clusters(one)} clusters")
        var inc = empty()
        timed("50k gallery mix, 100 updates of 500, new") {
            for (chunk in g50k.chunked(500)) inc = FaceClusterer.update(inc, add = chunk)
        }
        println("BENCH 50k gallery mix incremental: ${clusters(inc)} clusters")
        timed("one more update of 500 onto 50k, new") {
            FaceClusterer.update(inc, add = gallery(500, 20, 0.2, Random(5)))
        }
        timed("restore 50k from persisted membership, new") {
            FaceClusterer.restore(model, policy, g50k, inc.unknownClusters.map { it.members },
                ClusterReviewFacts.NONE)
        }

        val s50k = strangers(50_000, Random(4))
        val worst = timed("50k strangers (worst case), one update, new") {
            FaceClusterer.update(empty(), add = s50k)
        }
        println("BENCH 50k strangers: ${clusters(worst)} clusters")
    }
}
