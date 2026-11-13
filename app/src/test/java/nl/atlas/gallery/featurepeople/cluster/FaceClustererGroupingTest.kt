package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.ClusterPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #579: "a person that is on 1,000 images will be stored as 1,000 faces".
 *
 * Synthetic, gallery-like faces (no real face data): every face is its
 * identity mixed with a random direction so that two faces of one person
 * score cosine [lo, hi], and a quarter of a person's faces are weak (small,
 * blurred, turned: 0.08-0.20 to another face). Strangers appear once each.
 * Relatives share 30% of their identity direction.
 *
 * Synthetic strangers here sit near cosine 0.04; real SFace strangers do not
 * (PR #581 finding: 0.29% of real stranger pairs reach 0.38, and vectors
 * share a common direction). So these tests prove the mechanisms
 * (consolidation, vetoes, determinism), not that SFACE_GALLERY is safe on a
 * phone. ClusterPolicy.DEFAULT is the rule measured on real faces
 * (docs/people/PEOPLE_BENCH.md), not SFACE_GALLERY.
 */
class FaceClustererGroupingTest {
    private val model = "sface-2021dec-int8"
    private val dim = 128
    private var nextId = 1
    private fun id() = NativeFaceObservationId("%032x".format(nextId++))

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(rng: Random): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    /** Unit vector with cosine ~[s] to [a] (b is a random, near-orthogonal unit). */
    private fun mix(a: DoubleArray, b: DoubleArray, s: Double): DoubleArray {
        val r = DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * b[it] }
        val n = sqrt(r.sumOf { it * it })
        return DoubleArray(dim) { r[it] / n }
    }
    private fun face(identity: DoubleArray, rng: Random, lo: Double, hi: Double,
                     weakShare: Double = 0.25): FaceEmbedding {
        val pair = if (rng.nextDouble() < weakShare) 0.08 + 0.12 * rng.nextDouble()
            else lo + (hi - lo) * rng.nextDouble()
        val f = mix(identity, unit(rng), sqrt(pair))
        return FaceEmbedding(id(), model, FloatArray(dim) { f[it].toFloat() })
    }

    private class Library(val faces: List<FaceEmbedding>,
                          val person: Map<NativeFaceObservationId, Int>, val counts: List<Int>)

    private fun library(seed: Int, lo: Double, hi: Double): Library {
        val rng = Random(seed)
        val common = unit(rng)
        val counts = List(30) { maxOf(3, 300 / (it + 1)) }
        val ids = ArrayList<DoubleArray>()
        val faces = ArrayList<FaceEmbedding>()
        val person = HashMap<NativeFaceObservationId, Int>()
        for (p in counts.indices) {
            val identity = if (p % 5 == 1) mix(ids[p - 1], unit(rng), 0.30)
                else mix(common, unit(rng), 0.30)
            ids += identity
            repeat(counts[p]) { face(identity, rng, lo, hi).also { faces += it; person[it.observation] = p } }
        }
        repeat(400) {
            face(mix(common, unit(rng), 0.30), rng, lo, hi, weakShare = 0.0)
                .also { faces += it; person[it.observation] = STRANGER }
        }
        return Library(faces.shuffled(Random(seed + 1)), person, counts)
    }

    /** What the store does: publications of [batch] faces, each update + consolidate. */
    private fun publishAll(faces: List<FaceEmbedding>, policy: ClusterPolicy, batch: Int): FaceClusterState {
        var state = FaceClusterState.empty(model, dim, policy)
        for (chunk in faces.sortedBy { it.observation.localHex }.chunked(batch)) {
            state = ClusterPublication.publish(state, chunk)
        }
        return state
    }

    private class Score(val wrongFaces: Int, val meanShare: Double, val gridTiles: Int,
                        val peopleOnGrid: Set<Int>, val strangerTiles: Int)

    private fun score(lib: Library, state: FaceClusterState): Score {
        val clusters = state.unknownClusters
        var wrong = 0
        for (c in clusters) {
            val majority = c.members.groupingBy { lib.person.getValue(it) }.eachCount().maxOf { it.value }
            wrong += c.members.size - majority
        }
        val share = lib.counts.indices.map { p ->
            clusters.maxOf { c -> c.members.count { lib.person[it] == p } }.toDouble() / lib.counts[p]
        }
        val grid = clusters.filter { it.members.size >= 3 }
        fun owner(c: UnknownFaceCluster) =
            c.members.groupingBy { lib.person.getValue(it) }.eachCount().maxBy { it.value }.key
        return Score(wrong, share.average(), grid.size,
            grid.map(::owner).filter { it != STRANGER }.toSet(),
            grid.count { owner(it) == STRANGER })
    }

    @Test fun conservativePolicySplitsOnePersonIntoManyFragments() {
        // The phone's symptom, reproduced: same-person cosine 0.25-0.45.
        val lib = library(seed = 579, lo = 0.25, hi = 0.45)
        val s = score(lib, publishAll(lib.faces, ClusterPolicy.SFACE_CONSERVATIVE, lib.faces.size))
        assertTrue("conservative keeps about half of a person together, was ${s.meanShare}",
            s.meanShare < 0.6)
    }

    @Test fun galleryPolicyGroupsEachPersonIntoOneTile() {
        for ((lo, hi) in listOf(0.25 to 0.45, 0.30 to 0.50, 0.35 to 0.55)) {
            val lib = library(seed = 579, lo = lo, hi = hi)
            for (batch in listOf(lib.faces.size, 64)) {
                val s = score(lib, publishAll(lib.faces, ClusterPolicy.SFACE_GALLERY, batch))
                val tag = "same-person cos [$lo,$hi] batch $batch"
                assertTrue("$tag: most of each person's photos in one group, was ${s.meanShare}",
                    s.meanShare >= 0.82)
                // A person with 10-14 photos that all score below the join
                // threshold with each other may not form a group yet; every
                // person with 20+ photos must, and nobody gets two tiles.
                val frequent = lib.counts.indices.filter { lib.counts[it] >= 20 }.toSet()
                assertTrue("$tag: every frequent person on the >=3 grid, had ${s.peopleOnGrid}",
                    s.peopleOnGrid.containsAll(frequent))
                assertTrue("$tag: at least 28 of 30 people on the grid", s.peopleOnGrid.size >= 28)
                assertEquals("$tag: one grid tile per person", s.peopleOnGrid.size, s.gridTiles)
                assertEquals("$tag: strangers never form a grid tile", 0, s.strangerTiles)
                assertTrue("$tag: at most 1% of faces in another person's group, was ${s.wrongFaces}",
                    s.wrongFaces <= lib.faces.size / 100)
            }
        }
    }

    @Test fun sameIdentitySetBecomesOneCluster() {
        val rng = Random(11)
        val identity = unit(rng)
        val faces = List(400) { face(identity, rng, 0.30, 0.50, weakShare = 0.0) }
        val state = publishAll(faces, ClusterPolicy.SFACE_GALLERY, 64)
        assertEquals(400, state.unknownClusters.first().members.size)
        assertEquals(1, state.unknownClusters.size)
    }

    @Test fun cannotLinkKeepsTwoCloseIdentitiesApart() {
        val rng = Random(12)
        val a = unit(rng)
        val b = mix(a, unit(rng), 0.75) // look-alikes: identity cosine 0.75
        val fa = List(60) { face(a, rng, 0.40, 0.60, weakShare = 0.0) }
        val fb = List(60) { face(b, rng, 0.40, 0.60, weakShare = 0.0) }
        val merged = publishAll(fa + fb, ClusterPolicy.SFACE_GALLERY, 32)
        assertEquals("without a review fact they look like one person", 1,
            merged.unknownClusters.count { it.members.size >= 3 })
        // "Not the same person" between one face of each: the two never share a group.
        val facts = ClusterReviewFacts(cannotLink = setOf(FacePair.of(fa[0].observation, fb[0].observation)))
        var state = FaceClusterer.update(FaceClusterState.empty(model, dim, ClusterPolicy.SFACE_GALLERY),
            reviews = facts)
        for (chunk in (fa + fb).sortedBy { it.observation.localHex }.chunked(32)) {
            state = ClusterPublication.publish(state, chunk)
        }
        val ca = state.clusterOf(fa[0].observation)
        val cb = state.clusterOf(fb[0].observation)
        assertTrue(ca != null && cb != null && ca != cb)
        for (c in state.unknownClusters) {
            assertTrue(!(fa[0].observation in c.members && fb[0].observation in c.members))
        }
    }

    @Test fun consolidationNeverMovesConfirmedFaces() {
        val rng = Random(13)
        val identity = unit(rng)
        val faces = List(40) { face(identity, rng, 0.30, 0.50) }
        val person = PersonKey("p1")
        val confirmed = faces.take(5).associate { it.observation to person }
        var state = FaceClusterer.update(FaceClusterState.empty(model, dim, ClusterPolicy.SFACE_GALLERY),
            reviews = ClusterReviewFacts(confirmed = confirmed))
        state = ClusterPublication.publish(state, faces)
        for (f in confirmed.keys) assertEquals(null, state.clusterOf(f))
        assertEquals(35, state.unknownClusters.sumOf { it.members.size })
    }

    @Test fun consolidateIsDeterministicAndIdempotent() {
        val lib = library(seed = 7, lo = 0.25, hi = 0.45)
        val a = publishAll(lib.faces, ClusterPolicy.SFACE_GALLERY, 128)
        val b = publishAll(lib.faces.shuffled(Random(3)), ClusterPolicy.SFACE_GALLERY, 128)
        assertEquals(a.unknownClusters, b.unknownClusters)
        assertEquals(a.unknownClusters, FaceClusterer.consolidate(a).unknownClusters)
    }

    @Test fun policiesWithoutAttachNeverConsolidate() {
        val lib = library(seed = 3, lo = 0.25, hi = 0.45)
        for (policy in listOf(ClusterPolicy.SFACE_CONSERVATIVE, ClusterPolicy.DEFAULT)) {
            val state = FaceClusterer.update(FaceClusterState.empty(model, dim, policy),
                add = lib.faces)
            assertTrue(FaceClusterer.consolidate(state) === state)
        }
    }

    @Test fun diagnosticLineHoldsAggregatesOnly() {
        val lib = library(seed = 5, lo = 0.25, hi = 0.45)
        val state = publishAll(lib.faces, ClusterPolicy.SFACE_GALLERY, lib.faces.size)
        val d = FaceClusterDiagnostics.of(state)
        assertEquals(lib.faces.size, d.faces)
        assertEquals(state.unknownClusters.size, d.clusters)
        assertEquals(state.unknownClusters.size, d.clusterSizes.sum())
        assertEquals(FaceClusterDiagnostics.MAX_QUERIES, d.nearestNeighbour.sum())
        val line = d.logLine()
        assertTrue(line, line.startsWith("people_clusters policy=j=0.38;"))
        for (f in lib.faces) assertTrue("no face id in the line", f.observation.localHex !in line)
        assertTrue(line, Regex("""[\w=.;():,+ \-]+""").matches(line))
    }

    private companion object { const val STRANGER = -1 }
}
