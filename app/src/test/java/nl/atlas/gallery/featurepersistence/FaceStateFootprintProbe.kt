package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Heap footprint of People's whole-library face state at 263k synthetic faces
 * (Lukas's library size when v0.8.4 hit OOM at 511/512 MB). Skipped unless
 * ATLAS_MEM_PROBE=1; JVM numbers, close to but not the same as ART:
 *
 * Set ATLAS_MEM_PROBE_FACES=25000 or 100000 for the #599 scale comparison.
 *
 *     ATLAS_MEM_PROBE=1 gradle :app:testDebugUnitTest --tests '*FaceStateFootprintProbe'
 */
class FaceStateFootprintProbe {
    private fun used(): Long {
        repeat(4) { System.gc(); Thread.sleep(50) }
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }

    @Test fun probe() {
        assumeTrue(System.getenv("ATLAS_MEM_PROBE") == "1")
        val n = System.getenv("ATLAS_MEM_PROBE_FACES")?.toInt() ?: 263_000
        require(n in setOf(25_000, 100_000, 263_000))
        val model = "sface-2021dec-int8"
        val rng = Random(1)
        val base = used()
        val faces = ArrayList<FaceEmbedding>(n)
        for (i in 0 until n) faces += FaceEmbedding(NativeFaceObservationId("%032x".format(i + 1)),
            model, FloatArray(128) { rng.nextFloat() - 0.5f }, frame = i.toLong() + 1)
        val afterFaces = used()
        println("PROBE embeddings ${n}: ${(afterFaces - base) / 1_048_576} MB, ${(afterFaces - base) / n} B/face")
        // 8,000 groups of varying size, rest singletons (like #51 numbers).
        val clusters = ArrayList<List<NativeFaceObservationId>>()
        var at = 0
        while (at < n * 6 / 10) {
            val size = 3 + rng.nextInt(60)
            clusters += faces.subList(at, minOf(n, at + size)).map { it.observation }
            at += size
        }
        while (at < n) { clusters += listOf(faces[at].observation); at++ }
        val idsHeld = used()
        val t0 = System.nanoTime()
        val state = FaceClusterer.restore(model, ClusterPolicy.DEFAULT, faces, clusters,
            ClusterReviewFacts.NONE)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val afterState = used()
        println("PROBE restore state (+maps, shared embeddings): ${(afterState - idsHeld) / 1_048_576} MB, $ms ms, clusters ${state.unknownClusters.size}")
        val membership = ClusterPublication.membership(state)
        val afterMem = used()
        println("PROBE membership map: ${(afterMem - afterState) / 1_048_576} MB size ${membership.size}")
        val oldRows = HashMap<String, String>(n * 2)
        for ((k, v) in membership) oldRows[String(k.toCharArray())] = String(v.toCharArray())
        val afterOld = used()
        println("PROBE fresh-string membership rows (as read from SQLite): ${(afterOld - afterMem) / 1_048_576} MB")
        // A second whole-library read, as buildClusterState did before (fresh
        // decode) and does now (reusing the remembered state's embeddings).
        val shared = SharedClusterFaces(ClusterPolicy.DEFAULT).apply { remember(state) }
        val prior = shared.prior(model)
        val beforeSecond = used()
        val reusedFaces = faces.map { SharedClusterFaces.reuse(prior, it.observation.localHex, it.frame)!! }
        val reusedState = FaceClusterer.restore(model, ClusterPolicy.DEFAULT, reusedFaces, clusters,
            ClusterReviewFacts.NONE)
        val afterReused = used()
        println("PROBE second copy with shared embeddings: ${(afterReused - beforeSecond) / 1_048_576} MB")
        val rng2 = Random(1)
        val freshFaces = ArrayList<FaceEmbedding>(n)
        for (i in 0 until n) freshFaces += FaceEmbedding(NativeFaceObservationId("%032x".format(i + 1)),
            model, FloatArray(128) { rng2.nextFloat() - 0.5f }, frame = i.toLong() + 1)
        val freshState = FaceClusterer.restore(model, ClusterPolicy.DEFAULT, freshFaces,
            clusters.map { c -> c.map { NativeFaceObservationId(String(it.localHex.toCharArray())) } },
            ClusterReviewFacts.NONE)
        val afterFresh = used()
        println("PROBE second copy decoded fresh (old behaviour): ${(afterFresh - afterReused) / 1_048_576} MB")
        println(state.faceCount + membership.size + oldRows.size + clusters.size + faces.size +
            reusedState.faceCount + freshState.faceCount)
    }
}
