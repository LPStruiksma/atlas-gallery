package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.ClusterPublication
import nl.atlas.gallery.featurepersistence.ClusterPublicationCadence
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #508 probe: a whole-library scan's cluster publications on the JVM, with the
 * store's cadence (restore the published seed, add the pending faces).
 * Opt-in: ATLAS_LIBRARY_SCALE=<faces>.
 */
class FaceClustererLibraryScaleTest {
    private val model = "sface-2021dec-int8"
    private val dim = 128
    private val policy = ClusterPolicy.DEFAULT
    private var nextId = 1

    private fun Random.gaussian(): Float {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }

    private fun face(v: FloatArray) =
        FaceEmbedding(NativeFaceObservationId("%032x".format(nextId++)), model, v)

    private fun gallery(total: Int, people: Int, strangerShare: Double, rng: Random): List<FloatArray> {
        val known = total - (total * strangerShare).toInt()
        val weights = DoubleArray(people) { 1.0 / (it + 1) }
        val wSum = weights.sum()
        val out = ArrayList<FloatArray>(total)
        val sigma = 0.8f / sqrt(dim.toFloat())
        for (p in 0 until people) {
            val c = FloatArray(dim) { rng.gaussian() }
            val n = sqrt(c.sumOf { it.toDouble() * it }).toFloat()
            val count = maxOf(1, (known * weights[p] / wSum).toInt())
            repeat(count) { out.add(FloatArray(dim) { c[it] / n + sigma * rng.gaussian() }) }
        }
        while (out.size < total) out.add(FloatArray(dim) { rng.gaussian() })
        return out.shuffled(rng)
    }

    private fun used(): Long { System.gc(); System.gc(); val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }

    @Test fun libraryScan() {
        val total = System.getenv("ATLAS_LIBRARY_SCALE")?.toIntOrNull()
        assumeTrue(total != null)
        val vectors = gallery(total!!, people = total / 100, strangerShare = 0.3, rng = Random(508))
        val base = used()
        var published: FaceClusterState = FaceClusterState.empty(model, dim, policy)
        val pending = ArrayList<FaceEmbedding>()
        var pubMsTotal = 0L; var pubMsMax = 0L; var pubs = 0
        val t0 = System.nanoTime()
        for ((i, v) in vectors.withIndex()) {
            pending += face(v)
            val last = i == vectors.size - 1
            if (ClusterPublicationCadence.due(true, pending.size, published.faceCount, last)) {
                val t = System.nanoTime()
                val seed = if (published.faceCount == 0) published else FaceClusterer.restore(model, policy,
                    published.faces.values, published.unknownClusters.map { it.members }, ClusterReviewFacts.NONE)
                published = ClusterPublication.publish(seed, pending)
                val ms = (System.nanoTime() - t) / 1_000_000
                pubMsTotal += ms; pubMsMax = maxOf(pubMsMax, ms); pubs++
                println("SCALE pub=$pubs faces=${published.faceCount} added=${pending.size} clusters=${published.unknownClusters.size} ms=$ms")
                pending.clear()
            }
        }
        val heap = used() - base
        println("SCALE done faces=$total clusters=${published.unknownClusters.size} pubs=$pubs pub_ms_total=$pubMsTotal pub_ms_max=$pubMsMax wall_ms=${(System.nanoTime() - t0) / 1_000_000} retained_heap_mb=${heap / 1_000_000}")
    }
}
