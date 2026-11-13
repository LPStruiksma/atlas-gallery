package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * D2-3b: the faster [FaceClusterer] must return exactly what the #409
 * algorithm ([ReferenceFaceClusterer]) returns: same clusters, same IDs, same
 * suggestions and similarities, through adds, removals, review changes and
 * restores. Synthetic seeded vectors only; no real faces.
 */
class FaceClustererEquivalenceTest {
    private val model = "sface-2021dec-int8"
    private var nextId = 1
    private fun id() = NativeFaceObservationId("%032x".format(nextId++))

    private fun Random.gaussian(): Float {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }

    private fun unit(rng: Random, dim: Int): FloatArray {
        val v = FloatArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    /** Identities, some pairs deliberately close, plus strangers and exact duplicates. */
    private fun faces(rng: Random, dim: Int, identities: Int, strangers: Int): List<FaceEmbedding> {
        val centres = ArrayList<FloatArray>()
        repeat(identities) {
            val c = unit(rng, dim)
            if (centres.isNotEmpty() && rng.nextInt(3) == 0) {
                // A look-alike of an earlier identity, to exercise ambiguity.
                val near = centres[rng.nextInt(centres.size)]
                val w = 0.3f + 0.5f * rng.nextFloat()
                centres.add(FloatArray(dim) { w * near[it] + (1 - w) * c[it] })
            } else centres.add(c)
        }
        val out = ArrayList<FaceEmbedding>()
        for (c in centres) {
            val noise = (0.4f + 0.8f * rng.nextFloat()) / sqrt(dim.toFloat())
            repeat(1 + rng.nextInt(12)) {
                out.add(FaceEmbedding(id(), model, FloatArray(dim) { c[it] + noise * rng.gaussian() }))
            }
        }
        repeat(strangers) { out.add(FaceEmbedding(id(), model, unit(rng, dim))) }
        repeat(3) {
            val src = out[rng.nextInt(out.size)]
            out.add(FaceEmbedding(id(), model, src.unit.copyOf()))
        }
        return out.shuffled(rng)
    }

    private fun assertSame(a: FaceClusterState, b: FaceClusterState, step: String) {
        assertEquals("clusters differ at $step", b.unknownClusters, a.unknownClusters)
        assertEquals(b.faceCount, a.faceCount)
    }

    private val policies = listOf(
        ClusterPolicy.SFACE_CONSERVATIVE,
        // Loose: many joins, unions and ambiguous ties.
        ClusterPolicy(0.20f, 0.02f, 0.25f, 0.20f, 0.02f),
        // Join and merge thresholds equal, no margin.
        ClusterPolicy(0.35f, 0f, 0.35f, 0.3f, 0f)
    )

    private fun scenario(seed: Int, dim: Int, policy: ClusterPolicy) {
        val rng = Random(seed)
        val all = faces(rng, dim, identities = 8 + rng.nextInt(25), strangers = rng.nextInt(40))
        var fast = FaceClusterState.empty(model, dim, policy)
        var slow = FaceClusterState.empty(model, dim, policy)
        var reviews = ClusterReviewFacts.NONE
        val added = ArrayList<FaceEmbedding>()
        var step = 0
        for (chunk in all.chunked(1 + rng.nextInt(all.size))) {
            val remove = if (added.size > 4 && rng.nextInt(3) == 0) {
                List(2) { added.removeAt(rng.nextInt(added.size)).observation }
            } else emptyList()
            added.addAll(chunk)
            reviews = mutate(reviews, added, remove.toSet(), rng)
            fast = FaceClusterer.update(fast, chunk, remove, reviews)
            slow = ReferenceFaceClusterer.update(slow, chunk, remove, reviews)
            assertSame(fast, slow, "seed $seed dim $dim step ${step++}")
        }
        // Review-only updates: undo some confirmations and splits.
        val undo = ClusterReviewFacts(
            confirmed = reviews.confirmed.entries.filter { rng.nextBoolean() }
                .associate { it.key to it.value },
            cannotLink = reviews.cannotLink.filter { rng.nextBoolean() }.toSet(),
            rejectedPeople = reviews.rejectedPeople)
        assertSame(FaceClusterer.update(fast, reviews = undo),
            ReferenceFaceClusterer.update(slow, reviews = undo), "seed $seed undo")
        // Restore from persisted membership, with one corrupt cancelling pair.
        val persisted = slow.unknownClusters.map { it.members }
        assertSame(
            FaceClusterer.restore(model, policy, added, persisted, reviews),
            ReferenceFaceClusterer.restore(model, policy, added, persisted, reviews),
            "seed $seed restore")
    }

    private fun mutate(
        r: ClusterReviewFacts,
        added: List<FaceEmbedding>,
        removed: Set<NativeFaceObservationId>,
        rng: Random
    ): ClusterReviewFacts {
        val confirmed = HashMap(r.confirmed)
        val cannot = HashSet(r.cannotLink)
        val rejected = HashSet(r.rejectedPeople)
        repeat(rng.nextInt(4)) {
            confirmed[added[rng.nextInt(added.size)].observation] =
                PersonKey("p" + rng.nextInt(4))
        }
        repeat(rng.nextInt(4)) {
            val a = added[rng.nextInt(added.size)].observation
            val b = added[rng.nextInt(added.size)].observation
            if (a != b) cannot.add(FacePair.of(a, b))
        }
        repeat(rng.nextInt(2)) {
            rejected.add(RejectedPerson(added[rng.nextInt(added.size)].observation,
                PersonKey("p" + rng.nextInt(4))))
        }
        confirmed.keys.removeAll(removed)
        return ClusterReviewFacts(confirmed, cannot, rejected)
    }

    @Test fun matchesTheReferenceOnRandomScenarios() {
        var seed = 1
        for (policy in policies) for (dim in listOf(128, 16, 3)) repeat(12) {
            scenario(seed++, dim, policy)
        }
    }

    @Test fun matchesTheReferenceOnAOneShotGalleryWithStrangers() {
        val rng = Random(2024)
        val all = faces(rng, 128, identities = 60, strangers = 600)
        val policy = ClusterPolicy.SFACE_CONSERVATIVE
        val empty = FaceClusterState.empty(model, 128, policy)
        val fast = FaceClusterer.update(empty, add = all)
        assertSame(fast, ReferenceFaceClusterer.update(empty, add = all), "one-shot")
        assertTrue(fast.unknownClusters.size > 600)
    }

    @Test fun zeroNormClusterMatchesTheReference() {
        val dim = 8
        val v = FloatArray(dim) { if (it == 0) 1f else 0f }
        val up = FaceEmbedding(id(), model, v)
        val down = FaceEmbedding(id(), model, FloatArray(dim) { -v[it] })
        val other = FaceEmbedding(id(), model, FloatArray(dim) { if (it == 1) 1f else 0f })
        val known = FaceEmbedding(id(), model, v.copyOf())
        val reviews = ClusterReviewFacts(confirmed = mapOf(known.observation to PersonKey("p")))
        val policy = ClusterPolicy(0f, 0f, 0f, 0f, 0f)
        val faces = listOf(up, down, other, known)
        val persisted = listOf(listOf(up.observation, down.observation))
        assertSame(FaceClusterer.restore(model, policy, faces, persisted, reviews),
            ReferenceFaceClusterer.restore(model, policy, faces, persisted, reviews), "zero")
    }
}
